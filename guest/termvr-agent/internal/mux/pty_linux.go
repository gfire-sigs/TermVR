//go:build linux

package mux

import (
	"context"
	"errors"
	"io"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"sync"
	"syscall"
	"unsafe"
)

type linuxWinSize struct {
	Row    uint16
	Col    uint16
	Xpixel uint16
	Ypixel uint16
}

// Reaper reaps zombie child processes for PID 1 without stealing direct-child exit codes.
type Reaper struct {
	mu      sync.Mutex // guards waiters and closed
	waiters map[int]chan uint8
	closed  bool
}

// NewReaper constructs a PID 1 SIGCHLD reaper.
func NewReaper() *Reaper {
	return &Reaper{
		waiters: make(map[int]chan uint8),
	}
}

// Run drains exited children on SIGCHLD until ctx is cancelled.
func (r *Reaper) Run(ctx context.Context) {
	sigCh := make(chan os.Signal, 16)
	signal.Notify(sigCh, syscall.SIGCHLD)
	defer signal.Stop(sigCh)

	r.reapOnce()
	for {
		select {
		case <-sigCh:
			r.reapOnce()
		case <-ctx.Done():
			r.mu.Lock()
			r.closed = true
			for pid, ch := range r.waiters {
				close(ch)
				delete(r.waiters, pid)
			}
			r.mu.Unlock()
			return
		}
	}
}

func (r *Reaper) reapOnce() {
	r.mu.Lock()
	defer r.mu.Unlock()

	for {
		var ws syscall.WaitStatus
		pid, err := syscall.Wait4(-1, &ws, syscall.WNOHANG, nil)
		if errors.Is(err, syscall.EINTR) {
			continue
		}
		if pid <= 0 {
			return
		}
		if ch, ok := r.waiters[pid]; ok {
			delete(r.waiters, pid)
			ch <- waitStatusToCode(ws)
			close(ch)
		}
	}
}

func (r *Reaper) startProcess(exePath string, argv []string, attr *os.ProcAttr) (*os.Process, chan uint8, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	proc, err := os.StartProcess(exePath, argv, attr)
	if err != nil {
		return nil, nil, err
	}
	ch := make(chan uint8, 1)
	if r.closed {
		close(ch)
		return proc, ch, nil
	}
	r.waiters[proc.Pid] = ch
	return proc, ch, nil
}

func waitStatusToCode(ws syscall.WaitStatus) uint8 {
	if ws.Exited() {
		return uint8(ws.ExitStatus())
	}
	if ws.Signaled() {
		return uint8(128 + int(ws.Signal()))
	}
	return 1
}

// EnsureRootRShared remounts / with MS_REC | MS_SHARED for k3s and container mounts.
func EnsureRootRShared() error {
	if err := syscall.Mount("", "/", "", syscall.MS_REC|syscall.MS_SHARED, ""); err != nil {
		return wrapErr("mount / MS_REC|MS_SHARED", err)
	}
	return nil
}

// EnsureArm64CPUInfoPatched bind-mounts a patched /proc/cpuinfo on gVisor arm64 hosts
// that report "CPU architecture : 0", ignoring permission errors in unprivileged sandboxes.
func EnsureArm64CPUInfoPatched() error {
	if runtime.GOARCH != "arm64" {
		return nil
	}
	raw, err := os.ReadFile("/proc/cpuinfo")
	if err != nil {
		return wrapErr("read /proc/cpuinfo", err)
	}
	patched, changed := PatchArm64ZeroCPUInfo(raw)
	if !changed {
		return nil
	}
	var tmpPath string
	var writeErr error
	for _, path := range []string{"/run/.termvr-cpuinfo", "/tmp/.termvr-cpuinfo"} {
		_ = os.Remove(path)
		if err := os.WriteFile(path, patched, 0o444); err == nil {
			tmpPath = path
			break
		} else {
			writeErr = err
		}
	}
	if tmpPath == "" {
		return wrapErr("write patched cpuinfo", writeErr)
	}
	if err := syscall.Mount(tmpPath, "/proc/cpuinfo", "", syscall.MS_BIND, ""); err != nil {
		_ = os.Remove(tmpPath)
		if errors.Is(err, syscall.EPERM) || errors.Is(err, syscall.EACCES) {
			return nil
		}
		return wrapErr("bind-mount /proc/cpuinfo", err)
	}
	return nil
}

type linuxPTYBackend struct {
	reaper *Reaper
}

func newDefaultPTYBackend(reaper *Reaper) PTYBackend {
	return &linuxPTYBackend{reaper: reaper}
}

type linuxPTYHandle struct {
	master   *os.File
	proc     *os.Process
	pid      int
	reaper   *Reaper
	waitCh   chan uint8
	waitOnce sync.Once
	exitCode uint8
	waitErr  error
}

func (b *linuxPTYBackend) OpenPTY(argv []string, cwd string, env []string, ws WinSize) (PTYHandle, error) {
	exePath, err := lookExecutable(argv[0], env)
	if err != nil {
		return nil, err
	}
	masterFd, err := syscall.Open("/dev/ptmx", syscall.O_RDWR|syscall.O_NOCTTY|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, wrapErr("open /dev/ptmx", err)
	}
	master := os.NewFile(uintptr(masterFd), "/dev/ptmx")

	var ptn uint32
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(masterFd), uintptr(syscall.TIOCGPTN), uintptr(unsafe.Pointer(&ptn))); errno != 0 {
		_ = master.Close()
		return nil, wrapErr("ioctl TIOCGPTN", errno)
	}
	var unlock int32
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(masterFd), uintptr(syscall.TIOCSPTLCK), uintptr(unsafe.Pointer(&unlock))); errno != 0 {
		_ = master.Close()
		return nil, wrapErr("ioctl TIOCSPTLCK", errno)
	}

	slavePath := "/dev/pts/" + strconv.FormatUint(uint64(ptn), 10)
	slaveFd, err := syscall.Open(slavePath, syscall.O_RDWR|syscall.O_NOCTTY|syscall.O_CLOEXEC, 0)
	if err != nil {
		_ = master.Close()
		return nil, wrapErr("open "+slavePath, err)
	}
	slave := os.NewFile(uintptr(slaveFd), slavePath)

	if err := setWinSizeFd(uintptr(masterFd), ws); err != nil {
		_ = slave.Close()
		_ = master.Close()
		return nil, err
	}

	attr := &os.ProcAttr{
		Dir:   cwd,
		Env:   env,
		Files: []*os.File{slave, slave, slave},
		Sys: &syscall.SysProcAttr{
			Setsid:  true,
			Setctty: true,
			Ctty:    0,
		},
	}

	var (
		proc   *os.Process
		waitCh chan uint8
	)
	if b.reaper != nil {
		proc, waitCh, err = b.reaper.startProcess(exePath, argv, attr)
	} else {
		proc, err = os.StartProcess(exePath, argv, attr)
	}
	_ = slave.Close()
	if err != nil {
		_ = master.Close()
		return nil, wrapErr("start pty command "+argv[0], err)
	}

	return &linuxPTYHandle{
		master: master,
		proc:   proc,
		pid:    proc.Pid,
		reaper: b.reaper,
		waitCh: waitCh,
	}, nil
}

func setWinSizeFd(fd uintptr, ws WinSize) error {
	lws := linuxWinSize{
		Row:    ws.Rows,
		Col:    ws.Cols,
		Xpixel: ws.XPixel,
		Ypixel: ws.YPixel,
	}
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, uintptr(syscall.TIOCSWINSZ), uintptr(unsafe.Pointer(&lws))); errno != 0 {
		return wrapErr("ioctl TIOCSWINSZ", errno)
	}
	return nil
}

func (h *linuxPTYHandle) Read(p []byte) (int, error) {
	return h.master.Read(p)
}

func (h *linuxPTYHandle) Write(p []byte) (int, error) {
	return h.master.Write(p)
}

func (h *linuxPTYHandle) Close() error {
	err := h.master.Close()
	if h.pid > 0 {
		_ = syscall.Kill(-h.pid, syscall.SIGHUP)
	}
	return err
}

func (h *linuxPTYHandle) Resize(ws WinSize) error {
	if err := setWinSizeFd(h.master.Fd(), ws); err != nil {
		return err
	}
	return h.signalForeground(syscall.SIGWINCH)
}

func (h *linuxPTYHandle) Signal(sig uint8) error {
	if sig == 0 {
		return nil
	}
	return h.signalForeground(syscall.Signal(sig))
}

func (h *linuxPTYHandle) signalForeground(sig syscall.Signal) error {
	var pgid int32
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, h.master.Fd(), uintptr(syscall.TIOCGPGRP), uintptr(unsafe.Pointer(&pgid))); errno == 0 && pgid > 0 {
		return syscall.Kill(-int(pgid), sig)
	}
	if h.pid > 0 {
		return syscall.Kill(-h.pid, sig)
	}
	return nil
}

func (h *linuxPTYHandle) Wait() (uint8, error) {
	h.waitOnce.Do(func() {
		if h.reaper != nil && h.waitCh != nil {
			code, ok := <-h.waitCh
			if ok {
				h.exitCode = code
			} else {
				h.exitCode = 1
			}
			_ = h.proc.Release()
			return
		}
		st, err := h.proc.Wait()
		h.exitCode, h.waitErr = exitCodeFromProcessState(st, err)
	})
	return h.exitCode, h.waitErr
}

type linuxDaemonHandle struct {
	stdinW   *os.File
	outR     *os.File
	proc     *os.Process
	pid      int
	reaper   *Reaper
	waitCh   chan uint8
	waitOnce sync.Once
	exitCode uint8
	waitErr  error
}

func (b *linuxPTYBackend) StartDaemon(argv []string, cwd string, env []string) (DaemonHandle, error) {
	exePath, err := lookExecutable(argv[0], env)
	if err != nil {
		return nil, err
	}
	inR, inW, err := os.Pipe()
	if err != nil {
		return nil, err
	}
	outR, outW, err := os.Pipe()
	if err != nil {
		_ = inR.Close()
		_ = inW.Close()
		return nil, err
	}

	attr := &os.ProcAttr{
		Dir:   cwd,
		Env:   env,
		Files: []*os.File{inR, outW, outW},
		Sys:   &syscall.SysProcAttr{Setpgid: true},
	}

	var (
		proc   *os.Process
		waitCh chan uint8
	)
	if b.reaper != nil {
		proc, waitCh, err = b.reaper.startProcess(exePath, argv, attr)
	} else {
		proc, err = os.StartProcess(exePath, argv, attr)
	}
	_ = inR.Close()
	_ = outW.Close()
	if err != nil {
		_ = inW.Close()
		_ = outR.Close()
		return nil, wrapErr("start init daemon "+argv[0], err)
	}

	return &linuxDaemonHandle{
		stdinW: inW,
		outR:   outR,
		proc:   proc,
		pid:    proc.Pid,
		reaper: b.reaper,
		waitCh: waitCh,
	}, nil
}

func (d *linuxDaemonHandle) Stdin() io.WriteCloser {
	return d.stdinW
}

func (d *linuxDaemonHandle) Output() io.Reader {
	return d.outR
}

func (d *linuxDaemonHandle) Wait() (uint8, error) {
	d.waitOnce.Do(func() {
		if d.reaper != nil && d.waitCh != nil {
			code, ok := <-d.waitCh
			if ok {
				d.exitCode = code
			} else {
				d.exitCode = 1
			}
			_ = d.proc.Release()
			return
		}
		st, err := d.proc.Wait()
		d.exitCode, d.waitErr = exitCodeFromProcessState(st, err)
	})
	return d.exitCode, d.waitErr
}

func (d *linuxDaemonHandle) Close() error {
	_ = d.stdinW.Close()
	_ = d.outR.Close()
	if d.pid > 0 {
		_ = syscall.Kill(-d.pid, syscall.SIGTERM)
	}
	return nil
}

func exitCodeFromProcessState(st *os.ProcessState, err error) (uint8, error) {
	if err != nil {
		return 1, err
	}
	if st != nil {
		if ws, ok := st.Sys().(syscall.WaitStatus); ok {
			return waitStatusToCode(ws), nil
		}
		return uint8(st.ExitCode()), nil
	}
	return 0, nil
}
