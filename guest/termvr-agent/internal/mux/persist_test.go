package mux_test

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"testing"

	"dev.gfire.termvr/guest/termvr-agent/internal/mux"
)

func TestParseProcMountsAndSelectBindMounts(t *testing.T) {
	t.Parallel()

	raw := "" +
		"overlay / overlay rw 0 0\n" +
		"proc /proc proc rw 0 0\n" +
		"sysfs /sys sysfs rw 0 0\n" +
		"cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n" +
		"devtmpfs /dev devtmpfs rw 0 0\n" +
		"devpts /dev/pts devpts rw 0 0\n" +
		"tmpfs /tmp tmpfs rw 0 0\n" +
		"tmpfs /run tmpfs rw 0 0\n" +
		"none /run/termvr-agent hostfs ro 0 0\n" +
		"none /workspace hostfs rw 0 0\n" +
		"/dev/loop0 /.termvr-rootfs ext4 rw 0 0\n" +
		"/dev/loop1 /var/lib/my\\040data ext4 rw 0 0\n"

	entries := mux.ParseProcMounts(raw)
	binds := mux.SelectBindMounts(entries, "/.termvr-rootfs")

	want := []string{"/dev", "/run", "/sys", "/tmp", "/proc", "/workspace", "/var/lib/my data"}
	for _, w := range want {
		if !slices.Contains(binds, w) {
			t.Fatalf("expected bind mount %q in %v", w, binds)
		}
	}
	for _, coveredOrExcluded := range []string{"/", "/.termvr-rootfs", "/sys/fs/cgroup", "/dev/pts", "/run/termvr-agent"} {
		if slices.Contains(binds, coveredOrExcluded) {
			t.Fatalf("did not expect %q in %v", coveredOrExcluded, binds)
		}
	}
}

func TestEnsurePersistentRootPopulated_FirstBootRecoveryAndRefresh(t *testing.T) {
	t.Parallel()

	tmp := t.TempDir()
	srcRoot := filepath.Join(tmp, "src-root")
	persistDir := filepath.Join(tmp, "persist")

	for _, rel := range []string{
		"bin",
		"etc/ssl/certs",
		"usr/local/share/ca-certificates",
		"readonly-dir",
		"proc/1",
		"sys/class",
		"dev/pts",
		"run/termvr-agent",
		"tmp",
		"workspace",
		"data",
	} {
		if err := os.MkdirAll(filepath.Join(srcRoot, rel), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	// Regular file + hardlink + symlink + readonly dir child + FIFO + skipped mount contents.
	helloPath := filepath.Join(srcRoot, "bin", "hello")
	if err := os.WriteFile(helloPath, []byte("#!/bin/sh\necho ok\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	hardLinkPath := filepath.Join(srcRoot, "bin", "hello-link")
	if err := os.Link(helloPath, hardLinkPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("hello", filepath.Join(srcRoot, "bin", "sh")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "readonly-dir", "inner.txt"), []byte("keep"), 0o444); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(srcRoot, "readonly-dir"), 0o555); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = os.Chmod(filepath.Join(srcRoot, "readonly-dir"), 0o755)
		_ = os.Chmod(filepath.Join(persistDir, "root", "readonly-dir"), 0o755)
	}()

	fifoPath := filepath.Join(srcRoot, "bin", "pipe")
	if err := syscall.Mkfifo(fifoPath, 0o600); err != nil {
		t.Fatal(err)
	}

	// Contents inside skipped virtual/external mounts must NOT be copied.
	if err := os.WriteFile(filepath.Join(srcRoot, "workspace", "host-secret.txt"), []byte("host"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "data", "ext-vol.txt"), []byte("vol"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "tmp", "volatile.txt"), []byte("tmp"), 0o644); err != nil {
		t.Fatal(err)
	}

	// Boot files + host CA certs.
	if err := os.WriteFile(filepath.Join(srcRoot, "etc", "resolv.conf"), []byte("nameserver 10.0.2.3\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "etc", "hosts"), []byte("127.0.0.1 localhost\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "etc", "hostname"), []byte("termvr-1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	hostCert := "-----BEGIN CERTIFICATE-----\nHOSTCERT1\n-----END CERTIFICATE-----\n"
	if err := os.WriteFile(filepath.Join(srcRoot, "usr/local/share/ca-certificates/gvx-host-1.crt"), []byte(hostCert), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "etc/ssl/certs/ca-certificates.crt"), []byte(hostCert), 0o644); err != nil {
		t.Fatal(err)
	}

	mountPoints := []string{"/proc", "/sys", "/dev", "/run", "/tmp", "/workspace", "/data"}

	// Pre-populate a stale partial root without .termvr-rootfs-ready to test recovery.
	staleFile := filepath.Join(persistDir, "root", "bin", "hello")
	if err := os.MkdirAll(filepath.Dir(staleFile), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(staleFile, []byte("incomplete"), 0o644); err != nil {
		t.Fatal(err)
	}

	populated, err := mux.EnsurePersistentRootPopulated(srcRoot, persistDir, mountPoints)
	if err != nil {
		t.Fatalf("EnsurePersistentRootPopulated first boot: %v", err)
	}
	if !populated {
		t.Fatal("expected populatedNow=true on first boot")
	}

	dstRoot := filepath.Join(persistDir, "root")
	readyMarker := filepath.Join(persistDir, mux.PersistReadyFileName)
	if _, err := os.Stat(readyMarker); err != nil {
		t.Fatalf("missing ready marker %s: %v", readyMarker, err)
	}

	// Verify hardlink preservation.
	i1, err := os.Lstat(filepath.Join(dstRoot, "bin", "hello"))
	if err != nil {
		t.Fatal(err)
	}
	i2, err := os.Lstat(filepath.Join(dstRoot, "bin", "hello-link"))
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(i1, i2) {
		t.Fatal("expected hardlink to preserve shared inode in persistent root")
	}

	// Verify symlink preservation.
	if target, err := os.Readlink(filepath.Join(dstRoot, "bin", "sh")); err != nil || target != "hello" {
		t.Fatalf("symlink mismatch: target=%q err=%v", target, err)
	}

	// Verify FIFO and readonly directory child.
	if fi, err := os.Lstat(filepath.Join(dstRoot, "bin", "pipe")); err != nil || fi.Mode()&os.ModeNamedPipe == 0 {
		t.Fatalf("expected FIFO node in persistent root, err=%v", err)
	}
	if inner, err := os.ReadFile(filepath.Join(dstRoot, "readonly-dir", "inner.txt")); err != nil || string(inner) != "keep" {
		t.Fatalf("readonly dir copy failed: %q err=%v", string(inner), err)
	}

	// Verify skipped mount point directories exist as empty dirs without source files.
	for _, rel := range []string{"workspace/host-secret.txt", "data/ext-vol.txt", "tmp/volatile.txt"} {
		if _, err := os.Lstat(filepath.Join(dstRoot, rel)); !os.IsNotExist(err) {
			t.Fatalf("expected skipped mount content %s not copied, err=%v", rel, err)
		}
	}
	if _, err := os.Stat(filepath.Join(dstRoot, "data")); err != nil {
		t.Fatalf("expected /data mount point dir created: %v", err)
	}

	// Simulate user package installation + custom CA + edited /etc/resolv.conf in outer root on second boot.
	userPkg := filepath.Join(dstRoot, "usr", "bin", "fastfetch")
	if err := os.MkdirAll(filepath.Dir(userPkg), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(userPkg, []byte("installed-pkg"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "etc", "resolv.conf"), []byte("nameserver 10.0.2.99\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	customCert := "-----BEGIN CERTIFICATE-----\nCUSTOMUSERCERT\n-----END CERTIFICATE-----\n"
	bundlePath := filepath.Join(dstRoot, "etc/ssl/certs/ca-certificates.crt")
	if err := os.WriteFile(bundlePath, []byte(hostCert+customCert), 0o644); err != nil {
		t.Fatal(err)
	}
	hostCert2 := "-----BEGIN CERTIFICATE-----\nHOSTCERT2\n-----END CERTIFICATE-----\n"
	if err := os.WriteFile(filepath.Join(srcRoot, "usr/local/share/ca-certificates/gvx-host-2.crt"), []byte(hostCert2), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srcRoot, "etc/ssl/certs/ca-certificates.crt"), []byte(hostCert+hostCert2), 0o644); err != nil {
		t.Fatal(err)
	}
	// Remove bin/hello from srcRoot to prove second boot does not overwrite or re-sync persistent root.
	_ = os.Remove(helloPath)

	populated2, err := mux.EnsurePersistentRootPopulated(srcRoot, persistDir, mountPoints)
	if err != nil {
		t.Fatalf("EnsurePersistentRootPopulated second boot: %v", err)
	}
	if populated2 {
		t.Fatal("expected populatedNow=false when ready marker exists")
	}

	// User-installed package and removed-from-src file persist.
	if got, err := os.ReadFile(userPkg); err != nil || string(got) != "installed-pkg" {
		t.Fatalf("persisted user file lost: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dstRoot, "bin", "hello-link")); err != nil {
		t.Fatalf("expected existing rootfs file kept on second boot: %v", err)
	}
	// /etc/resolv.conf refreshed and custom CA preserved alongside new host CA.
	if resolv, err := os.ReadFile(filepath.Join(dstRoot, "etc", "resolv.conf")); err != nil || string(resolv) != "nameserver 10.0.2.99\n" {
		t.Fatalf("expected refreshed resolv.conf, got %q err=%v", string(resolv), err)
	}
	bundleBytes, err := os.ReadFile(bundlePath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(bundleBytes), "CUSTOMUSERCERT") || !strings.Contains(string(bundleBytes), "HOSTCERT2") {
		t.Fatalf("expected both custom CA and updated host CA in bundle, got:\n%s", string(bundleBytes))
	}
}

func TestVolumeRPCHandlerSupportsSlashRootForPersistentRootfs(t *testing.T) {
	t.Parallel()

	// VolumeRPCHandler confines "/" when explicitly listed in roots via isWithinRoot("/", ...).
	h := mux.NewVolumeRPCHandler([]string{"/"})
	var gotStatus uint8
	var gotBody bytes.Buffer
	tmpDir := t.TempDir()
	reqJSON := `{"op":"stat","root":"/","path":"` + strings.TrimPrefix(tmpDir, "/") + `"}`
	h.HandleHostRPC(
		context.Background(),
		1,
		mux.RPCOpVolumeFSOp,
		strings.NewReader(reqJSON),
		func(status uint8, chunk []byte, fin bool) error {
			gotStatus = status
			gotBody.Write(chunk)
			return nil
		},
	)
	if gotStatus != mux.RPCStatusOK {
		t.Fatalf("stat under root=/ returned status %d: %s", gotStatus, gotBody.String())
	}
}
