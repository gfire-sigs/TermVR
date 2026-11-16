package main

import (
	"context"
	"errors"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"syscall"

	"dev.gfire.termvr/guest/termvr-agent/internal/mux"
)

type agentConfig struct {
	muxStdio             bool
	proxyListen          string
	transparentProxyPort int
	makeRootRShared      bool
	initDaemonArgv       []string
}

var errHelpRequested = errors.New("help requested")

func logStderr(msg string) {
	_, _ = os.Stderr.WriteString(msg + "\n")
}

func main() {
	if len(os.Args) > 0 {
		base := filepath.Base(os.Args[0])
		if isCLIToolName(base) {
			if err := mux.RunCLITool(base, os.Args[1:], os.Stdin, os.Stdout, os.Stderr, ""); err != nil {
				logStderr(base + ": " + err.Error())
				os.Exit(1)
			}
			return
		}
	}
	if len(os.Args) > 1 && isCLISubcommand(os.Args[1]) {
		if err := mux.RunCLITool(os.Args[1], os.Args[2:], os.Stdin, os.Stdout, os.Stderr, ""); err != nil {
			logStderr("termvr-agent " + os.Args[1] + ": " + err.Error())
			os.Exit(1)
		}
		return
	}

	cfg, err := parseFlags(os.Args[1:])
	if err != nil {
		if errors.Is(err, errHelpRequested) {
			logStderr("usage: termvr-agent --mux-stdio [--proxy-listen 127.0.0.1:1080] [--transparent-proxy-port 1081] [--make-root-rshared] [-- <initDaemonArgv...>]")
			os.Exit(0)
		}
		logStderr("termvr-agent: " + err.Error())
		os.Exit(2)
	}

	if !cfg.muxStdio {
		logStderr("termvr-agent: --mux-stdio is required when running as guest PID 1")
		os.Exit(2)
	}

	if err := runAgent(cfg); err != nil {
		logStderr("termvr-agent: " + err.Error())
		os.Exit(1)
	}
}

func isCLIToolName(name string) bool {
	switch name {
	case "termvr-open", "termvr-copy", "termvr-paste", "termvr-share":
		return true
	default:
		return false
	}
}

func isCLISubcommand(name string) bool {
	switch name {
	case "open", "copy", "paste", "share":
		return true
	default:
		return false
	}
}

func parseBoolFlag(s string) (bool, error) {
	switch s {
	case "1", "t", "T", "true", "TRUE", "True":
		return true, nil
	case "0", "f", "F", "false", "FALSE", "False":
		return false, nil
	default:
		return false, errors.New("invalid bool: " + s)
	}
}

func parseFlags(args []string) (agentConfig, error) {
	cfg := agentConfig{
		proxyListen: mux.DefaultProxyListenAddr,
	}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			cfg.initDaemonArgv = append([]string(nil), args[i+1:]...)
			break
		}
		if !strings.HasPrefix(arg, "-") || arg == "-" {
			cfg.initDaemonArgv = append([]string(nil), args[i:]...)
			break
		}
		trimmed := strings.TrimPrefix(strings.TrimPrefix(arg, "-"), "-")
		name, val, hasEq := strings.Cut(trimmed, "=")
		switch name {
		case "h", "help":
			return agentConfig{}, errHelpRequested
		case "mux-stdio", "make-root-rshared":
			b := true
			if hasEq {
				var err error
				if b, err = parseBoolFlag(val); err != nil {
					return agentConfig{}, err
				}
			}
			if name == "mux-stdio" {
				cfg.muxStdio = b
			} else {
				cfg.makeRootRShared = b
			}
		case "proxy-listen", "transparent-proxy-port":
			if !hasEq {
				if i+1 >= len(args) {
					return agentConfig{}, errors.New("flag -" + name + " requires an argument")
				}
				i++
				val = args[i]
			}
			if name == "proxy-listen" {
				cfg.proxyListen = val
			} else {
				port, err := strconv.Atoi(val)
				if err != nil {
					return agentConfig{}, errors.New("invalid --transparent-proxy-port value: " + val)
				}
				cfg.transparentProxyPort = port
			}
		default:
			return agentConfig{}, errors.New("unknown flag: " + arg)
		}
	}
	if cfg.transparentProxyPort < 0 || cfg.transparentProxyPort > 65535 {
		return agentConfig{}, errors.New("invalid --transparent-proxy-port: must be in 0..65535")
	}
	return cfg, nil
}

func runAgent(cfg agentConfig) error {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()

	if cfg.makeRootRShared {
		if err := mux.EnsureRootRShared(); err != nil {
			logStderr("termvr-agent: warning: make-root-rshared failed: " + err.Error())
		}
	}
	if err := mux.EnsureArm64CPUInfoPatched(); err != nil {
		logStderr("termvr-agent: warning: patch cpuinfo failed: " + err.Error())
	}

	reaper := mux.NewReaper()
	go reaper.Run(ctx)

	const binDir = "/run/termvr-agent/bin"
	if err := mux.SetupBinWrappers(binDir, ""); err != nil {
		logStderr("termvr-agent: warning: setup bin wrappers in " + binDir + ": " + err.Error())
	}

	volumeRoots := discoverVolumeRoots("/proc/mounts")
	sess := mux.NewSession(mux.SessionConfig{
		Reader:         os.Stdin,
		Writer:         os.Stdout,
		InitDaemonArgv: cfg.initDaemonArgv,
		RootDir:        "/",
		ProcRoot:       "/proc",
		BinDir:         binDir,
		VolumeRoots:    volumeRoots,
		Reaper:         reaper,
	})

	sockPath := os.Getenv("TERMVR_AGENT_SOCK")
	if sockPath == "" {
		sockPath = mux.DefaultRPCSocketPath
	}
	rpcSrv, err := mux.StartRPCSocketServer(ctx, sockPath, sess)
	if err != nil && sockPath == mux.DefaultRPCSocketPath {
		fallbackSock := "/tmp/termvr-agent-rpc.sock"
		var ferr error
		rpcSrv, ferr = mux.StartRPCSocketServer(ctx, fallbackSock, sess)
		if ferr != nil {
			logStderr("termvr-agent: warning: rpc socket unavailable: " + err.Error() + "; fallback: " + ferr.Error())
		}
	} else if err != nil {
		logStderr("termvr-agent: warning: rpc socket unavailable: " + err.Error())
	}
	if rpcSrv != nil {
		defer rpcSrv.Close()
	}

	noProxy := splitNoProxy(os.Getenv("NO_PROXY"))
	if len(noProxy) == 0 {
		noProxy = splitNoProxy(os.Getenv("no_proxy"))
	}

	if trimASCII(cfg.proxyListen) != "" {
		mixedSrv, merr := mux.StartMixedProxyServer(ctx, mux.ProxyConfig{
			ListenAddr:     cfg.proxyListen,
			Session:        sess,
			NoProxyEntries: noProxy,
			SocketMark:     mux.DefaultBypassMark,
		})
		if merr != nil {
			logStderr("termvr-agent: warning: mixed proxy listen failed: " + merr.Error())
		} else {
			defer mixedSrv.Close()
		}
	}

	if cfg.transparentProxyPort > 0 {
		tAddr := "127.0.0.1:" + strconv.Itoa(cfg.transparentProxyPort)
		tSrv, terr := mux.StartTransparentProxyServer(ctx, mux.ProxyConfig{
			ListenAddr:     tAddr,
			Session:        sess,
			NoProxyEntries: noProxy,
			SocketMark:     mux.DefaultBypassMark,
		})
		if terr != nil {
			logStderr("termvr-agent: warning: transparent proxy listen failed: " + terr.Error())
		} else {
			defer tSrv.Close()
			if rerr := mux.InstallTransparentRedirect(uint16(cfg.transparentProxyPort), noProxy, mux.DefaultBypassMark); rerr != nil {
				logStderr("termvr-agent: warning: iptables redirect setup failed: " + rerr.Error())
			}
		}
	}

	err = sess.Run(ctx)
	cancel()
	if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, io.EOF) {
		return err
	}
	return nil
}

func discoverVolumeRoots(mountsPath string) []string {
	// Include "/" so VOLUME_FS_OP can browse native -rootfs-volume overlays when enabled.
	roots := []string{"/", "/workspace"}
	raw, err := os.ReadFile(mountsPath)
	if err != nil {
		return roots
	}
	for rest := string(raw); rest != ""; {
		var line string
		line, rest, _ = strings.Cut(rest, "\n")
		line = trimASCII(line)
		if line == "" || line[0] == '#' {
			continue
		}
		col := 0
		var mountPointField string
		for i := 0; i < len(line); {
			for i < len(line) && (line[i] == ' ' || line[i] == '\t') {
				i++
			}
			if i >= len(line) {
				break
			}
			start := i
			for i < len(line) && line[i] != ' ' && line[i] != '\t' {
				i++
			}
			if col == 1 {
				mountPointField = unescapeMountField(line[start:i])
			} else if col == 2 {
				fsType := line[start:i]
				mp := filepath.Clean(mountPointField)
				if mp != "/" &&
					mp != "/proc" && !strings.HasPrefix(mp, "/proc/") &&
					mp != "/sys" && !strings.HasPrefix(mp, "/sys/") &&
					mp != "/dev" && !strings.HasPrefix(mp, "/dev/") &&
					mp != "/tmp" && !strings.HasPrefix(mp, "/tmp/") &&
					mp != "/run" && !strings.HasPrefix(mp, "/run/") {
					switch fsType {
					case "ext4", "ext4lite", "hostfs", "9p", "virtiofs", "fuse":
						if !slices.Contains(roots, mp) {
							roots = append(roots, mp)
						}
					}
				}
				break
			}
			col++
		}
	}
	return roots
}

func unescapeMountField(s string) string {
	if strings.IndexByte(s, '\\') < 0 {
		return s
	}
	out := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+3 < len(s) {
			var b byte
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

func trimASCII(s string) string {
	return strings.Trim(s, " \t\r\n")
}

func splitNoProxy(raw string) []string {
	if trimASCII(raw) == "" {
		return nil
	}
	var out []string
	for rest := raw; rest != ""; {
		var p string
		p, rest, _ = strings.Cut(rest, ",")
		p = trimASCII(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
