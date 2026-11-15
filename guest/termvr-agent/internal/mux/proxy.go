package mux

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// DefaultProxyListenAddr is the mixed SOCKS5/SOCKS4/HTTP listener address.
	DefaultProxyListenAddr = "127.0.0.1:1080"
	// DefaultBypassMark marks direct outbound sockets to prevent transparent-redirect loops.
	DefaultBypassMark = 0x5456

	socks5Version        = 0x05
	socks5MethodNoAuth   = 0x00
	socks5MethodNoAccept = 0xFF
	socks5CmdConnect     = 0x01
	socks5AtypIPv4       = 0x01
	socks5AtypDomain     = 0x03
	socks5AtypIPv6       = 0x04

	socks4Version       = 0x04
	socks4CmdConnect    = 0x01
	socks4ReplyGranted  = 0x5A
	socks4ReplyRejected = 0x5B

	iptEntrySize          = 112
	xtTargetHeaderSize    = 32
	xtMatchHeaderSize     = 32
	xtStandardTargetSize  = 40 // 32 + XT_ALIGN(4)
	xtErrorTargetSize     = 64 // 32 + 32 (XT_FUNCTION_MAXNAMELEN = 30, aligned to 32)
	xtTCPMatchSize        = 48 // 32 + 16
	nfNatMultiRangeSize   = 56 // 32 + 24
	xtMarkMatchSize       = 48 // 32 + 16
	iptProtoTCP           = 6
	iptVerdictReturn      = -5 // XT_RETURN (-NF_REPEAT - 1)
	iptVerdictAccept      = -2 // -NF_ACCEPT - 1
	nfNatRangeProtoSpec   = 2  // IP_NAT_RANGE_PROTO_SPECIFIED
	nfInetLocalOutHookIdx = 3
)

// ProxyConfig configures the guest mixed proxy or transparent proxy listener.
type ProxyConfig struct {
	ListenAddr     string
	Session        *Session
	NoProxyEntries []string
	SocketMark     int
	DialTimeout    time.Duration
}

// ProxyServer serves outbound proxy connections over a mux Session with bypass fallback.
type ProxyServer struct {
	ln     net.Listener
	cfg    ProxyConfig
	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
	mu     sync.Mutex // guards conns and closed
	conns  map[net.Conn]struct{}
	closed bool
}

func newProxyServer(ctx context.Context, cfg ProxyConfig, ln net.Listener) *ProxyServer {
	if cfg.DialTimeout <= 0 {
		cfg.DialTimeout = 15 * time.Second
	}
	srvCtx, cancel := context.WithCancel(ctx)
	s := &ProxyServer{
		ln:     ln,
		cfg:    cfg,
		ctx:    srvCtx,
		cancel: cancel,
		conns:  make(map[net.Conn]struct{}),
	}
	go func() {
		<-srvCtx.Done()
		s.closeAllConns()
	}()
	return s
}

func (s *ProxyServer) trackConn(c net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.conns[c] = struct{}{}
	return true
}

func (s *ProxyServer) untrackConn(c net.Conn) {
	s.mu.Lock()
	delete(s.conns, c)
	s.mu.Unlock()
}

func (s *ProxyServer) closeAllConns() {
	_ = s.ln.Close()
	s.mu.Lock()
	if !s.closed {
		s.closed = true
		for c := range s.conns {
			_ = c.Close()
		}
	}
	s.mu.Unlock()
}

// StartMixedProxyServer binds 127.0.0.1:1080 and serves SOCKS5/5h, SOCKS4/4a, and HTTP CONNECT/plain proxy.
func StartMixedProxyServer(ctx context.Context, cfg ProxyConfig) (*ProxyServer, error) {
	addr := cfg.ListenAddr
	if addr == "" {
		addr = DefaultProxyListenAddr
	}
	ln, err := listenTCPAddr(addr)
	if err != nil {
		return nil, wrapErr("listen mixed proxy "+addr, err)
	}
	s := newProxyServer(ctx, cfg, ln)
	s.wg.Add(1)
	go s.acceptMixedLoop()
	return s, nil
}

func listenTCPAddr(raw string) (*net.TCPListener, error) {
	host, portStr, err := net.SplitHostPort(raw)
	if err != nil {
		return nil, err
	}
	var port int
	if portStr != "" {
		p, err := strconv.ParseUint(portStr, 10, 16)
		if err != nil {
			return nil, err
		}
		port = int(p)
	}
	if host == "" {
		return net.ListenTCP("tcp", &net.TCPAddr{Port: port})
	}
	if asciiEqualFold(host, "localhost") {
		return net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	}
	ipStr, zone, _ := strings.Cut(strings.Trim(host, "[]"), "%")
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return nil, errStr("invalid listen ip: " + host)
	}
	return net.ListenTCP("tcp", &net.TCPAddr{IP: ip, Port: port, Zone: zone})
}

// Addr returns the bound listener network address.
func (s *ProxyServer) Addr() net.Addr {
	return s.ln.Addr()
}

// Close stops accepting connections and waits for active tunnels.
func (s *ProxyServer) Close() error {
	s.cancel()
	s.closeAllConns()
	s.wg.Wait()
	return nil
}

func (s *ProxyServer) acceptMixedLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return
		}
		if !s.trackConn(conn) {
			_ = conn.Close()
			return
		}
		s.wg.Add(1)
		go func(c net.Conn) {
			defer s.wg.Done()
			defer s.untrackConn(c)
			defer c.Close()
			s.handleMixedConn(c)
		}(conn)
	}
}

// DialTarget connects to host:port via mux, or directly if matched by NO_PROXY or ErrDirectBypass.
func (s *ProxyServer) DialTarget(ctx context.Context, host string, port uint16) (io.ReadWriteCloser, error) {
	if MatchesNoProxy(host, s.cfg.NoProxyEntries) || s.cfg.Session == nil {
		return s.dialDirect(ctx, host, port)
	}
	stream, err := s.cfg.Session.DialProxy(ctx, host, port)
	if err == nil {
		return stream, nil
	}
	if errors.Is(err, ErrDirectBypass) {
		return s.dialDirect(ctx, host, port)
	}
	return nil, err
}

func (s *ProxyServer) dialDirect(ctx context.Context, host string, port uint16) (io.ReadWriteCloser, error) {
	dialCtx, cancel := context.WithTimeout(ctx, s.cfg.DialTimeout)
	defer cancel()
	ip, err := resolveDirectAddr(dialCtx, host)
	if err != nil {
		return nil, err
	}
	return dialDirectAddr(dialCtx, ip, port, s.cfg.SocketMark, s.cfg.DialTimeout)
}

func dialDirectTCPPlain(ctx context.Context, ip net.IP, port uint16) (net.Conn, error) {
	raddr := &net.TCPAddr{IP: ip, Port: int(port)}
	type dialRes struct {
		conn net.Conn
		err  error
	}
	ch := make(chan dialRes, 1)
	go func() {
		c, err := net.DialTCP("tcp", nil, raddr)
		ch <- dialRes{conn: c, err: err}
	}()
	select {
	case res := <-ch:
		return res.conn, res.err
	case <-ctx.Done():
		go func() {
			if res := <-ch; res.conn != nil {
				_ = res.conn.Close()
			}
		}()
		return nil, ctx.Err()
	}
}

func resolveDirectAddr(ctx context.Context, host string) (net.IP, error) {
	h := asciiTrimSpace(strings.Trim(host, "[]"))
	if h == "" {
		return nil, errStr("empty direct target host")
	}
	if ip := net.ParseIP(h); ip != nil {
		return ip, nil
	}
	if asciiEqualFold(h, "localhost") {
		return net.IPv4(127, 0, 0, 1), nil
	}
	if raw, err := os.ReadFile("/etc/hosts"); err == nil {
		for rest := string(raw); rest != ""; {
			var line string
			line, rest, _ = strings.Cut(rest, "\n")
			if idx := strings.IndexByte(line, '#'); idx >= 0 {
				line = line[:idx]
			}
			fields := splitASCIIFields(line)
			if len(fields) < 2 {
				continue
			}
			ip := net.ParseIP(fields[0])
			if ip == nil {
				continue
			}
			for _, name := range fields[1:] {
				if asciiEqualFold(name, h) {
					return ip, nil
				}
			}
		}
	}
	return resolveViaUDPDNS(ctx, h)
}

func resolveViaUDPDNS(ctx context.Context, host string) (net.IP, error) {
	ns := net.IPv4(127, 0, 0, 1)
	if raw, err := os.ReadFile("/etc/resolv.conf"); err == nil {
		for rest := string(raw); rest != ""; {
			var line string
			line, rest, _ = strings.Cut(rest, "\n")
			line = asciiTrimSpace(line)
			if val, ok := strings.CutPrefix(line, "nameserver"); ok && len(val) > 0 && isASCIIWS(val[0]) {
				if parsed := net.ParseIP(asciiTrimSpace(val)); parsed != nil && parsed.To4() != nil {
					ns = parsed.To4()
					break
				}
			}
		}
	}
	conn, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: ns, Port: 53})
	if err != nil {
		return nil, err
	}
	dl, ok := ctx.Deadline()
	if !ok {
		dl = time.Now().Add(3 * time.Second)
	}
	_ = conn.SetDeadline(dl)

	var pkt [512]byte
	pkt[0], pkt[1], pkt[2], pkt[5] = 0x54, 0x56, 0x01, 1 // ID=0x5456, RD=1, QDCOUNT=1
	pos := 12
	for rest := strings.TrimSuffix(host, "."); rest != ""; {
		var lbl string
		lbl, rest, _ = strings.Cut(rest, ".")
		if len(lbl) == 0 || len(lbl) > 63 || pos+1+len(lbl)+5 > len(pkt) {
			_ = conn.Close()
			return nil, errStr("invalid dns hostname")
		}
		pkt[pos] = byte(len(lbl))
		pos++
		pos += copy(pkt[pos:], lbl)
	}
	pkt[pos], pkt[pos+2], pkt[pos+4] = 0, 1, 1 // root, QTYPE=A, QCLASS=IN
	pos += 5

	_, werr := conn.Write(pkt[:pos])
	var n int
	if werr == nil {
		n, werr = conn.Read(pkt[:])
	}
	_ = conn.Close()
	if werr != nil || n < pos+12 {
		return nil, errStr("dns lookup failed for " + host)
	}
	anCount := int(be16(pkt[6:8]))
	cur := pos
	for i := 0; i < anCount && cur < n; i++ {
		if pkt[cur]&0xC0 == 0xC0 {
			cur += 2
		} else {
			for cur < n && pkt[cur] != 0 {
				cur += int(pkt[cur]) + 1
			}
			cur++
		}
		if cur+10 > n {
			break
		}
		typ := be16(pkt[cur : cur+2])
		rdlen := int(be16(pkt[cur+8 : cur+10]))
		cur += 10
		if cur+rdlen > n {
			break
		}
		if typ == 1 && rdlen == 4 {
			return net.IPv4(pkt[cur], pkt[cur+1], pkt[cur+2], pkt[cur+3]), nil
		}
		cur += rdlen
	}
	return nil, errStr("no A record for " + host)
}

// MatchesNoProxy evaluates host against IP, CIDR, exact hostname, and suffix entries.
func MatchesNoProxy(host string, entries []string) bool {
	h := asciiTrimSpace(asciiToLower(host))
	h = strings.TrimPrefix(h, "[")
	h = strings.TrimSuffix(h, "]")
	if h == "" {
		return false
	}
	ip := net.ParseIP(h)
	for _, raw := range entries {
		entry := asciiTrimSpace(asciiToLower(raw))
		if entry == "" {
			continue
		}
		if entry == "*" {
			return true
		}
		if strings.Contains(entry, "/") {
			if ip != nil {
				if _, ipnet, err := net.ParseCIDR(entry); err == nil && ipnet.Contains(ip) {
					return true
				}
			}
			continue
		}
		if eIP := net.ParseIP(strings.Trim(entry, "[]")); eIP != nil && ip != nil {
			if eIP.Equal(ip) {
				return true
			}
			continue
		}
		trimmed := strings.TrimPrefix(entry, ".")
		if h == trimmed || strings.HasSuffix(h, "."+trimmed) {
			return true
		}
	}
	return false
}

func (s *ProxyServer) handleMixedConn(c net.Conn) {
	_ = c.SetReadDeadline(time.Now().Add(15 * time.Second))
	br := newBufReader(c, 8192)
	first, err := br.PeekByte()
	if err != nil {
		return
	}
	switch first {
	case socks5Version:
		s.handleSOCKS5(c, br)
	case socks4Version:
		s.handleSOCKS4(c, br)
	default:
		s.handleHTTP(c, br)
	}
}

//go:noinline
func socks5Reply(c net.Conn, rep byte) error {
	_, err := c.Write([]byte{socks5Version, rep, 0x00, socks5AtypIPv4, 0, 0, 0, 0, 0, 0})
	return err
}

//go:noinline
func socks4Reply(c net.Conn, rep byte) error {
	_, err := c.Write([]byte{0x00, rep, 0, 0, 0, 0, 0, 0})
	return err
}

func (s *ProxyServer) handleSOCKS5(c net.Conn, br *bufReader) {
	var hdr [2]byte
	if _, err := io.ReadFull(br, hdr[:]); err != nil {
		return
	}
	nMethods := int(hdr[1])
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	hasNoAuth := false
	for _, m := range methods {
		if m == socks5MethodNoAuth {
			hasNoAuth = true
			break
		}
	}
	if !hasNoAuth {
		_, _ = c.Write([]byte{socks5Version, socks5MethodNoAccept})
		return
	}
	if _, err := c.Write([]byte{socks5Version, socks5MethodNoAuth}); err != nil {
		return
	}

	var reqHdr [4]byte
	if _, err := io.ReadFull(br, reqHdr[:]); err != nil {
		return
	}
	if reqHdr[0] != socks5Version {
		return
	}
	if reqHdr[1] != socks5CmdConnect {
		_ = socks5Reply(c, 0x07)
		return
	}

	var host string
	switch reqHdr[3] {
	case socks5AtypIPv4:
		var ip4 [4]byte
		if _, err := io.ReadFull(br, ip4[:]); err != nil {
			return
		}
		host = net.IP(ip4[:]).String()
	case socks5AtypDomain:
		lenByte, err := br.ReadByte()
		if err != nil || lenByte == 0 {
			return
		}
		dom := make([]byte, int(lenByte))
		if _, err := io.ReadFull(br, dom); err != nil {
			return
		}
		host = string(dom)
	case socks5AtypIPv6:
		var ip6 [16]byte
		if _, err := io.ReadFull(br, ip6[:]); err != nil {
			return
		}
		host = net.IP(ip6[:]).String()
	default:
		_ = socks5Reply(c, 0x08)
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(br, portBuf[:]); err != nil {
		return
	}
	port := be16(portBuf[:])
	_ = c.SetReadDeadline(time.Time{})

	up, err := s.DialTarget(s.ctx, host, port)
	if err != nil {
		_ = socks5Reply(c, 0x05)
		return
	}
	defer up.Close()

	if err := socks5Reply(c, 0x00); err != nil {
		return
	}
	relayStreams(c, br, up)
}

func (s *ProxyServer) handleSOCKS4(c net.Conn, br *bufReader) {
	var hdr [8]byte
	if _, err := io.ReadFull(br, hdr[:]); err != nil {
		return
	}
	if hdr[0] != socks4Version || hdr[1] != socks4CmdConnect {
		_ = socks4Reply(c, socks4ReplyRejected)
		return
	}
	port := be16(hdr[2:4])
	ip4 := [4]byte{hdr[4], hdr[5], hdr[6], hdr[7]}

	// Read null-terminated USERID.
	if _, err := readNullTerminated(br, 512); err != nil {
		return
	}

	var host string
	if ip4[0] == 0 && ip4[1] == 0 && ip4[2] == 0 && ip4[3] != 0 {
		// SOCKS4a domain extension.
		dom, err := readNullTerminated(br, 512)
		if err != nil || dom == "" {
			_ = socks4Reply(c, socks4ReplyRejected)
			return
		}
		host = dom
	} else {
		host = net.IP(ip4[:]).String()
	}
	_ = c.SetReadDeadline(time.Time{})

	up, err := s.DialTarget(s.ctx, host, port)
	if err != nil {
		_ = socks4Reply(c, socks4ReplyRejected)
		return
	}
	defer up.Close()

	if err := socks4Reply(c, socks4ReplyGranted); err != nil {
		return
	}
	relayStreams(c, br, up)
}

func readNullTerminated(br *bufReader, maxLen int) (string, error) {
	buf := make([]byte, 0, 64)
	for i := 0; i < maxLen; i++ {
		ch, err := br.ReadByte()
		if err != nil {
			return "", err
		}
		if ch == 0 {
			return string(buf), nil
		}
		buf = append(buf, ch)
	}
	return "", errStr("null-terminated field exceeds max length")
}

func readFilteredHTTPHeaders(br *bufReader) (hdrBytes []byte, hostHeader string, hasHost bool, err error) {
	for i := 0; i < 256; i++ {
		rawLine, lerr := br.ReadLine(16 * 1024)
		if lerr != nil {
			return nil, "", false, lerr
		}
		line := strings.TrimRight(string(rawLine), "\r\n")
		if line == "" {
			return hdrBytes, hostHeader, hasHost, nil
		}
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			return nil, "", false, errStr("malformed http header")
		}
		k = asciiTrimSpace(k)
		v = asciiTrimSpace(v)
		if asciiEqualFold(k, "Proxy-Connection") || asciiEqualFold(k, "Proxy-Authorization") {
			continue
		}
		if asciiEqualFold(k, "Host") {
			hasHost = true
			if hostHeader == "" {
				hostHeader = v
			}
		}
		hdrBytes = append(hdrBytes, k...)
		hdrBytes = append(hdrBytes, ':', ' ')
		hdrBytes = append(hdrBytes, v...)
		hdrBytes = append(hdrBytes, '\r', '\n')
	}
	return nil, "", false, errStr("too many http headers")
}

func splitHTTPTargetURL(raw string) (scheme, host, originURI string, err error) {
	if raw == "" || hasCtrlOrWS(raw) {
		return "", "", "", errStr("invalid request target")
	}
	if strings.HasPrefix(raw, "/") {
		return "", "", raw, nil
	}
	if s, rest, ok := strings.Cut(raw, "://"); ok {
		scheme = asciiToLower(s)
		if idx := strings.IndexAny(rest, "/?#"); idx >= 0 {
			host = rest[:idx]
			tail := rest[idx:]
			if strings.HasPrefix(tail, "/") {
				originURI = tail
			} else {
				originURI = "/" + tail
			}
		} else {
			host = rest
			originURI = "/"
		}
		if host == "" {
			return "", "", "", errStr("missing target host")
		}
		return scheme, host, originURI, nil
	}
	return "", raw, "/", nil
}

//go:noinline
func writeHTTPErr(c net.Conn, status string) {
	_, _ = io.WriteString(c, "HTTP/1.1 "+status+"\r\nConnection: close\r\n\r\n")
}

func (s *ProxyServer) handleHTTP(c net.Conn, br *bufReader) {
	rawReq, err := br.ReadLine(16 * 1024)
	if err != nil {
		return
	}
	reqLine := strings.TrimRight(string(rawReq), "\r\n")
	method, rest, ok := strings.Cut(reqLine, " ")
	if !ok {
		return
	}
	target, proto, ok := strings.Cut(rest, " ")
	if !ok || !strings.HasPrefix(proto, "HTTP/") {
		writeHTTPErr(c, "400 Bad Request")
		return
	}
	hdrBytes, hostHeader, hasHost, err := readFilteredHTTPHeaders(br)
	if err != nil {
		writeHTTPErr(c, "400 Bad Request")
		return
	}
	_ = c.SetReadDeadline(time.Time{})

	if asciiEqualFold(method, "CONNECT") {
		authority := target
		if authority == "" {
			authority = hostHeader
		}
		host, port, err := parseHostPortWithDefault(authority, 443)
		if err != nil {
			writeHTTPErr(c, "400 Bad Request")
			return
		}
		up, err := s.DialTarget(s.ctx, host, port)
		if err != nil {
			writeHTTPErr(c, "502 Bad Gateway")
			return
		}
		defer up.Close()

		if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		relayStreams(c, br, up)
		return
	}

	// Plain HTTP proxy request (for example GET http://host/path HTTP/1.1).
	scheme, parsedHost, originURI, err := splitHTTPTargetURL(target)
	if err != nil {
		writeHTTPErr(c, "400 Bad Request")
		return
	}
	targetAddr := parsedHost
	if targetAddr == "" {
		targetAddr = hostHeader
	}
	defaultPort := uint16(80)
	if scheme == "https" {
		defaultPort = 443
	}
	host, port, err := parseHostPortWithDefault(targetAddr, defaultPort)
	if err != nil {
		writeHTTPErr(c, "400 Bad Request")
		return
	}
	up, err := s.DialTarget(s.ctx, host, port)
	if err != nil {
		writeHTTPErr(c, "502 Bad Gateway")
		return
	}
	defer up.Close()

	reqOut := make([]byte, 0, len(method)+len(originURI)+len(proto)+len(hdrBytes)+64)
	reqOut = append(reqOut, method...)
	reqOut = append(reqOut, ' ')
	reqOut = append(reqOut, originURI...)
	reqOut = append(reqOut, ' ')
	reqOut = append(reqOut, proto...)
	reqOut = append(reqOut, '\r', '\n')
	reqOut = append(reqOut, hdrBytes...)
	if !hasHost && targetAddr != "" {
		reqOut = append(reqOut, "Host: "...)
		reqOut = append(reqOut, targetAddr...)
		reqOut = append(reqOut, '\r', '\n')
	}
	reqOut = append(reqOut, '\r', '\n')

	if _, err := up.Write(reqOut); err != nil {
		return
	}
	relayStreams(c, br, up)
}

func parseHostPortWithDefault(raw string, defPort uint16) (string, uint16, error) {
	raw = asciiTrimSpace(raw)
	if raw == "" {
		return "", 0, errStr("empty host")
	}
	if strings.HasPrefix(raw, "[") && strings.HasSuffix(raw, "]") {
		return strings.Trim(raw, "[]"), defPort, nil
	}
	host, portStr, err := net.SplitHostPort(raw)
	if err != nil {
		if strings.Contains(raw, ":") && !strings.HasPrefix(raw, "[") {
			return "", 0, err
		}
		return raw, defPort, nil
	}
	p, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil || p == 0 {
		return "", 0, errStr("invalid port")
	}
	return host, uint16(p), nil
}

func relayStreams(client net.Conn, clientReader *bufReader, upstream io.ReadWriteCloser) {
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		if clientReader != nil {
			if buffered := clientReader.BufferedBytes(); len(buffered) > 0 {
				if _, err := upstream.Write(buffered); err != nil {
					_ = upstream.Close()
					return
				}
			}
		}
		_, err := copyStream(upstream, client)
		if err != nil {
			_ = upstream.Close()
			return
		}
		if cw, ok := upstream.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}()

	go func() {
		defer wg.Done()
		_, err := copyStream(client, upstream)
		if err != nil {
			_ = client.Close()
			return
		}
		if cw, ok := client.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}()

	wg.Wait()
}

// BuildNATOutputEntries constructs 64-bit Linux iptables nat OUTPUT rules:
// RETURN rules for IPv4 bypass CIDRs, REDIRECT --to-ports redirectPort for TCP,
// followed by an ACCEPT underflow rule and an ERROR end-of-table marker.
func BuildNATOutputEntries(redirectPort uint16, bypassCIDRs []string) (blob []byte, numEntries uint32, hookEntry [5]uint32, underflow [5]uint32, err error) {
	if redirectPort == 0 {
		return nil, 0, hookEntry, underflow, errStr("redirect port must be non-zero")
	}

	prefixEntries := make([][]byte, 0, len(bypassCIDRs)+4)
	for _, raw := range bypassCIDRs {
		raw = asciiTrimSpace(raw)
		if raw == "" {
			continue
		}
		_, ipnet, perr := net.ParseCIDR(raw)
		if perr != nil || ipnet.IP.To4() == nil {
			continue
		}
		prefixEntries = append(prefixEntries, buildIPTReturnCIDREntry(ipnet))
	}

	redirEntry := buildIPTRedirectTCPEntry(redirectPort)
	acceptEntry := buildIPTStandardTargetEntry([4]byte{}, [4]byte{}, 0, iptVerdictAccept)
	errEntry := buildIPTErrorEntry()

	totalLen := 0
	for _, e := range prefixEntries {
		totalLen += len(e)
	}
	underflowOff := uint32(totalLen + len(redirEntry))
	totalLen += len(redirEntry) + len(acceptEntry) + len(errEntry)

	out := make([]byte, 0, totalLen)
	for _, e := range prefixEntries {
		out = append(out, e...)
	}
	out = append(out, redirEntry...)
	out = append(out, acceptEntry...)
	out = append(out, errEntry...)

	hookEntry[nfInetLocalOutHookIdx] = 0
	underflow[nfInetLocalOutHookIdx] = underflowOff
	numEntries = uint32(len(prefixEntries) + 3)
	return out, numEntries, hookEntry, underflow, nil
}

func buildIPTReturnCIDREntry(ipnet *net.IPNet) []byte {
	var dst, mask [4]byte
	if ip4 := ipnet.IP.To4(); ip4 != nil {
		copy(dst[:], ip4)
	}
	if len(ipnet.Mask) == 4 {
		copy(mask[:], ipnet.Mask)
	}
	return buildIPTStandardTargetEntry(dst, mask, 0, iptVerdictReturn)
}

func buildIPTStandardTargetEntry(dst, dmsk [4]byte, proto uint16, verdict int32) []byte {
	total := iptEntrySize + xtStandardTargetSize
	buf := make([]byte, total)
	copy(buf[4:8], dst[:])
	copy(buf[12:16], dmsk[:])
	putLE16(buf[80:82], proto)
	putLE16(buf[88:90], iptEntrySize)
	putLE16(buf[90:92], uint16(total))

	t := buf[iptEntrySize:]
	putLE16(t[0:2], xtStandardTargetSize)
	putLE32(t[32:36], uint32(verdict))
	return buf
}

func buildIPTRedirectTCPEntry(port uint16) []byte {
	total := iptEntrySize + xtTCPMatchSize + nfNatMultiRangeSize
	buf := make([]byte, total)
	putLE16(buf[80:82], iptProtoTCP)
	putLE16(buf[88:90], iptEntrySize+xtTCPMatchSize)
	putLE16(buf[90:92], uint16(total))

	m := buf[iptEntrySize:]
	putLE16(m[0:2], xtTCPMatchSize)
	copy(m[2:31], "tcp")
	m[31] = 0
	putLE16(m[32:34], 0)
	putLE16(m[34:36], 0xFFFF)
	putLE16(m[36:38], 0)
	putLE16(m[38:40], 0xFFFF)

	t := buf[iptEntrySize+xtTCPMatchSize:]
	putLE16(t[0:2], nfNatMultiRangeSize)
	copy(t[2:31], "REDIRECT")
	t[31] = 0
	putLE32(t[32:36], 1) // rangesize = 1
	putLE32(t[36:40], nfNatRangeProtoSpec)
	putBE16(t[48:50], port)
	putBE16(t[50:52], port)
	return buf
}

func buildIPTErrorEntry() []byte {
	total := iptEntrySize + xtErrorTargetSize
	buf := make([]byte, total)
	putLE16(buf[88:90], iptEntrySize)
	putLE16(buf[90:92], uint16(total))

	t := buf[iptEntrySize:]
	putLE16(t[0:2], xtErrorTargetSize)
	copy(t[2:31], "ERROR")
	copy(t[32:64], "ERROR")
	return buf
}
