package mux

import (
	"bytes"
	"testing"
)

// TestPatchArm64ZeroCPUInfoRewritesGVisorZero verifies CPU architecture 0 becomes 8 while preserving tabs.
func TestPatchArm64ZeroCPUInfoRewritesGVisorZero(t *testing.T) {
	t.Parallel()

	raw := []byte("processor\t: 0\n" +
		"BogoMIPS\t: 0.00\n" +
		"CPU implementer\t: 0x0\n" +
		"CPU architecture\t: 0\n" +
		"CPU variant\t: 0x0\n" +
		"processor\t: 1\n" +
		"CPU architecture\t: 0\n")

	patched, changed := PatchArm64ZeroCPUInfo(raw)
	if !changed {
		t.Fatal("expected PatchArm64ZeroCPUInfo to report change")
	}
	want := []byte("processor\t: 0\n" +
		"BogoMIPS\t: 0.00\n" +
		"CPU implementer\t: 0x0\n" +
		"CPU architecture\t: 8\n" +
		"CPU variant\t: 0x0\n" +
		"processor\t: 1\n" +
		"CPU architecture\t: 8\n")
	if !bytes.Equal(patched, want) {
		t.Fatalf("patched =\n%s\nwant\n%s", patched, want)
	}
}

// TestPatchArm64ZeroCPUInfoNoOpsWhenValid verifies non-zero arm64 and x86 cpuinfo inputs stay untouched.
func TestPatchArm64ZeroCPUInfoNoOpsWhenValid(t *testing.T) {
	t.Parallel()

	for _, sample := range []string{
		"processor\t: 0\nCPU architecture\t: 8\n",
		"processor\t: 0\nvendor_id\t: GenuineIntel\n",
	} {
		out, changed := PatchArm64ZeroCPUInfo([]byte(sample))
		if changed {
			t.Fatalf("expected no change for %q, got %q", sample, out)
		}
		if string(out) != sample {
			t.Fatalf("output modified unexpectedly: %q", out)
		}
	}
}
