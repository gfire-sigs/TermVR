//go:build linux

package mux

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

func hostLstatMeta(path string) (fileMeta, error) {
	var st syscall.Stat_t
	if err := syscall.Lstat(path, &st); err != nil {
		return fileMeta{}, err
	}
	return fileMeta{
		dev:       uint64(st.Dev),
		ino:       uint64(st.Ino),
		nlink:     uint64(st.Nlink),
		mode:      st.Mode,
		uid:       int(st.Uid),
		gid:       int(st.Gid),
		rdev:      uint64(st.Rdev),
		atimeSec:  int64(st.Atim.Sec),
		atimeNsec: int64(st.Atim.Nsec),
		mtimeSec:  int64(st.Mtim.Sec),
		mtimeNsec: int64(st.Mtim.Nsec),
	}, nil
}

func hostLchown(path string, uid, gid int) error {
	err := syscall.Lchown(path, uid, gid)
	if errors.Is(err, syscall.EPERM) {
		return nil
	}
	return err
}

func hostChmod(path string, mode uint32) error {
	return syscall.Chmod(path, mode)
}

func hostMknod(path string, mode uint32, rdev uint64) error {
	return syscall.Mknod(path, mode, int(rdev))
}

func hostUtimensNofollow(path string, atimeSec, atimeNsec, mtimeSec, mtimeNsec int64) error {
	atFDCWD := -100
	const atSymlinkNofollow = 0x100
	pathPtr, err := syscall.BytePtrFromString(path)
	if err != nil {
		return err
	}
	ts := [2]syscall.Timespec{
		{Sec: atimeSec, Nsec: atimeNsec},
		{Sec: mtimeSec, Nsec: mtimeNsec},
	}
	_, _, errno := syscall.Syscall6(
		syscall.SYS_UTIMENSAT,
		uintptr(atFDCWD),
		uintptr(unsafe.Pointer(pathPtr)),
		uintptr(unsafe.Pointer(&ts[0])),
		uintptr(atSymlinkNofollow),
		0,
		0,
	)
	if errno != 0 {
		return errno
	}
	return nil
}

func hostCopyXattrs(src, dst string) error {
	srcPtr, err := syscall.BytePtrFromString(src)
	if err != nil {
		return nil
	}
	dstPtr, err := syscall.BytePtrFromString(dst)
	if err != nil {
		return nil
	}

	sz, _, errno := syscall.Syscall6(
		syscall.SYS_LLISTXATTR,
		uintptr(unsafe.Pointer(srcPtr)),
		0,
		0,
		0,
		0,
		0,
	)
	if errno != 0 || sz == 0 {
		return nil
	}
	namesBuf := make([]byte, int(sz))
	sz, _, errno = syscall.Syscall6(
		syscall.SYS_LLISTXATTR,
		uintptr(unsafe.Pointer(srcPtr)),
		uintptr(unsafe.Pointer(&namesBuf[0])),
		uintptr(len(namesBuf)),
		0,
		0,
		0,
	)
	if errno != 0 || sz == 0 {
		return nil
	}
	namesBuf = namesBuf[:int(sz)]

	for len(namesBuf) > 0 {
		nul := -1
		for i, b := range namesBuf {
			if b == 0 {
				nul = i
				break
			}
		}
		var name string
		if nul >= 0 {
			name = string(namesBuf[:nul])
			namesBuf = namesBuf[nul+1:]
		} else {
			name = string(namesBuf)
			namesBuf = nil
		}
		if name == "" {
			continue
		}
		namePtr, nerr := syscall.BytePtrFromString(name)
		if nerr != nil {
			continue
		}
		valLen, _, gerr := syscall.Syscall6(
			syscall.SYS_LGETXATTR,
			uintptr(unsafe.Pointer(srcPtr)),
			uintptr(unsafe.Pointer(namePtr)),
			0,
			0,
			0,
			0,
		)
		if gerr != 0 {
			continue
		}
		var valBuf []byte
		var valPtr uintptr
		if valLen > 0 {
			valBuf = make([]byte, int(valLen))
			valLen, _, gerr = syscall.Syscall6(
				syscall.SYS_LGETXATTR,
				uintptr(unsafe.Pointer(srcPtr)),
				uintptr(unsafe.Pointer(namePtr)),
				uintptr(unsafe.Pointer(&valBuf[0])),
				uintptr(len(valBuf)),
				0,
				0,
			)
			if gerr != 0 {
				continue
			}
			valPtr = uintptr(unsafe.Pointer(&valBuf[0]))
		}
		_, _, _ = syscall.Syscall6(
			syscall.SYS_LSETXATTR,
			uintptr(unsafe.Pointer(dstPtr)),
			uintptr(unsafe.Pointer(namePtr)),
			valPtr,
			uintptr(valLen),
			0,
			0,
		)
	}
	return nil
}

// SetupPersistentRoot populates persistDir/root on first boot, refreshes /etc files,
// bind-mounts runtime mountpoints into persistDir/root, and pivots / onto it.
func SetupPersistentRoot(persistDir string) error {
	cleanPersist := filepath.Clean(persistDir)
	if cleanPersist == "" || cleanPersist == "/" || cleanPersist[0] != '/' {
		return errors.New("invalid persist-root-dir: " + persistDir)
	}

	rawMounts, err := os.ReadFile("/proc/mounts")
	if err != nil {
		return err
	}
	mountEntries := ParseProcMounts(string(rawMounts))
	allMountPoints := make([]string, 0, len(mountEntries))
	for _, e := range mountEntries {
		allMountPoints = append(allMountPoints, e.MountPoint)
	}
	bindMounts := SelectBindMounts(mountEntries, cleanPersist)

	if _, err := EnsurePersistentRootPopulated("/", cleanPersist, allMountPoints); err != nil {
		return err
	}

	rootDir := filepath.Join(cleanPersist, persistRootSubdir)
	if err := syscall.Mount(rootDir, rootDir, "", syscall.MS_BIND, ""); err != nil {
		return err
	}

	for _, mp := range bindMounts {
		target := filepath.Join(rootDir, strings.TrimPrefix(mp, "/"))
		info, serr := os.Lstat(mp)
		if serr != nil {
			continue
		}
		if info.IsDir() {
			if err := os.MkdirAll(target, info.Mode().Perm()); err != nil {
				return err
			}
		} else {
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			if _, lerr := os.Lstat(target); lerr != nil {
				f, ferr := os.OpenFile(target, os.O_CREATE|os.O_WRONLY, 0o644)
				if ferr != nil {
					return ferr
				}
				_ = f.Close()
			}
		}
		if err := syscall.Mount(mp, target, "", syscall.MS_BIND|syscall.MS_REC, ""); err != nil {
			return err
		}
	}

	oldRoot := filepath.Join(rootDir, persistOldRootName)
	if err := os.MkdirAll(oldRoot, 0o700); err != nil {
		return err
	}
	if err := syscall.PivotRoot(rootDir, oldRoot); err != nil {
		return err
	}
	if err := syscall.Chdir("/"); err != nil {
		return err
	}
	if err := syscall.Unmount("/"+persistOldRootName, syscall.MNT_DETACH); err != nil {
		return err
	}
	_ = os.Remove("/" + persistOldRootName)
	return nil
}
