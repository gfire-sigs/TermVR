//go:build !linux

package mux

import (
	"context"
	"errors"
	"io"
	"os"
	"sync"
	"syscall"
)

// Reaper is a no-op stub on non-Linux hosts used only for cross-OS unit testing.
type Reaper struct{}

// NewReaper constructs a non-Linux Reaper stub.
func NewReaper() *Reaper {
	return &Reaper{}
}

// Run blocks until ctx is cancelled on non-Linux hosts.
func (r *Reaper) Run(ctx context.Context) {
	<-ctx.Done()
}

// EnsureRootRShared reports that Linux mount propagation is unavailable on non-Linux hosts.
func EnsureRootRShared() error {
	return errors.New("MS_SHARED root remount requires Linux")
}

// EnsureArm64CPUInfoPatched is a no-op on non-Linux hosts.
func EnsureArm64CPUInfoPatched() error {
	return nil
}

type portablePTYBackend struct{}

func newDefaultPTYBackend(_ *Reaper) PTYBackend {
	return &portablePTYBackend{}
}

type portablePTYHandle struct {
	inW      *os.File
	outR     *os.File
	proc     *os.Process
	pid      int
	mu       sync.Mutex // guards winSize
	winSize  WinSize
	waitOnce sync.Once
	exitCode uint8
	waitErr  error
}

func (b *portablePTYBackend) OpenPTY(argv []string, cwd string, env []string, ws WinSize) (PTYHandle, error) {
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

	proc, err := os.StartProcess(exePath, argv, &os.ProcAttr{
		Dir:   cwd,
		Env:   env,
		Files: []*os.File{inR, outW, outW},
		Sys:   &syscall.SysProcAttr{Setpgid: true},
	})
	_ = inR.Close()
	_ = outW.Close()
	if err != nil {
		_ = inW.Close()
		_ = outR.Close()
		return nil, wrapErr("start portable pty command "+argv[0], err)
	}

	return &portablePTYHandle{
		inW:     inW,
		outR:    outR,
		proc:    proc,
		pid:     proc.Pid,
		winSize: ws,
	}, nil
}

func (h *portablePTYHandle) Read(p []byte) (int, error) {
	return h.outR.Read(p)
}

func (h *portablePTYHandle) Write(p []byte) (int, error) {
	return h.inW.Write(p)
}

func (h *portablePTYHandle) Close() error {
	_ = h.inW.Close()
	err := h.outR.Close()
	if h.pid > 0 {
		_ = syscall.Kill(-h.pid, syscall.SIGHUP)
	}
	return err
}

func (h *portablePTYHandle) Resize(ws WinSize) error {
	h.mu.Lock()
	h.winSize = ws
	h.mu.Unlock()
	if h.pid > 0 {
		_ = syscall.Kill(-h.pid, syscall.SIGWINCH)
	}
	return nil
}

func (h *portablePTYHandle) Signal(sig uint8) error {
	if sig == 0 || h.pid <= 0 {
		return nil
	}
	return syscall.Kill(-h.pid, syscall.Signal(sig))
}

func (h *portablePTYHandle) Wait() (uint8, error) {
	h.waitOnce.Do(func() {
		st, err := h.proc.Wait()
		if err != nil {
			h.exitCode = 1
			h.waitErr = err
			return
		}
		if ws, ok := st.Sys().(syscall.WaitStatus); ok {
			if ws.Exited() {
				h.exitCode = uint8(ws.ExitStatus())
				return
			}
			if ws.Signaled() {
				h.exitCode = uint8(128 + int(ws.Signal()))
				return
			}
		}
		h.exitCode = uint8(st.ExitCode())
	})
	return h.exitCode, h.waitErr
}

type portableDaemonHandle struct {
	inW      *os.File
	outR     *os.File
	proc     *os.Process
	pid      int
	waitOnce sync.Once
	exitCode uint8
	waitErr  error
}

func (b *portablePTYBackend) StartDaemon(argv []string, cwd string, env []string) (DaemonHandle, error) {
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
	proc, err := os.StartProcess(exePath, argv, &os.ProcAttr{
		Dir:   cwd,
		Env:   env,
		Files: []*os.File{inR, outW, outW},
		Sys:   &syscall.SysProcAttr{Setpgid: true},
	})
	_ = inR.Close()
	_ = outW.Close()
	if err != nil {
		_ = inW.Close()
		_ = outR.Close()
		return nil, err
	}
	return &portableDaemonHandle{
		inW:  inW,
		outR: outR,
		proc: proc,
		pid:  proc.Pid,
	}, nil
}

func (d *portableDaemonHandle) Stdin() io.WriteCloser {
	return d.inW
}

func (d *portableDaemonHandle) Output() io.Reader {
	return d.outR
}

func (d *portableDaemonHandle) Wait() (uint8, error) {
	d.waitOnce.Do(func() {
		st, err := d.proc.Wait()
		if err != nil {
			d.exitCode = 1
			d.waitErr = err
			return
		}
		d.exitCode = uint8(st.ExitCode())
	})
	return d.exitCode, d.waitErr
}

func (d *portableDaemonHandle) Close() error {
	_ = d.stdinWClose()
	_ = d.outR.Close()
	if d.pid > 0 {
		_ = syscall.Kill(-d.pid, syscall.SIGTERM)
	}
	return nil
}

func (d *portableDaemonHandle) stdinWClose() error {
	return d.inW.Close()
}
