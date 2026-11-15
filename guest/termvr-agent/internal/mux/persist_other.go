//go:build !linux

package mux

import (
	"errors"
	"os"
	"syscall"
	"time"
)

func hostLstatMeta(path string) (fileMeta, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return fileMeta{}, err
	}
	mode := uint32(info.Mode().Perm())
	m := info.Mode()
	switch {
	case m.IsDir():
		mode |= 0o040000
	case m&os.ModeSymlink != 0:
		mode |= 0o120000
	case m&os.ModeNamedPipe != 0:
		mode |= 0o010000
	case m&os.ModeSocket != 0:
		mode |= 0o140000
	case m&os.ModeDevice != 0:
		if m&os.ModeCharDevice != 0 {
			mode |= 0o020000
		} else {
			mode |= 0o060000
		}
	default:
		mode |= 0o100000
	}
	if m&os.ModeSetuid != 0 {
		mode |= 0o4000
	}
	if m&os.ModeSetgid != 0 {
		mode |= 0o2000
	}
	if m&os.ModeSticky != 0 {
		mode |= 0o1000
	}
	mt := info.ModTime()
	meta := fileMeta{
		mode:      mode,
		uid:       -1,
		gid:       -1,
		atimeSec:  mt.Unix(),
		atimeNsec: int64(mt.Nanosecond()),
		mtimeSec:  mt.Unix(),
		mtimeNsec: int64(mt.Nanosecond()),
	}
	if st, ok := info.Sys().(*syscall.Stat_t); ok {
		meta.dev = uint64(st.Dev)
		meta.ino = uint64(st.Ino)
		meta.nlink = uint64(st.Nlink)
		meta.uid = int(st.Uid)
		meta.gid = int(st.Gid)
		meta.rdev = uint64(st.Rdev)
	}
	return meta, nil
}

func hostLchown(path string, uid, gid int) error {
	if uid < 0 && gid < 0 {
		return nil
	}
	err := os.Lchown(path, uid, gid)
	if errors.Is(err, syscall.EPERM) {
		return nil
	}
	return err
}

func hostChmod(path string, mode uint32) error {
	perm := os.FileMode(mode & 0o777)
	if mode&0o4000 != 0 {
		perm |= os.ModeSetuid
	}
	if mode&0o2000 != 0 {
		perm |= os.ModeSetgid
	}
	if mode&0o1000 != 0 {
		perm |= os.ModeSticky
	}
	return os.Chmod(path, perm)
}

func hostUtimensNofollow(path string, atimeSec, atimeNsec, mtimeSec, mtimeNsec int64) error {
	info, err := os.Lstat(path)
	if err == nil && info.Mode()&os.ModeSymlink != 0 {
		return nil
	}
	return os.Chtimes(path, time.Unix(atimeSec, atimeNsec), time.Unix(mtimeSec, mtimeNsec))
}

func hostMknod(path string, mode uint32, rdev uint64) error {
	if mode&0o170000 == 0o010000 {
		return syscall.Mkfifo(path, mode&0o777)
	}
	return errors.New("mknod non-fifo unsupported on non-linux")
}

func hostCopyXattrs(_, _ string) error {
	return nil
}

// SetupPersistentRoot is unsupported outside Linux sandboxes.
func SetupPersistentRoot(_ string) error {
	return errors.New("persistent root pivot is only supported on Linux")
}

// ResolveExecutable locates bin in PATH when it does not contain a slash.
func ResolveExecutable(bin string, _ []string) (string, error) {
	return bin, nil
}
