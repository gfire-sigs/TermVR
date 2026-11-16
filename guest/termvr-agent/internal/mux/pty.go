package mux

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

var (
	ErrNoShellFound   = errors.New("no guest shell found in /bin/bash, /usr/bin/bash, /bin/ash, /bin/sh, or /bin/aux/sh")
	ErrInvalidWorkDir = errors.New("requested working directory does not exist")
)

var defaultShellCandidates = []string{
	"/bin/bash",
	"/usr/bin/bash",
	"/bin/ash",
	"/bin/sh",
	"/bin/aux/sh",
}

func isKnownShellCandidate(path string) bool {
	for _, c := range defaultShellCandidates {
		if path == c {
			return true
		}
	}
	return false
}

func isShellExecutable(path string) bool {
	switch filepath.Base(path) {
	case "bash", "sh", "ash", "dash", "zsh", "fish", "ksh", "mksh":
		return true
	default:
		return false
	}
}

func isExecutableInRoot(rootDir, guestPath string) bool {
	if rootDir == "" {
		rootDir = "/"
	}
	hostPath := guestPath
	if rootDir != "/" {
		hostPath = filepath.Join(rootDir, strings.TrimPrefix(guestPath, "/"))
	}
	info, err := os.Stat(hostPath)
	return err == nil && !info.IsDir() && info.Mode()&0o111 != 0
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

// ResolveShellArgv returns argv when executable (falling back to defaultShellCandidates if a configured
// shell binary is absent), or probes /bin/bash, /usr/bin/bash, /bin/ash, /bin/sh, and /bin/aux/sh.
func ResolveShellArgv(argv []string, rootDir string) ([]string, error) {
	if rootDir == "" {
		rootDir = "/"
	}
	if len(argv) > 0 && asciiTrimSpace(argv[0]) != "" {
		if !isKnownShellCandidate(argv[0]) || isExecutableInRoot(rootDir, argv[0]) {
			cp := make([]string, len(argv))
			copy(cp, argv)
			return cp, nil
		}
		if len(argv) > 2 || (len(argv) == 2 && argv[1] != "-l") {
			cp := make([]string, len(argv))
			copy(cp, argv)
			return cp, nil
		}
	}
	for _, candidate := range defaultShellCandidates {
		if isExecutableInRoot(rootDir, candidate) {
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

// BuildGuestEnv merges base environment, overrides, TERM/COLORTERM/LANG defaults, and binDir PATH prefix.
func BuildGuestEnv(baseEnv []string, overrides map[string]string, binDir string) []string {
	out := make([]string, 0, len(baseEnv)+len(overrides)+4)
	for _, entry := range baseEnv {
		if k, v, ok := strings.Cut(entry, "="); ok && k != "" {
			out = setEnvKV(out, k, v)
		}
	}
	for k, v := range overrides {
		if k != "" && strings.IndexByte(k, 0) < 0 && strings.IndexByte(v, 0) < 0 {
			out = setEnvKV(out, k, v)
		}
	}
	if asciiTrimSpace(getEnvVal(out, "TERM")) == "" {
		out = setEnvKV(out, "TERM", "xterm-256color")
	}
	if asciiTrimSpace(getEnvVal(out, "COLORTERM")) == "" {
		out = setEnvKV(out, "COLORTERM", "truecolor")
	}
	if asciiTrimSpace(getEnvVal(out, "LANG")) == "" && asciiTrimSpace(getEnvVal(out, "LC_ALL")) == "" {
		out = setEnvKV(out, "LANG", "C.UTF-8")
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
