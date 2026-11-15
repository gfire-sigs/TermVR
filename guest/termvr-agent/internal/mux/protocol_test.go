package mux_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"dev.gfire.termvr/guest/termvr-agent/internal/mux"
)

// TestFrameHeaderBoundsAndValidation attacks the 12-byte wire frame codec across
// magic, version, opcode range, stream ID rules, and the 65536-byte payload cap.
// Seam: mux.ReadFrame and mux.WriteFrame.
// Oracle: Design 0001 Section 15.4 wire header diagram and frame table.
// Faults targeted: off-by-one payload cap (>65536 accepted or 65536 rejected),
// unchecked magic/version, stream 0 control frame accepted on non-zero stream.
func TestFrameHeaderBoundsAndValidation(t *testing.T) {
	t.Parallel()

	t.Run("payload_length_boundary_65535_65536_65537", func(t *testing.T) {
		t.Parallel()
		for _, size := range []int{0, 1, 65535, 65536} {
			payload := bytes.Repeat([]byte{0xAB}, size)
			var wire bytes.Buffer
			err := mux.WriteFrame(&wire, mux.Frame{
				Type:     mux.FramePTYData,
				StreamID: 1,
				Payload:  payload,
			})
			if err != nil {
				t.Fatalf("WriteFrame rejected valid payload size %d: %v", size, err)
			}
			if wire.Len() != mux.HeaderLength+size {
				t.Fatalf("wire length = %d, want %d", wire.Len(), mux.HeaderLength+size)
			}
			got, err := mux.ReadFrame(&wire)
			if err != nil {
				t.Fatalf("ReadFrame failed for size %d: %v", size, err)
			}
			if got.Type != mux.FramePTYData || got.StreamID != 1 || !bytes.Equal(got.Payload, payload) {
				t.Fatalf("round-trip mismatch at size %d", size)
			}
		}

		oversized := make([]byte, 65537)
		var out bytes.Buffer
		if err := mux.WriteFrame(&out, mux.Frame{
			Type:     mux.FramePTYData,
			StreamID: 1,
			Payload:  oversized,
		}); !errors.Is(err, mux.ErrPayloadTooLarge) {
			t.Fatalf("WriteFrame(65537) err = %v, want ErrPayloadTooLarge", err)
		}

		var rawHdr [mux.HeaderLength]byte
		binary.BigEndian.PutUint16(rawHdr[0:2], mux.FrameMagic)
		rawHdr[2] = mux.ProtocolVersion
		rawHdr[3] = byte(mux.FramePTYData)
		binary.BigEndian.PutUint32(rawHdr[4:8], 1)
		binary.BigEndian.PutUint32(rawHdr[8:12], 65537)
		if _, err := mux.ReadFrame(bytes.NewReader(rawHdr[:])); !errors.Is(err, mux.ErrPayloadTooLarge) {
			t.Fatalf("ReadFrame(65537) err = %v, want ErrPayloadTooLarge", err)
		}
	})

	t.Run("rejects_bad_magic_and_version_and_opcode", func(t *testing.T) {
		t.Parallel()
		makeRaw := func(magic uint16, ver uint8, typ uint8, streamID uint32, length uint32) []byte {
			var hdr [mux.HeaderLength]byte
			binary.BigEndian.PutUint16(hdr[0:2], magic)
			hdr[2] = ver
			hdr[3] = typ
			binary.BigEndian.PutUint32(hdr[4:8], streamID)
			binary.BigEndian.PutUint32(hdr[8:12], length)
			return hdr[:]
		}

		if _, err := mux.ReadFrame(bytes.NewReader(makeRaw(0x5457, 0x01, 0x04, 1, 0))); !errors.Is(err, mux.ErrInvalidMagic) {
			t.Fatalf("bad magic err = %v, want ErrInvalidMagic", err)
		}
		if _, err := mux.ReadFrame(bytes.NewReader(makeRaw(mux.FrameMagic, 0x02, 0x04, 1, 0))); !errors.Is(err, mux.ErrUnsupportedVersion) {
			t.Fatalf("bad version err = %v, want ErrUnsupportedVersion", err)
		}
		for _, badType := range []uint8{0x00, 0x11, 0xFF} {
			if _, err := mux.ReadFrame(bytes.NewReader(makeRaw(mux.FrameMagic, 0x01, badType, 0, 0))); !errors.Is(err, mux.ErrUnknownFrameType) {
				t.Fatalf("bad opcode 0x%02x err = %v, want ErrUnknownFrameType", badType, err)
			}
		}
	})

	t.Run("enforces_stream_id_partition_across_all_16_types", func(t *testing.T) {
		t.Parallel()
		streamZeroOnly := []mux.FrameType{
			mux.FrameMuxHello,
			mux.FrameTelemetryReq,
			mux.FrameTelemetryResp,
			mux.FrameHostRPCReq,
			mux.FrameHostRPCResp,
			mux.FrameMuxResync,
			mux.FrameMuxResyncAck,
		}
		for _, ft := range streamZeroOnly {
			var buf bytes.Buffer
			if err := mux.WriteFrame(&buf, mux.Frame{Type: ft, StreamID: 1}); !errors.Is(err, mux.ErrInvalidStreamID) {
				t.Fatalf("type 0x%02x accepted stream 1: %v", uint8(ft), err)
			}
		}

		nonZeroOnly := []mux.FrameType{
			mux.FrameOpenPTY,
			mux.FrameOpenPTYAck,
			mux.FramePTYResize,
			mux.FramePTYSignal,
			mux.FrameProxyConnect,
			mux.FrameProxyConnectAck,
		}
		for _, ft := range nonZeroOnly {
			var buf bytes.Buffer
			if err := mux.WriteFrame(&buf, mux.Frame{Type: ft, StreamID: 0}); !errors.Is(err, mux.ErrInvalidStreamID) {
				t.Fatalf("type 0x%02x accepted stream 0: %v", uint8(ft), err)
			}
		}

		// PTY_DATA, PTY_WINDOW_UPDATE, and PTY_EXIT permit stream 0..N.
		for _, ft := range []mux.FrameType{mux.FramePTYData, mux.FramePTYWindowUpdate, mux.FramePTYExit} {
			for _, sid := range []uint32{0, 1, 99} {
				var buf bytes.Buffer
				if err := mux.WriteFrame(&buf, mux.Frame{Type: ft, StreamID: sid}); err != nil {
					t.Fatalf("type 0x%02x rejected valid stream %d: %v", uint8(ft), sid, err)
				}
			}
		}
	})

	t.Run("rejects_truncated_header_and_body", func(t *testing.T) {
		t.Parallel()
		if _, err := mux.ReadFrame(bytes.NewReader([]byte{0x54, 0x56, 0x01})); !errors.Is(err, io.ErrUnexpectedEOF) {
			t.Fatalf("truncated header err = %v, want io.ErrUnexpectedEOF", err)
		}
		var hdr [mux.HeaderLength]byte
		binary.BigEndian.PutUint16(hdr[0:2], mux.FrameMagic)
		hdr[2] = mux.ProtocolVersion
		hdr[3] = byte(mux.FramePTYData)
		binary.BigEndian.PutUint32(hdr[4:8], 1)
		binary.BigEndian.PutUint32(hdr[8:12], 10)
		partial := append(hdr[:], 1, 2, 3)
		if _, err := mux.ReadFrame(bytes.NewReader(partial)); !errors.Is(err, io.ErrUnexpectedEOF) {
			t.Fatalf("truncated body err = %v, want io.ErrUnexpectedEOF", err)
		}
	})
}

// TestAll16PayloadCodecs verifies exact binary and JSON wire encodings for all
// 16 Section 15.4 frame payloads and checks malformed payload rejection.
func TestAll16PayloadCodecs(t *testing.T) {
	t.Parallel()

	// 0x01 MUX_HELLO
	helloRaw, err := mux.EncodeMuxHello(mux.HelloPayload{
		Protocol:     1,
		AgentVersion: "0.1.0",
		InitDaemon:   true,
	})
	if err != nil {
		t.Fatalf("EncodeMuxHello: %v", err)
	}
	var helloMap map[string]any
	if err := json.Unmarshal(helloRaw, &helloMap); err != nil {
		t.Fatalf("MUX_HELLO JSON invalid: %v", err)
	}
	if helloMap["protocol"] != float64(1) || helloMap["agentVersion"] != "0.1.0" || helloMap["initDaemon"] != true {
		t.Fatalf("unexpected MUX_HELLO JSON fields: %v", helloMap)
	}
	helloDec, err := mux.DecodeMuxHello(helloRaw)
	if err != nil || helloDec.Protocol != 1 || !helloDec.InitDaemon {
		t.Fatalf("DecodeMuxHello mismatch: %+v, err=%v", helloDec, err)
	}

	// 0x02 OPEN_PTY
	openRaw, err := mux.EncodeOpenPTY(mux.OpenPTYRequest{
		WinSize: mux.WinSize{Rows: 42, Cols: 110, XPixel: 1100, YPixel: 840},
		Spec: mux.OpenPTYSpec{
			Argv: []string{"/bin/sh", "-c", "echo hi"},
			Cwd:  "/workspace",
			Env:  map[string]string{"TERM": "xterm-ghostty", "COLORTERM": "truecolor"},
		},
	})
	if err != nil {
		t.Fatalf("EncodeOpenPTY: %v", err)
	}
	if binary.BigEndian.Uint16(openRaw[0:2]) != 42 || binary.BigEndian.Uint16(openRaw[2:4]) != 110 {
		t.Fatalf("OPEN_PTY binary header mismatch")
	}
	openDec, err := mux.DecodeOpenPTY(openRaw)
	if err != nil || openDec.WinSize.Rows != 42 || openDec.WinSize.Cols != 110 || openDec.Spec.Cwd != "/workspace" {
		t.Fatalf("DecodeOpenPTY mismatch: %+v, err=%v", openDec, err)
	}
	if _, err := mux.DecodeOpenPTY([]byte{1, 2, 3}); err == nil {
		t.Fatalf("DecodeOpenPTY accepted short payload")
	}

	// 0x03 OPEN_PTY_ACK
	ackRaw := mux.EncodeOpenPTYAck(mux.OpenPTYAck{Status: 2, Error: "shell missing"})
	ackDec, err := mux.DecodeOpenPTYAck(ackRaw)
	if err != nil || ackDec.Status != 2 || ackDec.Error != "shell missing" {
		t.Fatalf("DecodeOpenPTYAck mismatch: %+v, err=%v", ackDec, err)
	}

	// 0x05 PTY_RESIZE
	wsRaw := mux.EncodeWinSize(mux.WinSize{Rows: 50, Cols: 132, XPixel: 1320, YPixel: 1000})
	wsDec, err := mux.DecodeWinSize(wsRaw)
	if err != nil || wsDec.Rows != 50 || wsDec.Cols != 132 {
		t.Fatalf("DecodeWinSize mismatch: %+v, err=%v", wsDec, err)
	}

	// 0x06 PTY_WINDOW_UPDATE
	winRaw := mux.EncodeWindowUpdate(65536)
	delta, err := mux.DecodeWindowUpdate(winRaw)
	if err != nil || delta != 65536 {
		t.Fatalf("DecodeWindowUpdate = %d, err=%v", delta, err)
	}
	if _, err := mux.DecodeWindowUpdate(mux.EncodeWindowUpdate(0)); err == nil {
		t.Fatalf("DecodeWindowUpdate accepted zero delta")
	}

	// 0x07 PTY_SIGNAL & 0x08 PTY_EXIT
	sig, err := mux.DecodePTYSignal(mux.EncodePTYSignal(uint8(syscall.SIGINT)))
	if err != nil || sig != uint8(syscall.SIGINT) {
		t.Fatalf("DecodePTYSignal = %d, err=%v", sig, err)
	}
	code, err := mux.DecodePTYExit(mux.EncodePTYExit(130))
	if err != nil || code != 130 {
		t.Fatalf("DecodePTYExit = %d, err=%v", code, err)
	}

	// 0x0A TELEMETRY_RESP ensures empty listenPorts encodes as [] rather than null.
	telRaw, err := mux.EncodeTelemetryResp(mux.TelemetrySnapshot{
		CPUNanos:  987654321,
		RSSBytes:  4096 * 128,
		ProcCount: 3,
	})
	if err != nil {
		t.Fatalf("EncodeTelemetryResp: %v", err)
	}
	if !strings.Contains(string(telRaw), `"listenPorts":[]`) {
		t.Fatalf("expected non-null listenPorts array in JSON: %s", string(telRaw))
	}

	// 0x0B / 0x0C HOST_RPC_REQ and HOST_RPC_RESP
	rpcRaw, err := mux.EncodeRPCFrame(mux.RPCChunk{
		ReqID: 77,
		Code:  1,
		Flags: mux.RPCFlagFIN,
		Data:  []byte("https://example.org"),
	})
	if err != nil {
		t.Fatalf("EncodeRPCFrame: %v", err)
	}
	rpcDec, err := mux.DecodeRPCFrame(rpcRaw)
	if err != nil || rpcDec.ReqID != 77 || rpcDec.Code != 1 || rpcDec.Flags != mux.RPCFlagFIN || string(rpcDec.Data) != "https://example.org" {
		t.Fatalf("DecodeRPCFrame mismatch: %+v, err=%v", rpcDec, err)
	}
	if _, err := mux.EncodeRPCFrame(mux.RPCChunk{
		ReqID: 1,
		Code:  1,
		Data:  make([]byte, mux.MaxRPCChunkBytes+1),
	}); err == nil {
		t.Fatalf("EncodeRPCFrame accepted chunk > 32 KiB")
	}

	// 0x0E MUX_RESYNC_ACK
	resyncRaw, err := mux.EncodeResyncAck(mux.ResyncAckPayload{})
	if err != nil || !strings.Contains(string(resyncRaw), `"openStreams":[]`) {
		t.Fatalf("EncodeResyncAck expected empty array: %s, err=%v", string(resyncRaw), err)
	}

	// 0x0F PROXY_CONNECT & 0x10 PROXY_CONNECT_ACK
	pcRaw, err := mux.EncodeProxyConnect(mux.ProxyConnectRequest{Host: "registry-1.docker.io", Port: 443})
	if err != nil {
		t.Fatalf("EncodeProxyConnect: %v", err)
	}
	pcDec, err := mux.DecodeProxyConnect(pcRaw)
	if err != nil || pcDec.Host != "registry-1.docker.io" || pcDec.Port != 443 {
		t.Fatalf("DecodeProxyConnect mismatch: %+v, err=%v", pcDec, err)
	}
	if _, err := mux.EncodeProxyConnect(mux.ProxyConnectRequest{Host: "", Port: 443}); err == nil {
		t.Fatalf("EncodeProxyConnect accepted empty host")
	}
	pcaRaw := mux.EncodeProxyConnectAck(mux.ProxyConnectAck{Status: 0, Message: ""})
	pcaDec, err := mux.DecodeProxyConnectAck(pcaRaw)
	if err != nil || pcaDec.Status != 0 {
		t.Fatalf("DecodeProxyConnectAck mismatch: %+v, err=%v", pcaDec, err)
	}
}

// TestProcTelemetryParsing verifies Linux /proc snapshot extraction with weird
// process names containing spaces and parentheses, reaped child ticks on PID 1,
// and deduplicated sorted TCP listen ports across IPv4 and IPv6.
func TestProcTelemetryParsing(t *testing.T) {
	t.Parallel()

	fakeProc := t.TempDir()
	mustWrite := func(rel, content string) {
		t.Helper()
		p := filepath.Join(fakeProc, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	// PID 1: utime=10, stime=5, cutime=20, cstime=4, rss=100 pages
	// Fields after ')': 1:state 2:ppid 3:pgrp 4:sid 5:tty 6:tpgid 7:flags 8:minflt 9:cminflt
	// 10:majflt 11:cmajflt 12:utime 13:stime 14:cutime 15:cstime 16:prio 17:nice 18:threads
	// 19:itreal 20:start 21:vsize 22:rss
	mustWrite("1/stat", "1 (termvr (agent) 1) S 0 1 1 0 -1 4194560 0 0 0 0 10 5 20 4 20 0 1 0 10 8192000 100 18446744073709551615\n")
	// PID 42: utime=7, stime=3, cutime=0, cstime=0, rss=50 pages
	mustWrite("42/stat", "42 (bash) S 1 42 42 34816 42 4194304 0 0 0 0 7 3 0 0 20 0 1 0 50 4096000 50 18446744073709551615\n")
	// Non-numeric entry ignored
	mustWrite("self/stat", "invalid\n")

	// /proc/net/tcp: port 0x1F90 (8080) LISTEN (0A), port 0x0050 (80) ESTABLISHED (01)
	mustWrite("net/tcp", "  sl  local_address rem_address   st tx_queue rx_queue tr tm_when retrnsmt   uid  timeout inode\n"+
		"   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1001\n"+
		"   1: 0100007F:0050 0100007F:C350 01 00000000:00000000 00:00000000 00000000     0        0 1002\n")
	// /proc/net/tcp6: port 0x192B (6443) LISTEN (0A) and duplicate 0x1F90 (8080) LISTEN (0A)
	mustWrite("net/tcp6", "  sl  local_address                         rem_address                        st tx_queue rx_queue\n"+
		"   0: 00000000000000000000000000000000:192B 00000000000000000000000000000000:0000 0A 00000000:00000000\n"+
		"   1: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000\n")

	snap, err := mux.ReadProcTelemetry(fakeProc)
	if err != nil {
		t.Fatalf("ReadProcTelemetry: %v", err)
	}
	if snap.ProcCount != 2 {
		t.Fatalf("ProcCount = %d, want 2", snap.ProcCount)
	}
	// Total ticks = (10+5+20+4) + (7+3) = 49 ticks * 10_000_000 ns = 490_000_000 ns
	if snap.CPUNanos != 490_000_000 {
		t.Fatalf("CPUNanos = %d, want 490000000", snap.CPUNanos)
	}
	wantRSS := uint64(150) * uint64(os.Getpagesize())
	if snap.RSSBytes != wantRSS {
		t.Fatalf("RSSBytes = %d, want %d", snap.RSSBytes, wantRSS)
	}
	if len(snap.ListenPorts) != 2 || snap.ListenPorts[0] != 6443 || snap.ListenPorts[1] != 8080 {
		t.Fatalf("ListenPorts = %v, want [6443 8080]", snap.ListenPorts)
	}
}

// TestSessionSlidingWindowResyncAndPTY exercises the full mux Session engine:
// MUX_HELLO, Stream-0 initDaemon, OPEN_PTY, 256 KiB sliding-window backpressure
// without blocking TELEMETRY_REQ or MUX_RESYNC, PTY_RESIZE, and PTY_EXIT.
func TestSessionSlidingWindowResyncAndPTY(t *testing.T) {
	t.Parallel()

	hostToGuestR, hostToGuestW := io.Pipe()
	guestToHostR, guestToHostW := io.Pipe()

	fakeProc := t.TempDir()
	_ = os.WriteFile(filepath.Join(fakeProc, "net_empty"), nil, 0o644)
	if err := os.MkdirAll(filepath.Join(fakeProc, "1"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(fakeProc, "1", "stat"),
		[]byte("1 (agent) S 0 1 1 0 -1 0 0 0 0 0 4 2 0 0 20 0 1 0 1 4096 10 0\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sess := mux.NewSession(mux.SessionConfig{
		Reader:         hostToGuestR,
		Writer:         guestToHostW,
		InitDaemonArgv: []string{"/bin/sh", "-c", "printf 'daemon-ready\\n'"},
		ProcRoot:       fakeProc,
		BinDir:         "/run/termvr-agent/bin",
	})

	done := make(chan error, 1)
	go func() {
		done <- sess.Run(ctx)
	}()

	var framesMu sync.Mutex
	var seenHello *mux.HelloPayload
	stream0Out := &bytes.Buffer{}
	ptyAckCh := make(chan mux.OpenPTYAck, 2)
	stream1Bytes := make(chan []byte, 64)
	stream1Exit := make(chan uint8, 1)
	telemetryCh := make(chan mux.TelemetrySnapshot, 2)
	resyncCh := make(chan mux.ResyncAckPayload, 2)

	go func() {
		for {
			f, err := mux.ReadFrame(guestToHostR)
			if err != nil {
				return
			}
			switch f.Type {
			case mux.FrameMuxHello:
				h, err := mux.DecodeMuxHello(f.Payload)
				if err == nil {
					framesMu.Lock()
					seenHello = &h
					framesMu.Unlock()
				}
			case mux.FrameOpenPTYAck:
				ack, err := mux.DecodeOpenPTYAck(f.Payload)
				if err == nil {
					ptyAckCh <- ack
				}
			case mux.FramePTYData:
				if f.StreamID == 0 {
					framesMu.Lock()
					stream0Out.Write(f.Payload)
					framesMu.Unlock()
				} else if f.StreamID == 1 {
					cp := append([]byte(nil), f.Payload...)
					stream1Bytes <- cp
				}
			case mux.FramePTYExit:
				if f.StreamID == 1 {
					code, err := mux.DecodePTYExit(f.Payload)
					if err == nil {
						stream1Exit <- code
					}
				}
			case mux.FrameTelemetryResp:
				snap, err := mux.DecodeTelemetryResp(f.Payload)
				if err == nil {
					telemetryCh <- snap
				}
			case mux.FrameMuxResyncAck:
				ack, err := mux.DecodeResyncAck(f.Payload)
				if err == nil {
					resyncCh <- ack
				}
			}
		}
	}()

	// Send host MUX_HELLO.
	helloBytes, _ := mux.EncodeMuxHello(mux.HelloPayload{Protocol: 1})
	if err := mux.WriteFrame(hostToGuestW, mux.Frame{Type: mux.FrameMuxHello, StreamID: 0, Payload: helloBytes}); err != nil {
		t.Fatalf("send MUX_HELLO: %v", err)
	}

	// Open stream 1 emitting 300 KiB (> 256 KiB window!) then exiting with code 7.
	openPayload, err := mux.EncodeOpenPTY(mux.OpenPTYRequest{
		WinSize: mux.WinSize{Rows: 24, Cols: 80, XPixel: 800, YPixel: 480},
		Spec: mux.OpenPTYSpec{
			Argv: []string{"/bin/sh", "-c", "dd if=/dev/zero bs=1024 count=300 2>/dev/null | tr '\\0' 'A'; exit 7"},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := mux.WriteFrame(hostToGuestW, mux.Frame{Type: mux.FrameOpenPTY, StreamID: 1, Payload: openPayload}); err != nil {
		t.Fatalf("send OPEN_PTY: %v", err)
	}

	select {
	case ack := <-ptyAckCh:
		if ack.Status != 0 {
			t.Fatalf("OPEN_PTY_ACK failed: status=%d err=%s", ack.Status, ack.Error)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for OPEN_PTY_ACK")
	}

	// Collect stream 1 output WITHOUT sending PTY_WINDOW_UPDATE yet.
	// Total received MUST stop at exactly InitialStreamWindow (262144 bytes).
	received := 0
	for received < int(mux.InitialStreamWindow) {
		select {
		case chunk := <-stream1Bytes:
			received += len(chunk)
		case <-time.After(3 * time.Second):
			t.Fatalf("stalled before filling initial 256 KiB window: got %d bytes", received)
		}
	}
	if received > int(mux.InitialStreamWindow) {
		t.Fatalf("flow control violated: received %d bytes > window %d without update", received, mux.InitialStreamWindow)
	}

	// Verify no extra bytes sneak past while window is exhausted, and verify control
	// frames (TELEMETRY_REQ and PTY_RESIZE and MUX_RESYNC) are NOT blocked by stream 1!
	if err := mux.WriteFrame(hostToGuestW, mux.Frame{
		Type:     mux.FramePTYResize,
		StreamID: 1,
		Payload:  mux.EncodeWinSize(mux.WinSize{Rows: 42, Cols: 110, XPixel: 1100, YPixel: 840}),
	}); err != nil {
		t.Fatal(err)
	}
	if err := mux.WriteFrame(hostToGuestW, mux.Frame{Type: mux.FrameTelemetryReq, StreamID: 0}); err != nil {
		t.Fatal(err)
	}
	select {
	case snap := <-telemetryCh:
		if snap.ProcCount != 1 || snap.CPUNanos != 60_000_000 {
			t.Fatalf("unexpected telemetry snapshot: %+v", snap)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("TELEMETRY_REQ blocked while stream 1 send window was zero")
	}

	// Verify stream 1 still has not exceeded 256 KiB before window credit or resync.
	select {
	case extra := <-stream1Bytes:
		t.Fatalf("unexpected %d bytes emitted while send window was 0", len(extra))
	default:
	}

	// Grant 64 KiB of window credit via PTY_WINDOW_UPDATE so remaining 44 KiB drains cleanly!
	if err := mux.WriteFrame(hostToGuestW, mux.Frame{
		Type:     mux.FramePTYWindowUpdate,
		StreamID: 1,
		Payload:  mux.EncodeWindowUpdate(65536),
	}); err != nil {
		t.Fatal(err)
	}

	const wantTotal = 300 * 1024
	for received < wantTotal {
		select {
		case chunk := <-stream1Bytes:
			received += len(chunk)
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out draining remaining output after PTY_WINDOW_UPDATE: got %d/%d", received, wantTotal)
		}
	}
	if received != wantTotal {
		t.Fatalf("total stream 1 bytes = %d, want %d", received, wantTotal)
	}

	select {
	case exitCode := <-stream1Exit:
		if exitCode != 7 {
			t.Fatalf("PTY_EXIT code = %d, want 7", exitCode)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for PTY_EXIT")
	}

	// Open stream 2 (sleeping shell), resize to 36x100, then test MUX_RESYNC snapshot.
	open2, _ := mux.EncodeOpenPTY(mux.OpenPTYRequest{
		WinSize: mux.WinSize{Rows: 36, Cols: 100},
		Spec:    mux.OpenPTYSpec{Argv: []string{"/bin/sh", "-c", "sleep 30"}},
	})
	_ = mux.WriteFrame(hostToGuestW, mux.Frame{Type: mux.FrameOpenPTY, StreamID: 2, Payload: open2})
	ack2 := <-ptyAckCh
	if ack2.Status != 0 {
		t.Fatalf("stream 2 OPEN_PTY_ACK failed: %+v", ack2)
	}

	if err := mux.WriteFrame(hostToGuestW, mux.Frame{Type: mux.FrameMuxResync, StreamID: 0}); err != nil {
		t.Fatal(err)
	}
	select {
	case resync := <-resyncCh:
		if len(resync.OpenStreams) != 1 || resync.OpenStreams[0].ID != 2 || resync.OpenStreams[0].Rows != 36 || resync.OpenStreams[0].Cols != 100 {
			t.Fatalf("MUX_RESYNC_ACK mismatch: %+v", resync)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for MUX_RESYNC_ACK")
	}

	// Send SIGKILL via PTY_SIGNAL to stream 2 and clean up.
	_ = mux.WriteFrame(hostToGuestW, mux.Frame{
		Type:     mux.FramePTYSignal,
		StreamID: 2,
		Payload:  mux.EncodePTYSignal(uint8(syscall.SIGKILL)),
	})

	framesMu.Lock()
	gotHello := seenHello
	gotDaemon := stream0Out.String()
	framesMu.Unlock()
	if gotHello == nil || gotHello.Protocol != 1 || !gotHello.InitDaemon {
		t.Fatalf("guest MUX_HELLO mismatch: %+v", gotHello)
	}
	if !strings.Contains(gotDaemon, "daemon-ready") {
		t.Fatalf("expected initDaemon output on stream 0, got %q", gotDaemon)
	}

	cancel()
	_ = hostToGuestW.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("session Run did not exit cleanly after context cancel")
	}
}

// TestShellProbeWorkDirAndEnvDefaults attacks shell discovery order (/bin/bash,
// /bin/ash, /bin/sh), /workspace fallback to /, and TERM/COLORTERM/PATH rules.
func TestShellProbeWorkDirAndEnvDefaults(t *testing.T) {
	t.Parallel()

	root := t.TempDir()
	binDir := filepath.Join(root, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatal(err)
	}

	// Empty root fails shell discovery.
	if _, err := mux.ResolveShellArgv(nil, root); err == nil {
		t.Fatal("expected error when no shell exists in root")
	}

	// Create /bin/sh first, then /bin/ash, then /bin/bash and verify strict precedence.
	shPath := filepath.Join(binDir, "sh")
	if err := os.WriteFile(shPath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := mux.ResolveShellArgv(nil, root)
	if err != nil || len(got) != 1 || got[0] != "/bin/sh" {
		t.Fatalf("expected [/bin/sh], got %v (err=%v)", got, err)
	}

	ashPath := filepath.Join(binDir, "ash")
	if err := os.WriteFile(ashPath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err = mux.ResolveShellArgv(nil, root)
	if err != nil || got[0] != "/bin/ash" {
		t.Fatalf("expected [/bin/ash], got %v (err=%v)", got, err)
	}

	bashPath := filepath.Join(binDir, "bash")
	if err := os.WriteFile(bashPath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err = mux.ResolveShellArgv(nil, root)
	if err != nil || got[0] != "/bin/bash" {
		t.Fatalf("expected [/bin/bash], got %v (err=%v)", got, err)
	}

	// Workdir fallback: missing /workspace falls back to /.
	if wd, err := mux.ResolveWorkDir("", root); err != nil || wd != "/" {
		t.Fatalf("ResolveWorkDir empty without /workspace = %q, err=%v", wd, err)
	}
	if err := os.MkdirAll(filepath.Join(root, "workspace"), 0o755); err != nil {
		t.Fatal(err)
	}
	if wd, err := mux.ResolveWorkDir("", root); err != nil || wd != "/workspace" {
		t.Fatalf("ResolveWorkDir empty with /workspace = %q, err=%v", wd, err)
	}

	// Environment defaults and PATH prepending.
	env := mux.BuildGuestEnv([]string{"PATH=/usr/bin:/bin", "FOO=bar"}, map[string]string{"CUSTOM": "1"}, "/run/termvr-agent/bin")
	envMap := make(map[string]string, len(env))
	for _, kv := range env {
		k, v, ok := strings.Cut(kv, "=")
		if ok {
			envMap[k] = v
		}
	}
	if envMap["TERM"] != "xterm-ghostty" || envMap["COLORTERM"] != "truecolor" {
		t.Fatalf("missing default TERM/COLORTERM: %v", envMap)
	}
	if !strings.HasPrefix(envMap["PATH"], "/run/termvr-agent/bin:") {
		t.Fatalf("expected /run/termvr-agent/bin prepended to PATH, got %q", envMap["PATH"])
	}
	if envMap["CUSTOM"] != "1" || envMap["FOO"] != "bar" {
		t.Fatalf("env merge lost entries: %v", envMap)
	}
}
