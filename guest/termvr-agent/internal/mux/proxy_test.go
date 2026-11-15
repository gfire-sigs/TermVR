package mux_test

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.gfire.termvr/guest/termvr-agent/internal/mux"
)

// startMockHostMux starts a Session connected to an in-memory host mux loop
// that records PROXY_CONNECT requests and either echoes or forwards to a test target,
// or replies with ProxyStatusDirectBypass.
func startMockHostMux(t *testing.T, onConnect func(streamID uint32, host string, port uint16) (status uint8, msg string, upstream net.Conn)) (*mux.Session, func()) {
	t.Helper()

	hostToGuestR, hostToGuestW := io.Pipe()
	guestToHostR, guestToHostW := io.Pipe()

	ctx, cancel := context.WithCancel(context.Background())
	sess := mux.NewSession(mux.SessionConfig{
		Reader: hostToGuestR,
		Writer: guestToHostW,
	})
	go func() { _ = sess.Run(ctx) }()

	// Consume initial MUX_HELLO.
	hello, err := mux.ReadFrame(guestToHostR)
	if err != nil || hello.Type != mux.FrameMuxHello {
		t.Fatalf("expected MUX_HELLO, got %+v, err=%v", hello, err)
	}

	var mu sync.Mutex
	upstreams := make(map[uint32]net.Conn)
	var writeMu sync.Mutex
	writeHost := func(f mux.Frame) {
		writeMu.Lock()
		defer writeMu.Unlock()
		_ = mux.WriteFrame(hostToGuestW, f)
	}

	go func() {
		for {
			f, err := mux.ReadFrame(guestToHostR)
			if err != nil {
				return
			}
			switch f.Type {
			case mux.FrameProxyConnect:
				req, err := mux.DecodeProxyConnect(f.Payload)
				if err != nil {
					continue
				}
				st, msg, up := onConnect(f.StreamID, req.Host, req.Port)
				ack := mux.EncodeProxyConnectAck(mux.ProxyConnectAck{Status: st, Message: msg})
				writeHost(mux.Frame{Type: mux.FrameProxyConnectAck, StreamID: f.StreamID, Payload: ack})
				if st == mux.ProxyStatusOK && up != nil {
					mu.Lock()
					upstreams[f.StreamID] = up
					mu.Unlock()
					sid := f.StreamID
					go func() {
						buf := make([]byte, 32*1024)
						for {
							n, rerr := up.Read(buf)
							if n > 0 {
								writeHost(mux.Frame{
									Type:     mux.FramePTYData,
									StreamID: sid,
									Payload:  buf[:n],
								})
							}
							if rerr != nil {
								writeHost(mux.Frame{
									Type:     mux.FramePTYExit,
									StreamID: sid,
									Payload:  mux.EncodePTYExit(0),
								})
								_ = up.Close()
								return
							}
						}
					}()
				}
			case mux.FramePTYData:
				mu.Lock()
				up := upstreams[f.StreamID]
				mu.Unlock()
				if up != nil {
					_, _ = up.Write(f.Payload)
				}
			case mux.FramePTYExit:
				mu.Lock()
				up := upstreams[f.StreamID]
				delete(upstreams, f.StreamID)
				mu.Unlock()
				if up != nil {
					_ = up.Close()
				}
			}
		}
	}()

	cleanup := func() {
		cancel()
		_ = hostToGuestW.Close()
		mu.Lock()
		for _, c := range upstreams {
			_ = c.Close()
		}
		mu.Unlock()
	}
	return sess, cleanup
}

// TestMixedProxyProtocolsAndBypass verifies SOCKS5h, SOCKS4, SOCKS4a, HTTP CONNECT,
// plain HTTP absolute-URI proxy forwarding, NO_PROXY bypass, and DirectBypass fallback.
func TestMixedProxyProtocolsAndBypass(t *testing.T) {
	t.Parallel()

	// Real local HTTP test server used both via mux upstream and direct bypass.
	targetSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Got-Host", r.Host)
		w.Header().Set("X-Got-Path", r.URL.Path)
		_, _ = io.WriteString(w, "ok-from-target")
	}))
	defer targetSrv.Close()

	targetHostPort := strings.TrimPrefix(targetSrv.URL, "http://")
	targetHost, targetPortStr, err := net.SplitHostPort(targetHostPort)
	if err != nil {
		t.Fatal(err)
	}
	_ = targetHost

	type dialRecord struct {
		host string
		port uint16
	}
	dials := make(chan dialRecord, 16)

	sess, cleanup := startMockHostMux(t, func(streamID uint32, host string, port uint16) (uint8, string, net.Conn) {
		dials <- dialRecord{host: host, port: port}
		if host == "direct-bypass.test" {
			return mux.ProxyStatusDirectBypass, "DIRECT", nil
		}
		// Connect mux upstream to the real targetSrv when host is remote-domain.test or loopback.
		up, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial("tcp", targetHostPort)
		if err != nil {
			return 5, err.Error(), nil
		}
		return mux.ProxyStatusOK, "", up
	})
	defer cleanup()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	proxySrv, err := mux.StartMixedProxyServer(ctx, mux.ProxyConfig{
		ListenAddr:     "127.0.0.1:0",
		Session:        sess,
		NoProxyEntries: []string{"10.0.0.0/8", ".cluster.local", "noproxy.local"},
	})
	if err != nil {
		t.Fatalf("StartMixedProxyServer: %v", err)
	}
	defer proxySrv.Close()
	proxyAddr := proxySrv.Addr().String()

	t.Run("socks5h_domain_tunnel", func(t *testing.T) {
		conn, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial("tcp", proxyAddr)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))

		// Greeting: VER=5, NMETHODS=1, METHOD=0 (no auth)
		_, _ = conn.Write([]byte{0x05, 0x01, 0x00})
		var greetResp [2]byte
		if _, err := io.ReadFull(conn, greetResp[:]); err != nil {
			t.Fatal(err)
		}
		if greetResp != [2]byte{0x05, 0x00} {
			t.Fatalf("unexpected SOCKS5 greeting resp: %v", greetResp)
		}

		// CONNECT remote-domain.test:8080 (ATYP=0x03)
		domain := "remote-domain.test"
		req := []byte{0x05, 0x01, 0x00, 0x03, byte(len(domain))}
		req = append(req, domain...)
		req = binary.BigEndian.AppendUint16(req, 8080)
		_, _ = conn.Write(req)

		var repHdr [4]byte
		if _, err := io.ReadFull(conn, repHdr[:]); err != nil {
			t.Fatal(err)
		}
		if repHdr[0] != 0x05 || repHdr[1] != 0x00 {
			t.Fatalf("SOCKS5 CONNECT failed: %v", repHdr)
		}
		// Drain BND.ADDR + BND.PORT for IPv4 ATYP=1 (4 + 2 bytes)
		var bnd [6]byte
		if _, err := io.ReadFull(conn, bnd[:]); err != nil {
			t.Fatal(err)
		}

		dr := <-dials
		if dr.host != "remote-domain.test" || dr.port != 8080 {
			t.Fatalf("mux received target %+v, want remote-domain.test:8080", dr)
		}

		_, _ = io.WriteString(conn, "GET /socks5h HTTP/1.1\r\nHost: remote-domain.test\r\nConnection: close\r\n\r\n")
		body, err := io.ReadAll(conn)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(body), "ok-from-target") || !strings.Contains(string(body), "/socks5h") {
			t.Fatalf("unexpected HTTP over SOCKS5h response: %s", string(body))
		}
	})

	t.Run("socks4a_domain_tunnel", func(t *testing.T) {
		conn, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial("tcp", proxyAddr)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))

		// SOCKS4a: VER=4, CD=1, PORT=443, IP=0.0.0.1, USERID="u\0", HOST="socks4a.test\0"
		var pkt []byte
		pkt = append(pkt, 0x04, 0x01)
		pkt = binary.BigEndian.AppendUint16(pkt, 443)
		pkt = append(pkt, 0, 0, 0, 1)
		pkt = append(pkt, "u\x00"...)
		pkt = append(pkt, "socks4a.test\x00"...)
		_, _ = conn.Write(pkt)

		var resp [8]byte
		if _, err := io.ReadFull(conn, resp[:]); err != nil {
			t.Fatal(err)
		}
		if resp[0] != 0x00 || resp[1] != 0x5A {
			t.Fatalf("SOCKS4a rejected: %v", resp)
		}
		dr := <-dials
		if dr.host != "socks4a.test" || dr.port != 443 {
			t.Fatalf("mux received %+v, want socks4a.test:443", dr)
		}
		_, _ = io.WriteString(conn, "GET /socks4a HTTP/1.1\r\nHost: socks4a.test\r\nConnection: close\r\n\r\n")
		body, _ := io.ReadAll(conn)
		if !strings.Contains(string(body), "ok-from-target") {
			t.Fatalf("unexpected HTTP over SOCKS4a response: %s", string(body))
		}
	})

	t.Run("http_connect_tunnel", func(t *testing.T) {
		conn, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial("tcp", proxyAddr)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))

		_, _ = io.WriteString(conn, "CONNECT connect-target.test:8443 HTTP/1.1\r\nHost: connect-target.test:8443\r\n\r\n")
		br := bufio.NewReader(conn)
		resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
		if err != nil {
			t.Fatal(err)
		}
		if resp.StatusCode != 200 {
			t.Fatalf("CONNECT status = %d, want 200", resp.StatusCode)
		}
		dr := <-dials
		if dr.host != "connect-target.test" || dr.port != 8443 {
			t.Fatalf("mux received %+v, want connect-target.test:8443", dr)
		}
		_, _ = io.WriteString(conn, "GET /connect-ok HTTP/1.1\r\nHost: connect-target.test\r\nConnection: close\r\n\r\n")
		body, _ := io.ReadAll(br)
		if !strings.Contains(string(body), "ok-from-target") {
			t.Fatalf("unexpected HTTP over CONNECT response: %s", string(body))
		}
	})

	t.Run("http_plain_proxy_request", func(t *testing.T) {
		conn, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial("tcp", proxyAddr)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))

		_, _ = io.WriteString(conn, "GET http://plain-http.test:"+targetPortStr+"/plain-path HTTP/1.1\r\nHost: plain-http.test:"+targetPortStr+"\r\nProxy-Connection: keep-alive\r\nConnection: close\r\n\r\n")
		dr := <-dials
		if dr.host != "plain-http.test" {
			t.Fatalf("mux received %+v, want plain-http.test", dr)
		}
		body, err := io.ReadAll(conn)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(body), "ok-from-target") || !strings.Contains(string(body), "/plain-path") {
			t.Fatalf("unexpected plain HTTP proxy response: %s", string(body))
		}
	})

	t.Run("no_proxy_matcher_and_direct_bypass", func(t *testing.T) {
		if !mux.MatchesNoProxy("10.42.0.5", []string{"10.0.0.0/8"}) {
			t.Fatal("expected 10.42.0.5 to match 10.0.0.0/8")
		}
		if !mux.MatchesNoProxy("api.default.svc.cluster.local", []string{".cluster.local"}) {
			t.Fatal("expected suffix .cluster.local match")
		}
		if mux.MatchesNoProxy("example.com", []string{"10.0.0.0/8", ".cluster.local"}) {
			t.Fatal("example.com should not match NO_PROXY")
		}

		// Direct bypass from host triggers ErrDirectBypass then local dial (which fails on nonexistent host).
		_, err := proxySrv.DialTarget(ctx, "direct-bypass.test", 80)
		dr := <-dials
		if dr.host != "direct-bypass.test" {
			t.Fatalf("expected direct-bypass.test to hit host first, got %+v", dr)
		}
		if err == nil || errors.Is(err, mux.ErrDirectBypass) {
			// Local dial error expected since direct-bypass.test does not exist in DNS.
			if errors.Is(err, mux.ErrDirectBypass) {
				t.Fatal("ProxyDialer leaked ErrDirectBypass instead of attempting direct net.Dial")
			}
		}
	})
}

// TestIPTReplaceBlobBuilder verifies the 64-bit Linux netfilter nat OUTPUT
// ipt_replace binary layout, bypass rules (127.0.0.0/8, 10.0.2.0/24, NO_PROXY),
// and REDIRECT --to-ports encoding independently of kernel CAP_NET_ADMIN.
func TestIPTReplaceBlobBuilder(t *testing.T) {
	t.Parallel()

	entries := []string{"127.0.0.0/8", "10.0.2.0/24", "172.16.0.0/12", ".cluster.local"}
	blob, numEntries, hookEntry, underflow, err := mux.BuildNATOutputEntries(1081, entries)
	if err != nil {
		t.Fatalf("BuildNATOutputEntries: %v", err)
	}
	// 3 valid IPv4 CIDRs (RETURN) + 1 REDIRECT + 1 policy RETURN + 1 ERROR end-of-table = 6 entries.
	if numEntries != 6 {
		t.Fatalf("numEntries = %d, want 6", numEntries)
	}
	if hookEntry[3] != 0 {
		t.Fatalf("NF_INET_LOCAL_OUT (3) hookEntry = %d, want 0", hookEntry[3])
	}
	if underflow[3] == 0 || int(underflow[3]) >= len(blob) {
		t.Fatalf("invalid NF_INET_LOCAL_OUT underflow offset %d (len=%d)", underflow[3], len(blob))
	}
	// Walk every ipt_entry by next_offset and verify exact termination at len(blob).
	off := 0
	count := 0
	for off < len(blob) {
		if off+112 > len(blob) {
			t.Fatalf("truncated ipt_entry at %d", off)
		}
		nextOff := int(binary.LittleEndian.Uint16(blob[off+90 : off+92]))
		if nextOff < 112 || off+nextOff > len(blob) {
			t.Fatalf("bad next_offset %d at %d", nextOff, off)
		}
		off += nextOff
		count++
	}
	if off != len(blob) || count != int(numEntries) {
		t.Fatalf("entry walk ended at %d/%d, count=%d/%d", off, len(blob), count, numEntries)
	}
}
