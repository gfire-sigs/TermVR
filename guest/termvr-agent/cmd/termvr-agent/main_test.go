package main

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
)

// TestParseAgentFlags verifies all required CLI flags and -- initDaemonArgv extraction.
func TestParseAgentFlags(t *testing.T) {
	t.Parallel()

	cfg, err := parseFlags([]string{
		"--mux-stdio",
		"--proxy-listen", "127.0.0.1:1080",
		"--transparent-proxy-port", "1081",
		"--make-root-rshared",
		"--",
		"/bin/k3s", "server", "--snapshotter=native",
	})
	if err != nil {
		t.Fatalf("parseFlags failed: %v", err)
	}
	if !cfg.muxStdio {
		t.Fatal("expected muxStdio=true")
	}
	if cfg.proxyListen != "127.0.0.1:1080" {
		t.Fatalf("proxyListen = %q, want 127.0.0.1:1080", cfg.proxyListen)
	}
	if cfg.transparentProxyPort != 1081 {
		t.Fatalf("transparentProxyPort = %d, want 1081", cfg.transparentProxyPort)
	}
	if !cfg.makeRootRShared {
		t.Fatal("expected makeRootRShared=true")
	}
	wantArgv := []string{"/bin/k3s", "server", "--snapshotter=native"}
	if !slices.Equal(cfg.initDaemonArgv, wantArgv) {
		t.Fatalf("initDaemonArgv = %v, want %v", cfg.initDaemonArgv, wantArgv)
	}
}

// TestParseAgentFlagsRejectsInvalidTransparentPort ensures out-of-range ports fail loudly.
func TestParseAgentFlagsRejectsInvalidTransparentPort(t *testing.T) {
	t.Parallel()

	for _, bad := range []string{"-1", "65536", "70000"} {
		if _, err := parseFlags([]string{"--mux-stdio", "--transparent-proxy-port", bad}); err == nil {
			t.Fatalf("expected parseFlags to reject --transparent-proxy-port=%s", bad)
		}
	}
}

// TestParseAgentFlagsRejectsRemovedPersistAndExecFlags verifies obsolete flags are rejected.
func TestParseAgentFlagsRejectsRemovedPersistAndExecFlags(t *testing.T) {
	t.Parallel()

	for _, bad := range [][]string{
		{"--mux-stdio", "--persist-root-dir", "/.termvr-rootfs"},
		{"--exec", "--", "/bin/sh"},
	} {
		if _, err := parseFlags(bad); err == nil {
			t.Fatalf("expected parseFlags to reject removed flag in %v", bad)
		}
	}
}

// TestDiscoverVolumeRootsFromProcMounts verifies "/" and mounted ext4/hostfs/9p volume roots
// are extracted while pseudo-filesystems (/proc, /sys, /dev, /tmp, /run) are excluded.
func TestDiscoverVolumeRootsFromProcMounts(t *testing.T) {
	t.Parallel()

	mountsFile := filepath.Join(t.TempDir(), "mounts")
	content := "" +
		"none / overlay rw 0 0\n" +
		"proc /proc proc rw 0 0\n" +
		"sysfs /sys sysfs rw 0 0\n" +
		"tmpfs /tmp tmpfs rw 0 0\n" +
		"tmpfs /run tmpfs rw 0 0\n" +
		"none /run/termvr-agent hostfs ro 0 0\n" +
		"none /workspace hostfs rw 0 0\n" +
		"/dev/loop0 /var/lib/docker ext4 rw 0 0\n" +
		"/dev/loop1 /var/lib/rancher/k3s ext4 rw 0 0\n" +
		"/dev/loop2 /var/lib/my\\040data ext4lite rw 0 0\n"
	if err := os.WriteFile(mountsFile, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}

	roots := discoverVolumeRoots(mountsFile)
	for _, expected := range []string{"/", "/workspace", "/var/lib/docker", "/var/lib/rancher/k3s", "/var/lib/my data"} {
		if !slices.Contains(roots, expected) {
			t.Fatalf("expected %q in roots: %v", expected, roots)
		}
	}
	for _, forbidden := range []string{"/proc", "/sys", "/tmp", "/run", "/run/termvr-agent"} {
		if slices.Contains(roots, forbidden) {
			t.Fatalf("forbidden root %q leaked into %v", forbidden, roots)
		}
	}
}

// TestSplitNoProxyList verifies comma-separated NO_PROXY parsing.
func TestSplitNoProxyList(t *testing.T) {
	t.Parallel()

	got := splitNoProxy("127.0.0.1, 10.0.0.0/8 , .cluster.local,")
	want := []string{"127.0.0.1", "10.0.0.0/8", ".cluster.local"}
	if !slices.Equal(got, want) {
		t.Fatalf("splitNoProxy = %v, want %v", got, want)
	}
}
