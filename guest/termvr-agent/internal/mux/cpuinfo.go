package mux

import (
	"bytes"
	"strings"
)

// PatchArm64ZeroCPUInfo rewrites gVisor arm64 "CPU architecture : 0" lines to "8"
// so containerd and Docker platform probes resolve linux/arm64 instead of linux/arm64/unknown.
func PatchArm64ZeroCPUInfo(raw []byte) ([]byte, bool) {
	lines := bytes.Split(raw, []byte("\n"))
	changed := false
	for i, line := range lines {
		colon := bytes.IndexByte(line, ':')
		if colon < 0 {
			continue
		}
		key := strings.TrimSpace(string(line[:colon]))
		val := strings.TrimSpace(string(line[colon+1:]))
		if key != "CPU architecture" || val != "0" {
			continue
		}
		zeroIdx := bytes.IndexByte(line[colon+1:], '0')
		if zeroIdx < 0 {
			continue
		}
		patched := append([]byte(nil), line...)
		patched[colon+1+zeroIdx] = '8'
		lines[i] = patched
		changed = true
	}
	if !changed {
		return raw, false
	}
	return bytes.Join(lines, []byte("\n")), true
}
