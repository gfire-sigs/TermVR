//go:build linux

package mux

import (
	"context"
	"net"
	"os"
	"syscall"
	"time"
	"unsafe"
)

const (
	soOriginalDst     = 80
	ip6tSoOriginalDst = 80
	iptBaseCtl        = 64
	iptSoSetReplace   = iptBaseCtl
	iptSoGetInfo      = iptBaseCtl
	iptSoGetEntries   = iptBaseCtl + 1
)

//go:noinline
func getsockoptBytes(fd, level, opt int, ptr unsafe.Pointer, slen *uint32) syscall.Errno {
	_, _, errno := syscall.Syscall6(syscall.SYS_GETSOCKOPT, uintptr(fd), uintptr(level), uintptr(opt), uintptr(ptr), uintptr(unsafe.Pointer(slen)), 0)
	return errno
}

func dialDirectAddr(ctx context.Context, ip net.IP, port uint16, mark int, timeout time.Duration) (net.Conn, error) {
	if mark == 0 {
		return dialDirectTCPPlain(ctx, ip, port)
	}
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	ip4 := ip.To4()
	af := syscall.AF_INET
	if ip4 == nil {
		af = syscall.AF_INET6
	}
	fd, err := syscall.Socket(af, syscall.SOCK_STREAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	// Best-effort SO_MARK; unprivileged userns without CAP_NET_ADMIN ignores EPERM.
	_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_MARK, mark)
	tv := syscall.Timeval{Sec: int64(timeout / time.Second), Usec: int64((timeout % time.Second) / time.Microsecond)}
	_ = syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_SNDTIMEO, &tv)

	var sa syscall.Sockaddr
	if ip4 != nil {
		var a4 [4]byte
		copy(a4[:], ip4)
		sa = &syscall.SockaddrInet4{Port: int(port), Addr: a4}
	} else {
		var a6 [16]byte
		copy(a6[:], ip.To16())
		sa = &syscall.SockaddrInet6{Port: int(port), Addr: a6}
	}
	if err := syscall.Connect(fd, sa); err != nil {
		_ = syscall.Close(fd)
		return nil, err
	}
	f := os.NewFile(uintptr(fd), "direct-tcp")
	conn, err := net.FileConn(f)
	_ = f.Close()
	if err != nil {
		return nil, err
	}
	return conn, nil
}

// StartTransparentProxyServer listens on listenAddr (e.g. 127.0.0.1:1081), reads
// SO_ORIGINAL_DST / IP6T_SO_ORIGINAL_DST, and tunnels TCP streams over mux.
func StartTransparentProxyServer(ctx context.Context, cfg ProxyConfig) (*ProxyServer, error) {
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = "127.0.0.1:1081"
	}
	ln, err := listenTCPAddr(cfg.ListenAddr)
	if err != nil {
		return nil, wrapErr("listen transparent proxy "+cfg.ListenAddr, err)
	}
	s := newProxyServer(ctx, cfg, ln)
	s.wg.Add(1)
	go s.acceptTransparentLoop()
	return s, nil
}

func (s *ProxyServer) acceptTransparentLoop() {
	defer s.wg.Done()
	localPort := 0
	if tcpAddr, ok := s.ln.Addr().(*net.TCPAddr); ok {
		localPort = tcpAddr.Port
	}
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
			s.handleTransparentConn(c, localPort)
		}(conn)
	}
}

func (s *ProxyServer) handleTransparentConn(c net.Conn, localPort int) {
	tcpConn, ok := c.(*net.TCPConn)
	if !ok {
		return
	}
	dstAddr, dstPort, err := getOriginalDst(tcpConn)
	if err != nil || dstPort == 0 {
		return
	}
	if dstAddr.IsLoopback() && int(dstPort) == localPort {
		return
	}
	up, err := s.DialTarget(s.ctx, dstAddr.String(), dstPort)
	if err != nil {
		return
	}
	defer up.Close()
	relayStreams(c, nil, up)
}

func getOriginalDst(conn *net.TCPConn) (net.IP, uint16, error) {
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return nil, 0, err
	}
	var outAddr net.IP
	var outPort uint16
	var outErr error

	if err := rawConn.Control(func(fd uintptr) {
		var sin [16]byte
		slen := uint32(len(sin))
		if getsockoptBytes(int(fd), syscall.SOL_IP, soOriginalDst, unsafe.Pointer(&sin[0]), &slen) == 0 && slen >= 8 {
			outPort = be16(sin[2:4])
			outAddr = net.IPv4(sin[4], sin[5], sin[6], sin[7])
			return
		}

		var sin6 [28]byte
		slen6 := uint32(len(sin6))
		if getsockoptBytes(int(fd), syscall.SOL_IPV6, ip6tSoOriginalDst, unsafe.Pointer(&sin6[0]), &slen6) == 0 && slen6 >= 24 {
			outPort = be16(sin6[2:4])
			ip := make(net.IP, 16)
			copy(ip, sin6[8:24])
			if v4 := ip.To4(); v4 != nil {
				ip = v4
			}
			outAddr = ip
			return
		}
		outErr = errStr("SO_ORIGINAL_DST and IP6T_SO_ORIGINAL_DST failed")
	}); err != nil {
		return nil, 0, err
	}
	return outAddr, outPort, outErr
}

type iptGetInfo struct {
	Name       [32]byte
	ValidHooks uint32
	HookEntry  [5]uint32
	Underflow  [5]uint32
	NumEntries uint32
	Size       uint32
}

type iptReplaceHeader struct {
	Name        [32]byte
	ValidHooks  uint32
	NumEntries  uint32
	Size        uint32
	HookEntry   [5]uint32
	Underflow   [5]uint32
	NumCounters uint32
	Counters    uintptr
}

type xtCounters struct {
	Pcnt uint64
	Bcnt uint64
}

// InstallTransparentRedirect installs IPv4 nat OUTPUT REDIRECT rules via pure-Go IPT_SO_SET_REPLACE,
// skipping 127.0.0.0/8, 10.0.2.0/24, SO_MARK bypass packets, and NO_PROXY CIDRs.
func InstallTransparentRedirect(redirectPort uint16, noProxyEntries []string, bypassMark int) error {
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_RAW, syscall.IPPROTO_RAW)
	if err != nil {
		return wrapErr("open raw ipv4 socket for iptables", err)
	}
	defer syscall.Close(fd)

	var info iptGetInfo
	copy(info.Name[:], "nat")
	infoLen := uint32(unsafe.Sizeof(info))
	if errno := getsockoptBytes(fd, syscall.SOL_IP, iptSoGetInfo, unsafe.Pointer(&info), &infoLen); errno != 0 {
		return wrapErr("IPT_SO_GET_INFO(nat)", errno)
	}

	allBypass := append([]string{"127.0.0.0/8", "10.0.2.0/24"}, noProxyEntries...)

	// Read current nat entries so other hooks (PREROUTING, POSTROUTING) stay intact.
	getEntriesSize := 40 + int(info.Size)
	getEntriesBuf := make([]byte, getEntriesSize)
	copy(getEntriesBuf[:32], "nat")
	putLE32(getEntriesBuf[32:36], info.Size)
	getLen := uint32(getEntriesSize)
	if errno := getsockoptBytes(fd, syscall.SOL_IP, iptSoGetEntries, unsafe.Pointer(&getEntriesBuf[0]), &getLen); errno != 0 {
		return wrapErr("IPT_SO_GET_ENTRIES(nat)", errno)
	}
	existing := getEntriesBuf[40 : 40+int(info.Size)]
	insertAt := int(info.HookEntry[nfInetLocalOutHookIdx])
	if insertAt > len(existing) {
		return errStr("invalid NF_INET_LOCAL_OUT hook offset in kernel nat table")
	}

	marks := []int{bypassMark}
	if bypassMark != 0 {
		// Fallback without xt_mark if the sandbox kernel omits xt_mark.
		marks = append(marks, 0)
	}
	var lastErr error
	for _, mark := range marks {
		rulesBlob, addedRules, err := buildNATRedirectInsertion(redirectPort, allBypass, mark)
		if err != nil {
			if lastErr != nil {
				return lastErr
			}
			return err
		}
		newTable := make([]byte, 0, len(existing)+len(rulesBlob))
		newTable = append(newTable, existing[:insertAt]...)
		newTable = append(newTable, rulesBlob...)
		newTable = append(newTable, existing[insertAt:]...)

		newInfo := info
		newInfo.NumEntries += addedRules
		newInfo.Size = uint32(len(newTable))
		shift := uint32(len(rulesBlob))
		for h := 0; h < 5; h++ {
			if (info.ValidHooks & (1 << h)) == 0 {
				continue
			}
			if h != nfInetLocalOutHookIdx && info.HookEntry[h] > info.HookEntry[nfInetLocalOutHookIdx] {
				newInfo.HookEntry[h] += shift
			}
			if info.Underflow[h] >= info.HookEntry[nfInetLocalOutHookIdx] {
				newInfo.Underflow[h] += shift
			}
		}
		lastErr = applyIPTReplace(fd, newInfo, newTable, info.NumEntries)
		if lastErr == nil {
			return nil
		}
	}
	return lastErr
}

func buildNATRedirectInsertion(redirectPort uint16, bypassCIDRs []string, bypassMark int) ([]byte, uint32, error) {
	var out []byte
	var count uint32

	if bypassMark != 0 {
		out = append(out, buildIPTMarkReturnEntry(uint32(bypassMark))...)
		count++
	}
	for _, raw := range bypassCIDRs {
		_, ipnet, err := net.ParseCIDR(raw)
		if err != nil || ipnet.IP.To4() == nil {
			continue
		}
		out = append(out, buildIPTReturnCIDREntry(ipnet)...)
		count++
	}
	out = append(out, buildIPTRedirectTCPEntry(redirectPort)...)
	count++
	return out, count, nil
}

func buildIPTMarkReturnEntry(mark uint32) []byte {
	total := iptEntrySize + xtMarkMatchSize + xtStandardTargetSize
	buf := make([]byte, total)
	putLE16(buf[88:90], iptEntrySize+xtMarkMatchSize)
	putLE16(buf[90:92], uint16(total))

	m := buf[iptEntrySize:]
	putLE16(m[0:2], xtMarkMatchSize)
	copy(m[2:31], "mark")
	m[31] = 1
	putLE32(m[32:36], mark)
	putLE32(m[36:40], 0xFFFFFFFF)
	m[40] = 0

	t := buf[iptEntrySize+xtMarkMatchSize:]
	putLE16(t[0:2], xtStandardTargetSize)
	verdict := int32(iptVerdictReturn)
	putLE32(t[32:36], uint32(verdict))
	return buf
}

func applyIPTReplace(fd int, info iptGetInfo, entryTable []byte, oldCounters uint32) error {
	if oldCounters == 0 {
		oldCounters = 1
	}
	counters := make([]xtCounters, oldCounters)
	hdrSize := int(unsafe.Sizeof(iptReplaceHeader{}))
	payload := make([]byte, hdrSize+len(entryTable))

	hdr := (*iptReplaceHeader)(unsafe.Pointer(&payload[0]))
	hdr.Name = info.Name
	hdr.ValidHooks = info.ValidHooks
	hdr.NumEntries = info.NumEntries
	hdr.Size = info.Size
	hdr.HookEntry = info.HookEntry
	hdr.Underflow = info.Underflow
	hdr.NumCounters = oldCounters
	hdr.Counters = uintptr(unsafe.Pointer(&counters[0]))
	copy(payload[hdrSize:], entryTable)

	if _, _, errno := syscall.Syscall6(
		syscall.SYS_SETSOCKOPT,
		uintptr(fd),
		uintptr(syscall.SOL_IP),
		uintptr(iptSoSetReplace),
		uintptr(unsafe.Pointer(&payload[0])),
		uintptr(len(payload)),
		0,
	); errno != 0 {
		return wrapErr("IPT_SO_SET_REPLACE(nat)", errno)
	}
	return nil
}
