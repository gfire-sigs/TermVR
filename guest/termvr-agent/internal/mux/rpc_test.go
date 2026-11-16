package mux_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"dev.gfire.termvr/guest/termvr-agent/internal/mux"
)

// TestVolumeFSConfinementAndChunking attacks VOLUME_FS_OP (op 5) path traversal,
// symlink escape, root deletion, and multi-chunk (> 32 KiB) read/write streaming.
func TestVolumeFSConfinementAndChunking(t *testing.T) {
	t.Parallel()

	volRoot := t.TempDir()
	outsideDir := t.TempDir()
	secretFile := filepath.Join(outsideDir, "secret.txt")
	if err := os.WriteFile(secretFile, []byte("host-secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(volRoot, "hello.txt"), []byte("hello-volume"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(volRoot, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(secretFile, filepath.Join(volRoot, "escape-link")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outsideDir, filepath.Join(volRoot, "escape-dir")); err != nil {
		t.Fatal(err)
	}

	hostToGuestR, hostToGuestW := io.Pipe()
	guestToHostR, guestToHostW := io.Pipe()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sess := mux.NewSession(mux.SessionConfig{
		Reader:      hostToGuestR,
		Writer:      guestToHostW,
		VolumeRoots: []string{volRoot},
	})
	go func() { _ = sess.Run(ctx) }()

	// Drain initial MUX_HELLO from guest.
	helloFrame, err := mux.ReadFrame(guestToHostR)
	if err != nil || helloFrame.Type != mux.FrameMuxHello {
		t.Fatalf("expected MUX_HELLO, got %+v, err=%v", helloFrame, err)
	}

	callVolumeRPC := func(reqID uint32, header mux.VolumeFSRequest, body []byte) (uint8, []byte) {
		t.Helper()
		hdrJSON, err := json.Marshal(header)
		if err != nil {
			t.Fatal(err)
		}
		full := append(hdrJSON, '\n')
		full = append(full, body...)

		for off := 0; off < len(full) || off == 0; {
			end := off + mux.MaxRPCChunkBytes
			if end > len(full) {
				end = len(full)
			}
			var flags uint8
			if end == len(full) {
				flags = mux.RPCFlagFIN
			}
			raw, err := mux.EncodeRPCFrame(mux.RPCChunk{
				ReqID: reqID,
				Code:  mux.RPCOpVolumeFSOp,
				Flags: flags,
				Data:  full[off:end],
			})
			if err != nil {
				t.Fatal(err)
			}
			if err := mux.WriteFrame(hostToGuestW, mux.Frame{
				Type:     mux.FrameHostRPCReq,
				StreamID: 0,
				Payload:  raw,
			}); err != nil {
				t.Fatal(err)
			}
			off = end
			if off == len(full) {
				break
			}
		}

		var out bytes.Buffer
		var finalStatus uint8
		for {
			f, err := mux.ReadFrame(guestToHostR)
			if err != nil {
				t.Fatalf("ReadFrame rpc resp: %v", err)
			}
			if f.Type != mux.FrameHostRPCResp {
				continue
			}
			ch, err := mux.DecodeRPCFrame(f.Payload)
			if err != nil {
				t.Fatal(err)
			}
			if ch.ReqID != reqID {
				continue
			}
			finalStatus = ch.Code
			out.Write(ch.Data)
			if (ch.Flags & mux.RPCFlagFIN) != 0 {
				return finalStatus, out.Bytes()
			}
		}
	}

	// Traversal and symlink escape attacks must all be rejected with RPCStatusDenied.
	attacks := []struct {
		name string
		req  mux.VolumeFSRequest
	}{
		{"dotdot_read", mux.VolumeFSRequest{Op: "read", Root: volRoot, Path: "../secret.txt"}},
		{"deep_dotdot_stat", mux.VolumeFSRequest{Op: "stat", Root: volRoot, Path: "sub/../../secret.txt"}},
		{"unlisted_root", mux.VolumeFSRequest{Op: "readdir", Root: outsideDir, Path: "."}},
		{"symlink_file_read", mux.VolumeFSRequest{Op: "read", Root: volRoot, Path: "escape-link"}},
		{"symlink_dir_write", mux.VolumeFSRequest{Op: "write", Root: volRoot, Path: "escape-dir/pwned.txt", Truncate: true}},
		{"symlink_dir_mkdir", mux.VolumeFSRequest{Op: "mkdir", Root: volRoot, Path: "escape-dir/newdir"}},
		{"nul_byte_path", mux.VolumeFSRequest{Op: "stat", Root: volRoot, Path: "hello.txt\x00evil"}},
		{"unlink_root_forbidden", mux.VolumeFSRequest{Op: "unlink", Root: volRoot, Path: "."}},
	}
	for i, tc := range attacks {
		st, msg := callVolumeRPC(uint32(10+i), tc.req, []byte("attack"))
		if st != mux.RPCStatusDenied && st != mux.RPCStatusBadRequest {
			t.Fatalf("%s: expected rejection status (Denied/BadRequest), got %d (%s)", tc.name, st, string(msg))
		}
		if _, err := os.Stat(filepath.Join(outsideDir, "pwned.txt")); err == nil {
			t.Fatalf("%s escaped volume confinement and wrote outside file", tc.name)
		}
	}

	// Chunked write > 64 KiB (spans multiple 32 KiB HOST_RPC_REQ frames) + chunked read.
	largePayload := bytes.Repeat([]byte("TermVR-Volume-Chunk-0123456789ABCDEF"), 3000) // ~108 KiB
	st, wResp := callVolumeRPC(100, mux.VolumeFSRequest{
		Op:       "write",
		Root:     volRoot,
		Path:     "nested/dir/large.bin",
		Truncate: true,
	}, largePayload)
	if st != mux.RPCStatusOK {
		t.Fatalf("large write failed: status=%d msg=%s", st, string(wResp))
	}

	st, rResp := callVolumeRPC(101, mux.VolumeFSRequest{
		Op:   "read",
		Root: volRoot,
		Path: "nested/dir/large.bin",
	}, nil)
	if st != mux.RPCStatusOK || !bytes.Equal(rResp, largePayload) {
		t.Fatalf("large read mismatch: status=%d got=%d bytes want=%d", st, len(rResp), len(largePayload))
	}

	// Readdir and stat and unlink inside volume root.
	st, dirRaw := callVolumeRPC(102, mux.VolumeFSRequest{Op: "readdir", Root: volRoot, Path: "nested/dir"}, nil)
	if st != mux.RPCStatusOK {
		t.Fatalf("readdir failed: status=%d msg=%s", st, string(dirRaw))
	}
	var dirList mux.VolumeReadDirResult
	if err := json.Unmarshal(dirRaw, &dirList); err != nil || len(dirList.Entries) != 1 || dirList.Entries[0].Name != "large.bin" {
		t.Fatalf("readdir unexpected result: %s, err=%v", string(dirRaw), err)
	}

	st, statRaw := callVolumeRPC(103, mux.VolumeFSRequest{Op: "stat", Root: volRoot, Path: "nested/dir/large.bin"}, nil)
	if st != mux.RPCStatusOK {
		t.Fatalf("stat failed: status=%d msg=%s", st, string(statRaw))
	}
	var statRes mux.VolumeStatResult
	if err := json.Unmarshal(statRaw, &statRes); err != nil || !statRes.Exists || statRes.Size != int64(len(largePayload)) {
		t.Fatalf("stat unexpected result: %s, err=%v", string(statRaw), err)
	}

	st, _ = callVolumeRPC(104, mux.VolumeFSRequest{Op: "unlink", Root: volRoot, Path: "nested/dir/large.bin"}, nil)
	if st != mux.RPCStatusOK {
		t.Fatalf("unlink failed: status=%d", st)
	}
	if _, err := os.Stat(filepath.Join(volRoot, "nested/dir/large.bin")); !os.IsNotExist(err) {
		t.Fatalf("expected unlinked file to be removed, stat err=%v", err)
	}

	// Unlinking the escape symlink itself deletes only the symlink inside volRoot, never outsideDir/secret.txt.
	st, _ = callVolumeRPC(105, mux.VolumeFSRequest{Op: "unlink", Root: volRoot, Path: "escape-link"}, nil)
	if st != mux.RPCStatusOK {
		t.Fatalf("unlink symlink failed: status=%d", st)
	}
	if _, err := os.Stat(secretFile); err != nil {
		t.Fatalf("unlinking symlink touched outside target: %v", err)
	}

	cancel()
	_ = hostToGuestW.Close()
}

// TestGuestRPCSocketAndCLIWrappers tests Unix socket RPC dispatch and the four
// CLI helpers (termvr-open, termvr-copy, termvr-paste, termvr-share) end-to-end
// through Session Stream-0 HOST_RPC_REQ/RESP.
func TestGuestRPCSocketAndCLIWrappers(t *testing.T) {
	t.Parallel()

	hostToGuestR, hostToGuestW := io.Pipe()
	guestToHostR, guestToHostW := io.Pipe()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sess := mux.NewSession(mux.SessionConfig{
		Reader: hostToGuestR,
		Writer: guestToHostW,
	})
	go func() { _ = sess.Run(ctx) }()

	// Consume MUX_HELLO and run a fake host responder for ops 1..4.
	if _, err := mux.ReadFrame(guestToHostR); err != nil {
		t.Fatal(err)
	}

	type recordedRPC struct {
		op   uint8
		body []byte
	}
	gotRPCs := make(chan recordedRPC, 8)
	go func() {
		partials := make(map[uint32]*bytes.Buffer)
		ops := make(map[uint32]uint8)
		for {
			f, err := mux.ReadFrame(guestToHostR)
			if err != nil {
				return
			}
			if f.Type != mux.FrameHostRPCReq {
				continue
			}
			ch, err := mux.DecodeRPCFrame(f.Payload)
			if err != nil {
				return
			}
			buf := partials[ch.ReqID]
			if buf == nil {
				buf = &bytes.Buffer{}
				partials[ch.ReqID] = buf
				ops[ch.ReqID] = ch.Code
			}
			buf.Write(ch.Data)
			if (ch.Flags & mux.RPCFlagFIN) == 0 {
				continue
			}
			op := ops[ch.ReqID]
			body := append([]byte(nil), buf.Bytes()...)
			delete(partials, ch.ReqID)
			delete(ops, ch.ReqID)
			gotRPCs <- recordedRPC{op: op, body: body}

			var respData []byte
			if op == mux.RPCOpClipboardRead {
				respData = []byte("pasted-from-android")
			}
			raw, _ := mux.EncodeRPCFrame(mux.RPCChunk{
				ReqID: ch.ReqID,
				Code:  mux.RPCStatusOK,
				Flags: mux.RPCFlagFIN,
				Data:  respData,
			})
			_ = mux.WriteFrame(hostToGuestW, mux.Frame{
				Type:     mux.FrameHostRPCResp,
				StreamID: 0,
				Payload:  raw,
			})
		}
	}()

	sockDir, err := os.MkdirTemp("", "tvr-rpc-*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(sockDir) })
	sockPath := filepath.Join(sockDir, "rpc.sock")
	srv, err := mux.StartRPCSocketServer(ctx, sockPath, sess)
	if err != nil {
		t.Fatalf("StartRPCSocketServer: %v", err)
	}
	defer srv.Close()

	// Verify 0755 wrapper setup (symlinks to a dummy executable).
	binDir := filepath.Join(sockDir, "bin")
	dummyExe := filepath.Join(sockDir, "termvr-agent")
	if err := os.WriteFile(dummyExe, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := mux.SetupBinWrappers(binDir, dummyExe); err != nil {
		t.Fatalf("SetupBinWrappers: %v", err)
	}
	for _, name := range []string{"termvr-open", "termvr-copy", "termvr-paste", "termvr-share"} {
		info, err := os.Lstat(filepath.Join(binDir, name))
		if err != nil {
			t.Fatalf("missing wrapper %s: %v", name, err)
		}
		if info.Mode()&os.ModeSymlink == 0 && info.Mode()&0o111 == 0 {
			t.Fatalf("wrapper %s not executable or symlink: %v", name, info.Mode())
		}
	}

	// 1. termvr-open rejects non-http/https schemes locally without hitting host.
	var outBuf, errBuf bytes.Buffer
	if err := mux.RunCLITool("termvr-open", []string{"file:///etc/passwd"}, strings.NewReader(""), &outBuf, &errBuf, sockPath); !errors.Is(err, mux.ErrInvalidURLScheme) {
		t.Fatalf("termvr-open accepted file:// scheme: %v", err)
	}
	if err := mux.RunCLITool("termvr-open", []string{"https://termvr.dev/docs"}, strings.NewReader(""), &outBuf, &errBuf, sockPath); err != nil {
		t.Fatalf("termvr-open valid https failed: %v", err)
	}
	rec := <-gotRPCs
	if rec.op != mux.RPCOpOpenURL || string(rec.body) != "https://termvr.dev/docs" {
		t.Fatalf("OPEN_URL RPC mismatch: op=%d body=%q", rec.op, string(rec.body))
	}

	// 2. termvr-copy rejects > 1 MiB and forwards <= 1 MiB.
	oversizedClip := bytes.Repeat([]byte("X"), mux.MaxClipboardBytes+1)
	if err := mux.RunCLITool("termvr-copy", nil, bytes.NewReader(oversizedClip), &outBuf, &errBuf, sockPath); !errors.Is(err, mux.ErrClipboardTooLarge) {
		t.Fatalf("termvr-copy accepted > 1 MiB: %v", err)
	}
	if err := mux.RunCLITool("termvr-copy", nil, strings.NewReader("copied-text"), &outBuf, &errBuf, sockPath); err != nil {
		t.Fatalf("termvr-copy failed: %v", err)
	}
	rec = <-gotRPCs
	if rec.op != mux.RPCOpClipboardWrite || string(rec.body) != "copied-text" {
		t.Fatalf("CLIPBOARD_WRITE RPC mismatch: op=%d body=%q", rec.op, string(rec.body))
	}

	// 3. termvr-paste prints host clipboard output to stdout.
	outBuf.Reset()
	if err := mux.RunCLITool("termvr-paste", nil, strings.NewReader(""), &outBuf, &errBuf, sockPath); err != nil {
		t.Fatalf("termvr-paste failed: %v", err)
	}
	rec = <-gotRPCs
	if rec.op != mux.RPCOpClipboardRead || outBuf.String() != "pasted-from-android" {
		t.Fatalf("CLIPBOARD_READ mismatch: op=%d stdout=%q", rec.op, outBuf.String())
	}

	// 4. termvr-share streams > 32 KiB file with metadata header.
	sharePath := filepath.Join(sockDir, "bundle.tar")
	shareData := bytes.Repeat([]byte("SHARE-CHUNK-"), 4000) // 48 KiB
	if err := os.WriteFile(sharePath, shareData, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := mux.RunCLITool("termvr-share", []string{sharePath}, strings.NewReader(""), &outBuf, &errBuf, sockPath); err != nil {
		t.Fatalf("termvr-share failed: %v", err)
	}
	rec = <-gotRPCs
	if rec.op != mux.RPCOpShareFile {
		t.Fatalf("SHARE_FILE op = %d, want %d", rec.op, mux.RPCOpShareFile)
	}
	nl := bytes.IndexByte(rec.body, '\n')
	if nl <= 0 {
		t.Fatalf("SHARE_FILE missing metadata header newline")
	}
	var meta mux.ShareFileHeader
	if err := json.Unmarshal(rec.body[:nl], &meta); err != nil {
		t.Fatalf("SHARE_FILE invalid metadata json: %v", err)
	}
	if meta.Name != "bundle.tar" || meta.Size != int64(len(shareData)) || !bytes.Equal(rec.body[nl+1:], shareData) {
		t.Fatalf("SHARE_FILE payload mismatch: meta=%+v dataLen=%d", meta, len(rec.body[nl+1:]))
	}

	// Confirm socket exists and accepts clean shutdown.
	probeConn, err := net.DialTimeout("unix", sockPath, time.Second)
	if err != nil {
		t.Fatalf("rpc socket unreachable: %v", err)
	}
	_ = probeConn.Close()
	cancel()
	_ = hostToGuestW.Close()
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
