package mux

import (
	"io"
	"os"
	"path/filepath"
	"strings"
)

const (
	// PersistReadyFileName marks a completed rootfs population inside persistDir.
	PersistReadyFileName = ".termvr-rootfs-ready"
	persistRootSubdir    = "root"
	persistOldRootName   = ".oldroot"
)

// ProcMountEntry describes a single mount line parsed from /proc/mounts.
type ProcMountEntry struct {
	MountPoint string
	FSType     string
}

type fileMeta struct {
	dev       uint64
	ino       uint64
	nlink     uint64
	mode      uint32
	uid       int
	gid       int
	rdev      uint64
	atimeSec  int64
	atimeNsec int64
	mtimeSec  int64
	mtimeNsec int64
}

type devInoKey struct {
	dev uint64
	ino uint64
}

// ParseProcMounts parses /proc/mounts contents and unescapes octal escapes.
func ParseProcMounts(raw string) []ProcMountEntry {
	var out []ProcMountEntry
	for rest := raw; rest != ""; {
		var line string
		line, rest, _ = strings.Cut(rest, "\n")
		line = asciiTrimSpace(line)
		if line == "" || line[0] == '#' {
			continue
		}
		fields := splitASCIIFields(line)
		if len(fields) < 3 {
			continue
		}
		mp := filepath.Clean(unescapeMountField(fields[1]))
		if len(mp) > 0 && mp[0] == '/' {
			out = append(out, ProcMountEntry{MountPoint: mp, FSType: fields[2]})
		}
	}
	return out
}

// SelectBindMounts returns depth-sorted uncovered mount points to bind into the persistent root.
func SelectBindMounts(entries []ProcMountEntry, persistDir string) []string {
	cleanPersist := filepath.Clean(persistDir)
	persistPrefix := cleanPersist + "/"

	seen := make(map[string]struct{}, len(entries))
	candidates := make([]string, 0, len(entries))
	for _, e := range entries {
		mp := filepath.Clean(e.MountPoint)
		if mp == "/" || mp == cleanPersist || strings.HasPrefix(mp, persistPrefix) {
			continue
		}
		if _, dup := seen[mp]; dup {
			continue
		}
		seen[mp] = struct{}{}
		candidates = append(candidates, mp)
	}

	for i := 1; i < len(candidates); i++ {
		for j := i; j > 0 && pathDepthLess(candidates[j], candidates[j-1]); j-- {
			candidates[j-1], candidates[j] = candidates[j], candidates[j-1]
		}
	}

	selected := make([]string, 0, len(candidates))
	for _, mp := range candidates {
		covered := false
		for _, parent := range selected {
			if mp == parent || strings.HasPrefix(mp, parent+"/") {
				covered = true
				break
			}
		}
		if !covered {
			selected = append(selected, mp)
		}
	}
	return selected
}

// EnsurePersistentRootPopulated copies srcRoot into persistDir/root on first boot
// and refreshes boot-generated /etc network, host, and CA files on every boot.
func EnsurePersistentRootPopulated(
	srcRoot string,
	persistDir string,
	mountPoints []string,
) (populatedNow bool, err error) {
	cleanSrc := filepath.Clean(srcRoot)
	cleanPersist := filepath.Clean(persistDir)
	rootDir := filepath.Join(cleanPersist, persistRootSubdir)
	readyPath := filepath.Join(cleanPersist, PersistReadyFileName)

	if st, statErr := os.Stat(readyPath); statErr == nil && !st.IsDir() {
		return false, RefreshBootEtcFiles(cleanSrc, rootDir)
	}

	if err := os.MkdirAll(rootDir, 0o755); err != nil {
		return false, err
	}

	skipAbs := buildCopySkipSet(cleanSrc, cleanPersist, mountPoints)
	if err := copyNode(cleanSrc, rootDir, cleanSrc, "", skipAbs, make(map[devInoKey]string, 256), make([]byte, 64*1024)); err != nil {
		return false, err
	}
	if err := RefreshBootEtcFiles(cleanSrc, rootDir); err != nil {
		return false, err
	}

	tmpReady := readyPath + ".tmp"
	_ = os.Remove(tmpReady)
	f, err := os.OpenFile(tmpReady, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return false, err
	}
	_, err = f.WriteString("ready\n")
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(tmpReady)
		return false, err
	}
	if err := os.Rename(tmpReady, readyPath); err != nil {
		_ = os.Remove(tmpReady)
		return false, err
	}
	return true, nil
}

// RefreshBootEtcFiles copies boot-time /etc network, host, and gvx CA files from srcRoot to dstRoot.
func RefreshBootEtcFiles(srcRoot, dstRoot string) error {
	for _, rel := range []string{"etc/resolv.conf", "etc/hosts", "etc/hostname"} {
		if err := copyRegularFileIfPresent(filepath.Join(srcRoot, rel), filepath.Join(dstRoot, rel), 0o644); err != nil {
			return err
		}
	}

	hasHostAnchors := false
	for _, anchorDir := range []string{"usr/local/share/ca-certificates", "etc/pki/ca-trust/source/anchors"} {
		names, err := readDirNames(filepath.Join(srcRoot, anchorDir))
		if err != nil {
			continue
		}
		for _, name := range names {
			if strings.HasPrefix(name, "gvx-host-") {
				if err := copyRegularFileIfPresent(filepath.Join(srcRoot, anchorDir, name), filepath.Join(dstRoot, anchorDir, name), 0o644); err != nil {
					return err
				}
				hasHostAnchors = true
			}
		}
	}
	if hasHostAnchors {
		for _, bundleRel := range []string{"etc/ssl/certs/ca-certificates.crt", "etc/pki/tls/certs/ca-bundle.crt", "etc/ssl/cert.pem"} {
			if err := mergePemBundleIfPresent(filepath.Join(srcRoot, bundleRel), filepath.Join(dstRoot, bundleRel)); err != nil {
				return err
			}
		}
	}
	return nil
}

func readDirNames(dirPath string) ([]string, error) {
	d, err := os.Open(dirPath)
	if err != nil {
		return nil, err
	}
	names, rerr := d.Readdirnames(-1)
	_ = d.Close()
	return names, rerr
}

func copyRegularFileIfPresent(src, dst string, mode os.FileMode) error {
	info, err := os.Lstat(src)
	if err != nil || !info.Mode().IsRegular() {
		return nil
	}
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	if dstInfo, lerr := os.Lstat(dst); lerr == nil && dstInfo.Mode()&os.ModeSymlink != 0 {
		_ = os.Remove(dst)
	}
	return os.WriteFile(dst, data, mode)
}

func mergePemBundleIfPresent(srcBundle, dstBundle string) error {
	srcInfo, err := os.Lstat(srcBundle)
	if err != nil || !srcInfo.Mode().IsRegular() {
		return nil
	}
	srcData, err := os.ReadFile(srcBundle)
	if err != nil {
		return err
	}
	dstInfo, err := os.Lstat(dstBundle)
	if err != nil {
		return copyRegularFileIfPresent(srcBundle, dstBundle, 0o644)
	}
	if dstInfo.Mode()&os.ModeSymlink != 0 {
		return nil
	}
	dstData, err := os.ReadFile(dstBundle)
	if err != nil {
		return err
	}
	dstStr := string(dstData)
	extra := ""
	const beginMarker = "-----BEGIN CERTIFICATE-----"
	const endMarker = "-----END CERTIFICATE-----"
	for pos := 0; pos < len(srcData); {
		startRel := strings.Index(string(srcData[pos:]), beginMarker)
		if startRel < 0 {
			break
		}
		start := pos + startRel
		endRel := strings.Index(string(srcData[start:]), endMarker)
		if endRel < 0 {
			break
		}
		end := start + endRel + len(endMarker)
		if end < len(srcData) && srcData[end] == '\r' {
			end++
		}
		if end < len(srcData) && srcData[end] == '\n' {
			end++
		}
		blk := string(srcData[start:end])
		if !strings.Contains(dstStr, blk) {
			if len(dstStr) > 0 && dstStr[len(dstStr)-1] != '\n' && extra == "" {
				extra = "\n"
			}
			extra += blk
		}
		pos = end
	}
	if extra == "" {
		return nil
	}
	f, err := os.OpenFile(dstBundle, os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	_, err = f.WriteString(extra)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

func buildCopySkipSet(srcRoot, persistDir string, mountPoints []string) map[string]struct{} {
	skips := make(map[string]struct{}, 8+len(mountPoints))
	for _, rel := range []string{"proc", "sys", "dev", "run", "tmp", "workspace"} {
		skips[filepath.Join(srcRoot, rel)] = struct{}{}
	}
	if persistDir != "" {
		cleanP := filepath.Clean(persistDir)
		skips[cleanP] = struct{}{}
		if !strings.HasPrefix(cleanP, srcRoot) {
			skips[filepath.Join(srcRoot, strings.TrimPrefix(cleanP, "/"))] = struct{}{}
		}
	}
	for _, mp := range mountPoints {
		cleanMP := filepath.Clean(mp)
		if cleanMP != "/" {
			skips[filepath.Join(srcRoot, strings.TrimPrefix(cleanMP, "/"))] = struct{}{}
		}
	}
	return skips
}

func copyNode(
	srcRoot string,
	dstRoot string,
	srcPath string,
	relPath string,
	skipAbs map[string]struct{},
	seen map[devInoKey]string,
	buf []byte,
) error {
	meta, err := hostLstatMeta(srcPath)
	if err != nil {
		return err
	}
	dstPath := dstRoot
	if relPath != "" {
		dstPath = filepath.Join(dstRoot, relPath)
	}

	ft := meta.mode & 0o170000
	if relPath != "" {
		if _, skip := skipAbs[srcPath]; skip {
			if ft == 0o040000 {
				if err := ensureDirForCopy(dstPath); err != nil {
					return err
				}
			} else {
				if existing, lerr := os.Lstat(dstPath); lerr == nil && existing.IsDir() {
					_ = os.RemoveAll(dstPath)
				}
				f, ferr := os.OpenFile(dstPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
				if ferr != nil {
					return ferr
				}
				_ = f.Close()
			}
			return applyNodeMeta(srcPath, dstPath, meta, false)
		}
	}

	if ft == 0o040000 {
		if err := ensureDirForCopy(dstPath); err != nil {
			return err
		}
		_ = hostChmod(dstPath, 0o700)

		names, err := readDirNames(srcPath)
		if err != nil {
			return err
		}
		for _, name := range names {
			if name == "." || name == ".." {
				continue
			}
			childRel := name
			if relPath != "" {
				childRel = filepath.Join(relPath, name)
			}
			if err := copyNode(srcRoot, dstRoot, filepath.Join(srcPath, name), childRel, skipAbs, seen, buf); err != nil {
				return err
			}
		}
		return applyNodeMeta(srcPath, dstPath, meta, false)
	}

	if _, lerr := os.Lstat(dstPath); lerr == nil {
		if err := os.RemoveAll(dstPath); err != nil {
			return err
		}
	}

	if meta.nlink > 1 && meta.ino != 0 {
		if existingTarget, ok := seen[devInoKey{meta.dev, meta.ino}]; ok {
			if os.Link(existingTarget, dstPath) == nil {
				return nil
			}
		}
	}

	isSymlink := false
	switch ft {
	case 0o120000:
		linkTarget, err := os.Readlink(srcPath)
		if err != nil {
			return err
		}
		if err := os.Symlink(linkTarget, dstPath); err != nil {
			return err
		}
		isSymlink = true
	case 0o100000:
		if err := copyRegContents(srcPath, dstPath, buf); err != nil {
			return err
		}
	case 0o010000, 0o020000, 0o060000, 0o140000:
		if err := hostMknod(dstPath, meta.mode, meta.rdev); err != nil {
			if ft != 0o010000 {
				return nil
			}
			return err
		}
	default:
		return nil
	}

	if meta.nlink > 1 && meta.ino != 0 {
		seen[devInoKey{meta.dev, meta.ino}] = dstPath
	}
	return applyNodeMeta(srcPath, dstPath, meta, isSymlink)
}

func ensureDirForCopy(dstPath string) error {
	if info, err := os.Lstat(dstPath); err == nil {
		if info.IsDir() {
			return nil
		}
		if err := os.RemoveAll(dstPath); err != nil {
			return err
		}
	}
	return os.MkdirAll(dstPath, 0o755)
}

func copyRegContents(srcPath, dstPath string, buf []byte) error {
	in, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer func() { _ = in.Close() }()

	out, err := os.OpenFile(dstPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	for {
		nr, rerr := in.Read(buf)
		if nr > 0 {
			for off := 0; off < nr; {
				nw, werr := out.Write(buf[off:nr])
				if werr != nil {
					_ = out.Close()
					return werr
				}
				off += nw
			}
		}
		if rerr != nil {
			if rerr == io.EOF {
				break
			}
			_ = out.Close()
			return rerr
		}
	}
	return out.Close()
}

func applyNodeMeta(srcPath, dstPath string, meta fileMeta, isSymlink bool) error {
	if err := hostLchown(dstPath, meta.uid, meta.gid); err != nil {
		return err
	}
	if !isSymlink {
		if err := hostChmod(dstPath, meta.mode&0o7777); err != nil {
			return err
		}
	}
	_ = hostCopyXattrs(srcPath, dstPath)
	_ = hostUtimensNofollow(dstPath, meta.atimeSec, meta.atimeNsec, meta.mtimeSec, meta.mtimeNsec)
	return nil
}

func unescapeMountField(s string) string {
	if strings.IndexByte(s, '\\') < 0 {
		return s
	}
	out := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+3 < len(s) {
			b := byte(0)
			switch s[i+1 : i+4] {
			case "040":
				b = ' '
			case "011":
				b = '\t'
			case "012":
				b = '\n'
			case "134":
				b = '\\'
			}
			if b != 0 {
				out = append(out, b)
				i += 3
				continue
			}
		}
		out = append(out, s[i])
	}
	return string(out)
}

func pathDepthLess(a, b string) bool {
	da, db := 0, 0
	for i := 0; i < len(a); i++ {
		if a[i] == '/' {
			da++
		}
	}
	for i := 0; i < len(b); i++ {
		if b[i] == '/' {
			db++
		}
	}
	if da != db {
		return da < db
	}
	if len(a) != len(b) {
		return len(a) < len(b)
	}
	return a < b
}
