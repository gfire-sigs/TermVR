package mux

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

var (
	ErrNoShellFound   = errors.New("no guest shell found in /bin/bash, /bin/ash, /bin/sh, or /bin/aux/sh")
	ErrInvalidWorkDir = errors.New("requested working directory does not exist")
)

var defaultShellCandidates = []string{
	"/bin/bash",
	"/bin/ash",
	"/bin/sh",
	"/bin/aux/sh",
}

// PTYHandle abstracts one allocated guest pseudo-terminal and child process.
type PTYHandle interface {
	io.ReadWriteCloser
	Resize(ws WinSize) error
	Signal(sig uint8) error
	Wait() (uint8, error)
}

// DaemonHandle abstracts the supervised Stream-0 init daemon process.
type DaemonHandle interface {
	Stdin() io.WriteCloser
	Output() io.Reader
	Wait() (uint8, error)
	Close() error
}

// PTYBackend allocates guest PTY sessions and supervises Stream-0 daemons.
type PTYBackend interface {
	OpenPTY(argv []string, cwd string, env []string, ws WinSize) (PTYHandle, error)
	StartDaemon(argv []string, cwd string, env []string) (DaemonHandle, error)
}

// ResolveShellArgv returns argv when non-empty, or probes /bin/bash, /bin/ash, and /bin/sh.
func ResolveShellArgv(argv []string, rootDir string) ([]string, error) {
	if len(argv) > 0 && asciiTrimSpace(argv[0]) != "" {
		cp := make([]string, len(argv))
		copy(cp, argv)
		return cp, nil
	}
	if rootDir == "" {
		rootDir = "/"
	}
	for _, candidate := range defaultShellCandidates {
		hostPath := candidate
		if rootDir != "/" {
			hostPath = filepath.Join(rootDir, strings.TrimPrefix(candidate, "/"))
		}
		info, err := os.Stat(hostPath)
		if err == nil && !info.IsDir() && info.Mode()&0o111 != 0 {
			return []string{candidate}, nil
		}
	}
	return nil, ErrNoShellFound
}

// ResolveWorkDir selects /workspace when present and falls back to /.
func ResolveWorkDir(requested string, rootDir string) (string, error) {
	if rootDir == "" {
		rootDir = "/"
	}
	checkDir := func(guestDir string) bool {
		target := guestDir
		if rootDir != "/" {
			target = filepath.Join(rootDir, strings.TrimPrefix(guestDir, "/"))
		}
		info, err := os.Stat(target)
		return err == nil && info.IsDir()
	}

	req := asciiTrimSpace(requested)
	if req == "" || req == "/workspace" {
		if checkDir("/workspace") {
			return "/workspace", nil
		}
		return "/", nil
	}
	if !filepath.IsAbs(req) {
		return "", wrapErr("path must be absolute: "+req, ErrInvalidWorkDir)
	}
	clean := filepath.Clean(req)
	if !checkDir(clean) {
		return "", wrapErr(clean, ErrInvalidWorkDir)
	}
	return clean, nil
}

//go:noinline
func setEnvKV(env []string, k, v string) []string {
	prefix := k + "="
	for i, e := range env {
		if strings.HasPrefix(e, prefix) {
			env[i] = prefix + v
			return env
		}
	}
	return append(env, prefix+v)
}

//go:noinline
func getEnvVal(env []string, k string) string {
	prefix := k + "="
	for _, e := range env {
		if strings.HasPrefix(e, prefix) {
			return e[len(prefix):]
		}
	}
	return ""
}

// BuildGuestEnv merges base environment, TERM/COLORTERM defaults, overrides, and binDir PATH prefix.
func BuildGuestEnv(baseEnv []string, overrides map[string]string, binDir string) []string {
	out := make([]string, 0, len(baseEnv)+len(overrides)+4)
	for _, entry := range baseEnv {
		if k, v, ok := strings.Cut(entry, "="); ok && k != "" {
			out = setEnvKV(out, k, v)
		}
	}
	if getEnvVal(out, "TERM") == "" {
		out = setEnvKV(out, "TERM", "xterm-256color")
	}
	if getEnvVal(out, "COLORTERM") == "" {
		out = setEnvKV(out, "COLORTERM", "truecolor")
	}
	if getEnvVal(out, "TERM_PROGRAM") == "" {
		out = setEnvKV(out, "TERM_PROGRAM", "TermVR")
	}
	for k, v := range overrides {
		if k != "" && strings.IndexByte(k, 0) < 0 && strings.IndexByte(v, 0) < 0 {
			out = setEnvKV(out, k, v)
		}
	}
	if binDir == "" {
		binDir = "/run/termvr-agent/bin"
	}
	curPath := getEnvVal(out, "PATH")
	if curPath == "" {
		out = setEnvKV(out, "PATH", binDir+":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/bin/aux")
	} else if !strings.HasPrefix(curPath, binDir+":") && curPath != binDir {
		newPath := binDir
		for rest := curPath; rest != ""; {
			var p string
			p, rest, _ = strings.Cut(rest, ":")
			if p != "" && p != binDir {
				newPath += ":" + p
			}
		}
		out = setEnvKV(out, "PATH", newPath)
	}
	sortStrings(out)
	return out
}

func lookExecutable(file string, env []string) (string, error) {
	if file == "" {
		return "", os.ErrNotExist
	}
	if strings.Contains(file, "/") {
		return file, nil
	}
	pathVal := getEnvVal(env, "PATH")
	if pathVal == "" {
		pathVal = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
	}
	for rest := pathVal; rest != ""; {
		var dir string
		dir, rest, _ = strings.Cut(rest, ":")
		if dir == "" {
			dir = "."
		}
		cand := filepath.Join(dir, file)
		if info, err := os.Stat(cand); err == nil && !info.IsDir() && info.Mode()&0o111 != 0 {
			return cand, nil
		}
	}
	return "", wrapErr("executable not found in PATH: "+file, os.ErrNotExist)
}
