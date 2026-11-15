package mux

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// RPCOpOpenURL opens an http/https URL in Android Custom Tabs (Guest to Host).
	RPCOpOpenURL uint8 = 1
	// RPCOpClipboardWrite writes UTF-8 text <= 1 MiB to Android clipboard (Guest to Host).
	RPCOpClipboardWrite uint8 = 2
	// RPCOpClipboardRead reads Android clipboard under host policy (Guest to Host).
	RPCOpClipboardRead uint8 = 3
	// RPCOpShareFile streams a guest file into Android share sheet (Guest to Host).
	RPCOpShareFile uint8 = 4
	// RPCOpVolumeFSOp runs confined filesystem operations on mounted roots (Host to Guest).
	RPCOpVolumeFSOp uint8 = 5

	// DefaultRPCSocketPath is the guest Unix socket exposed by PID 1.
	DefaultRPCSocketPath = "/run/termvr-agent/rpc.sock"
	// MaxClipboardBytes caps CLIPBOARD_WRITE payloads at 1 MiB.
	MaxClipboardBytes = 1024 * 1024
)

var (
	ErrInvalidURLScheme  = errors.New("only http and https URLs are allowed")
	ErrClipboardTooLarge = errors.New("clipboard payload exceeds 1 MiB")
	ErrPathOutsideRoot   = errors.New("path escapes confined volume root")
)

func init() {
	defaultVolumeHandlerFactory = func(roots []string) InboundRPCHandler {
		return NewVolumeRPCHandler(roots)
	}
}

// ShareFileHeader precedes raw file bytes in SHARE_FILE (op 4).
type ShareFileHeader struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
}

// VolumeFSRequest describes one VOLUME_FS_OP (op 5) request header line.
type VolumeFSRequest struct {
	Op        string `json:"op"`
	Root      string `json:"root"`
	Path      string `json:"path"`
	Offset    int64  `json:"offset,omitempty"`
	Length    int64  `json:"length,omitempty"`
	Truncate  bool   `json:"truncate,omitempty"`
	Mode      uint32 `json:"mode,omitempty"`
	Recursive bool   `json:"recursive,omitempty"`
}

// VolumeDirEntry describes one entry returned by VOLUME_FS_OP readdir.
type VolumeDirEntry struct {
	Name          string `json:"name"`
	IsDir         bool   `json:"isDir"`
	IsSymlink     bool   `json:"isSymlink"`
	Size          int64  `json:"size"`
	Mode          uint32 `json:"mode"`
	ModTimeUnixMs int64  `json:"modTimeUnixMs"`
}

// VolumeReadDirResult wraps directory entries for VOLUME_FS_OP readdir.
type VolumeReadDirResult struct {
	Entries []VolumeDirEntry `json:"entries"`
}

// VolumeStatResult describes stat output for VOLUME_FS_OP stat.
type VolumeStatResult struct {
	Exists        bool   `json:"exists"`
	Name          string `json:"name"`
	IsDir         bool   `json:"isDir"`
	IsSymlink     bool   `json:"isSymlink"`
	Size          int64  `json:"size"`
	Mode          uint32 `json:"mode"`
	ModTimeUnixMs int64  `json:"modTimeUnixMs"`
}

// VolumeWriteResult reports bytes written by VOLUME_FS_OP write.
type VolumeWriteResult struct {
	BytesWritten int64 `json:"bytesWritten"`
}

// VolumeRPCHandler executes confined VOLUME_FS_OP requests inside registered roots.
type VolumeRPCHandler struct {
	roots []string
}

// NewVolumeRPCHandler constructs a handler confined to the provided volume roots.
func NewVolumeRPCHandler(roots []string) *VolumeRPCHandler {
	cleanRoots := make([]string, 0, len(roots))
	for _, r := range roots {
		r = asciiTrimSpace(r)
		if r == "" {
			continue
		}
		abs, err := filepath.Abs(r)
		if err != nil {
			continue
		}
		if resolved, err := filepath.EvalSymlinks(abs); err == nil {
			abs = resolved
		}
		cleanRoots = append(cleanRoots, filepath.Clean(abs))
	}
	return &VolumeRPCHandler{roots: cleanRoots}
}

//go:noinline
func sendRPCFail(r io.Reader, sendResp func(uint8, []byte, bool) error, st uint8, msg string) {
	if r != nil {
		_, _ = copyStream(io.Discard, r)
	}
	_ = sendResp(st, []byte(msg), true)
}

//go:noinline
func sendRPCErr(r io.Reader, sendResp func(uint8, []byte, bool) error, st uint8, err error) {
	sendRPCFail(r, sendResp, st, err.Error())
}

// HandleHostRPC dispatches inbound Host-to-Guest RPC requests.
func (h *VolumeRPCHandler) HandleHostRPC(
	_ context.Context,
	_ uint32,
	op uint8,
	reqBody io.Reader,
	sendResp func(status uint8, chunk []byte, fin bool) error,
) {
	if op != RPCOpVolumeFSOp {
		sendRPCFail(nil, sendResp, RPCStatusDenied, "unsupported host-to-guest rpc op")
		return
	}

	br := newBufReader(reqBody, 64*1024)
	hdrLine, err := br.ReadLine(64 * 1024)
	if err != nil && !errors.Is(err, io.EOF) {
		sendRPCErr(nil, sendResp, RPCStatusBadRequest, err)
		return
	}
	hdrLine = trimWSBytes(hdrLine)
	if len(hdrLine) == 0 {
		sendRPCFail(nil, sendResp, RPCStatusBadRequest, "missing volume fs json header")
		return
	}
	req, err := decodeVolumeFSRequest(hdrLine)
	if err != nil {
		sendRPCFail(nil, sendResp, RPCStatusBadRequest, "invalid json header: "+err.Error())
		return
	}

	switch req.Op {
	case "readdir":
		h.handleReadDir(req, sendResp)
	case "stat":
		h.handleStat(req, sendResp)
	case "read":
		h.handleRead(req, sendResp)
	case "write":
		h.handleWrite(req, br, sendResp)
	case "mkdir":
		h.handleMkdir(req, sendResp)
	case "unlink":
		h.handleUnlink(req, sendResp)
	default:
		sendRPCFail(nil, sendResp, RPCStatusBadRequest, "unknown volume fs op "+req.Op)
	}
}

func decodeVolumeFSRequest(raw []byte) (VolumeFSRequest, error) {
	var req VolumeFSRequest
	p := jsonParser{src: raw}
	has, err := p.beginObj()
	if err != nil {
		return VolumeFSRequest{}, err
	}
	for first := true; has; {
		key, more, err := p.nextObjKey(&first)
		if err != nil {
			return VolumeFSRequest{}, err
		}
		if !more {
			break
		}
		switch key {
		case "op":
			req.Op, err = p.readString()
		case "root":
			req.Root, err = p.readString()
		case "path":
			req.Path, err = p.readString()
		case "offset":
			req.Offset, err = p.readInt64()
		case "length":
			req.Length, err = p.readInt64()
		case "truncate":
			req.Truncate, err = p.readBool()
		case "mode":
			var v uint64
			v, err = p.readUint64()
			req.Mode = uint32(v)
		case "recursive":
			req.Recursive, err = p.readBool()
		default:
			err = p.skipValue()
		}
		if err != nil {
			return VolumeFSRequest{}, err
		}
	}
	if err := p.finish(); err != nil {
		return VolumeFSRequest{}, err
	}
	return req, nil
}

func (h *VolumeRPCHandler) resolveRoot(requested string) (string, error) {
	if len(h.roots) == 0 {
		return "", ErrPathOutsideRoot
	}
	if asciiTrimSpace(requested) == "" {
		if len(h.roots) == 1 {
			return h.roots[0], nil
		}
		return "", ErrPathOutsideRoot
	}
	abs, err := filepath.Abs(requested)
	if err != nil {
		return "", ErrPathOutsideRoot
	}
	if resolved, err := filepath.EvalSymlinks(abs); err == nil {
		abs = resolved
	}
	abs = filepath.Clean(abs)
	if !containsStr(h.roots, abs) {
		return "", ErrPathOutsideRoot
	}
	return abs, nil
}

//go:noinline
func isWithinRoot(root, candidate string) bool {
	if candidate == root {
		return true
	}
	if root == string(filepath.Separator) {
		return strings.HasPrefix(candidate, root)
	}
	return strings.HasPrefix(candidate, root+string(filepath.Separator))
}

func (h *VolumeRPCHandler) resolveConfinedPath(rootReq, relReq string, followFinal bool) (canonRoot, target string, status uint8, err error) {
	canonRoot, err = h.resolveRoot(rootReq)
	if err != nil {
		return "", "", RPCStatusDenied, err
	}
	if strings.IndexByte(relReq, 0) >= 0 {
		return "", "", RPCStatusBadRequest, errStr("null byte in path")
	}
	if relReq == "" {
		relReq = "."
	}
	if filepath.IsAbs(relReq) {
		return "", "", RPCStatusDenied, ErrPathOutsideRoot
	}
	cleanRel := filepath.Clean(relReq)
	if cleanRel == ".." || strings.HasPrefix(cleanRel, ".."+string(filepath.Separator)) {
		return "", "", RPCStatusDenied, ErrPathOutsideRoot
	}
	joined := filepath.Join(canonRoot, cleanRel)
	if !isWithinRoot(canonRoot, joined) {
		return "", "", RPCStatusDenied, ErrPathOutsideRoot
	}

	// Verify every existing ancestor component stays confined inside canonRoot.
	cur := canonRoot
	sep := string(filepath.Separator)
	for rest := cleanRel; rest != ""; {
		var part string
		part, rest, _ = strings.Cut(rest, sep)
		if part == "." || part == "" {
			continue
		}
		cur = filepath.Join(cur, part)
		linfo, lerr := os.Lstat(cur)
		if lerr != nil {
			if os.IsNotExist(lerr) {
				break
			}
			return "", "", RPCStatusError, lerr
		}
		isLast := rest == ""
		if linfo.Mode()&os.ModeSymlink != 0 {
			if isLast && !followFinal {
				// Unlink removes the symlink entry itself rather than following it.
				break
			}
			resolved, rerr := filepath.EvalSymlinks(cur)
			if rerr != nil {
				if os.IsNotExist(rerr) {
					return "", "", RPCStatusDenied, ErrPathOutsideRoot
				}
				return "", "", RPCStatusDenied, rerr
			}
			if !isWithinRoot(canonRoot, resolved) {
				return "", "", RPCStatusDenied, ErrPathOutsideRoot
			}
		}
	}

	if followFinal {
		resolved, rerr := filepath.EvalSymlinks(joined)
		if rerr == nil {
			if !isWithinRoot(canonRoot, resolved) {
				return "", "", RPCStatusDenied, ErrPathOutsideRoot
			}
			return canonRoot, resolved, RPCStatusOK, nil
		}
		if os.IsNotExist(rerr) {
			if _, lerr := os.Lstat(joined); lerr == nil {
				// Dangling symlink pointing outside or broken target: deny access.
				return "", "", RPCStatusDenied, ErrPathOutsideRoot
			}
			return canonRoot, joined, RPCStatusNotFound, rerr
		}
		return "", "", RPCStatusDenied, rerr
	}

	parent := filepath.Dir(joined)
	if isWithinRoot(canonRoot, parent) {
		if resolvedParent, rerr := filepath.EvalSymlinks(parent); rerr == nil {
			if !isWithinRoot(canonRoot, resolvedParent) {
				return "", "", RPCStatusDenied, ErrPathOutsideRoot
			}
		}
	}
	return canonRoot, joined, RPCStatusOK, nil
}

func (h *VolumeRPCHandler) handleReadDir(req VolumeFSRequest, sendResp func(uint8, []byte, bool) error) {
	_, target, status, err := h.resolveConfinedPath(req.Root, req.Path, true)
	if err != nil {
		sendRPCErr(nil, sendResp, status, err)
		return
	}
	dirFile, err := os.Open(target)
	if err == nil {
		var dirEntries []os.DirEntry
		dirEntries, err = dirFile.ReadDir(-1)
		_ = dirFile.Close()
		if err == nil {
			entries := make([]VolumeDirEntry, 0, len(dirEntries))
			for _, de := range dirEntries {
				info, ierr := de.Info()
				if ierr != nil {
					continue
				}
				entries = append(entries, VolumeDirEntry{
					Name:          de.Name(),
					IsDir:         info.IsDir(),
					IsSymlink:     info.Mode()&os.ModeSymlink != 0,
					Size:          info.Size(),
					Mode:          uint32(info.Mode().Perm()),
					ModTimeUnixMs: info.ModTime().UnixMilli(),
				})
			}
			for i := 1; i < len(entries); i++ {
				for j := i; j > 0 && entries[j-1].Name > entries[j].Name; j-- {
					entries[j-1], entries[j] = entries[j], entries[j-1]
				}
			}
			_ = sendResp(RPCStatusOK, encodeVolumeReadDirResult(entries), true)
			return
		}
	}
	st := RPCStatusError
	if os.IsNotExist(err) {
		st = RPCStatusNotFound
	}
	sendRPCErr(nil, sendResp, st, err)
}

//go:noinline
func appendStatFields(buf []byte, name string, isDir, isSymlink bool, size int64, mode uint32, modTime int64) []byte {
	buf = appendJSONStrKV(buf, false, "name", name)
	buf = appendJSONBoolKV(buf, true, "isDir", isDir)
	buf = appendJSONBoolKV(buf, true, "isSymlink", isSymlink)
	buf = appendJSONIntKV(buf, true, "size", size)
	buf = appendJSONUintKV(buf, true, "mode", uint64(mode))
	buf = appendJSONIntKV(buf, true, "modTimeUnixMs", modTime)
	return append(buf, '}')
}

func encodeVolumeReadDirResult(entries []VolumeDirEntry) []byte {
	buf := make([]byte, 0, 256)
	buf = append(buf, `{"entries":[`...)
	for i, e := range entries {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = append(buf, '{')
		buf = appendStatFields(buf, e.Name, e.IsDir, e.IsSymlink, e.Size, e.Mode, e.ModTimeUnixMs)
	}
	buf = append(buf, `]}`...)
	return buf
}

func encodeVolumeStatResult(st VolumeStatResult) []byte {
	buf := make([]byte, 0, 160)
	buf = append(buf, `{"exists":`...)
	buf = strconv.AppendBool(buf, st.Exists)
	buf = append(buf, ',')
	return appendStatFields(buf, st.Name, st.IsDir, st.IsSymlink, st.Size, st.Mode, st.ModTimeUnixMs)
}

func (h *VolumeRPCHandler) handleStat(req VolumeFSRequest, sendResp func(uint8, []byte, bool) error) {
	_, target, status, err := h.resolveConfinedPath(req.Root, req.Path, true)
	if err != nil {
		if status == RPCStatusNotFound {
			raw := encodeVolumeStatResult(VolumeStatResult{Exists: false, Name: filepath.Base(req.Path)})
			_ = sendResp(RPCStatusOK, raw, true)
			return
		}
		sendRPCErr(nil, sendResp, status, err)
		return
	}
	info, err := os.Lstat(target)
	if err != nil {
		if os.IsNotExist(err) {
			raw := encodeVolumeStatResult(VolumeStatResult{Exists: false, Name: filepath.Base(target)})
			_ = sendResp(RPCStatusOK, raw, true)
			return
		}
		sendRPCErr(nil, sendResp, RPCStatusError, err)
		return
	}
	raw := encodeVolumeStatResult(VolumeStatResult{
		Exists:        true,
		Name:          info.Name(),
		IsDir:         info.IsDir(),
		IsSymlink:     info.Mode()&os.ModeSymlink != 0,
		Size:          info.Size(),
		Mode:          uint32(info.Mode().Perm()),
		ModTimeUnixMs: info.ModTime().UnixMilli(),
	})
	_ = sendResp(RPCStatusOK, raw, true)
}

func (h *VolumeRPCHandler) handleRead(req VolumeFSRequest, sendResp func(uint8, []byte, bool) error) {
	_, target, status, err := h.resolveConfinedPath(req.Root, req.Path, true)
	if err != nil {
		sendRPCErr(nil, sendResp, status, err)
		return
	}
	f, err := os.Open(target)
	if err != nil {
		st := RPCStatusError
		if os.IsNotExist(err) {
			st = RPCStatusNotFound
		}
		sendRPCErr(nil, sendResp, st, err)
		return
	}
	defer f.Close()

	if req.Offset < 0 {
		sendRPCFail(nil, sendResp, RPCStatusBadRequest, "negative read offset")
		return
	}
	if req.Offset > 0 {
		if _, err := f.Seek(req.Offset, io.SeekStart); err != nil {
			sendRPCErr(nil, sendResp, RPCStatusError, err)
			return
		}
	}

	var reader io.Reader = f
	if req.Length > 0 {
		reader = io.LimitReader(f, req.Length)
	}
	buf := make([]byte, MaxRPCChunkBytes)
	for {
		n, rerr := reader.Read(buf)
		if n > 0 {
			isEOF := errors.Is(rerr, io.EOF)
			if err := sendResp(RPCStatusOK, buf[:n], isEOF); err != nil {
				return
			}
			if isEOF {
				return
			}
		}
		if rerr != nil {
			if errors.Is(rerr, io.EOF) {
				_ = sendResp(RPCStatusOK, nil, true)
				return
			}
			sendRPCErr(nil, sendResp, RPCStatusError, rerr)
			return
		}
	}
}

func (h *VolumeRPCHandler) handleWrite(req VolumeFSRequest, r io.Reader, sendResp func(uint8, []byte, bool) error) {
	canonRoot, target, status, err := h.resolveConfinedPath(req.Root, req.Path, false)
	if err != nil {
		sendRPCErr(r, sendResp, status, err)
		return
	}
	if target == canonRoot {
		sendRPCFail(r, sendResp, RPCStatusDenied, "cannot overwrite volume root")
		return
	}
	if linfo, lerr := os.Lstat(target); lerr == nil && linfo.Mode()&os.ModeSymlink != 0 {
		sendRPCFail(r, sendResp, RPCStatusDenied, "cannot write through symlink")
		return
	}

	parent := filepath.Dir(target)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		sendRPCErr(r, sendResp, RPCStatusError, err)
		return
	}
	// Re-verify parent after MkdirAll so symlink parents cannot escape.
	resolvedParent, err := filepath.EvalSymlinks(parent)
	if err != nil || !isWithinRoot(canonRoot, resolvedParent) {
		sendRPCErr(r, sendResp, RPCStatusDenied, ErrPathOutsideRoot)
		return
	}

	mode := os.FileMode(req.Mode)
	if mode == 0 {
		mode = 0o644
	}
	flags := os.O_CREATE | os.O_WRONLY
	if req.Truncate && req.Offset == 0 {
		flags |= os.O_TRUNC
	}
	f, err := os.OpenFile(filepath.Join(resolvedParent, filepath.Base(target)), flags, mode)
	if err != nil {
		sendRPCErr(r, sendResp, RPCStatusError, err)
		return
	}
	if req.Offset > 0 {
		if _, err := f.Seek(req.Offset, io.SeekStart); err != nil {
			_ = f.Close()
			sendRPCErr(r, sendResp, RPCStatusError, err)
			return
		}
	}
	written, copyErr := copyStream(f, r)
	closeErr := f.Close()
	if copyErr != nil {
		sendRPCErr(nil, sendResp, RPCStatusError, copyErr)
		return
	}
	if closeErr != nil {
		sendRPCErr(nil, sendResp, RPCStatusError, closeErr)
		return
	}
	raw := append([]byte(`{"bytesWritten":`), strconv.AppendInt(nil, written, 10)...)
	raw = append(raw, '}')
	_ = sendResp(RPCStatusOK, raw, true)
}

func (h *VolumeRPCHandler) handleMkdir(req VolumeFSRequest, sendResp func(uint8, []byte, bool) error) {
	canonRoot, target, status, err := h.resolveConfinedPath(req.Root, req.Path, false)
	if err != nil {
		sendRPCErr(nil, sendResp, status, err)
		return
	}
	if target == canonRoot {
		_ = sendResp(RPCStatusOK, []byte("{}"), true)
		return
	}
	mode := os.FileMode(req.Mode)
	if mode == 0 {
		mode = 0o755
	}
	if err := os.MkdirAll(target, mode); err != nil {
		sendRPCErr(nil, sendResp, RPCStatusError, err)
		return
	}
	_ = sendResp(RPCStatusOK, []byte("{}"), true)
}

func (h *VolumeRPCHandler) handleUnlink(req VolumeFSRequest, sendResp func(uint8, []byte, bool) error) {
	canonRoot, target, status, err := h.resolveConfinedPath(req.Root, req.Path, false)
	if err != nil {
		sendRPCErr(nil, sendResp, status, err)
		return
	}
	if target == canonRoot {
		sendRPCFail(nil, sendResp, RPCStatusDenied, "cannot unlink volume root")
		return
	}
	if req.Recursive {
		err = os.RemoveAll(target)
	} else {
		err = os.Remove(target)
	}
	if err != nil {
		st := RPCStatusError
		if os.IsNotExist(err) {
			st = RPCStatusNotFound
		}
		sendRPCErr(nil, sendResp, st, err)
		return
	}
	_ = sendResp(RPCStatusOK, []byte("{}"), true)
}

// RPCSocketServer exposes Host-bound RPC operations over a guest Unix domain socket.
type RPCSocketServer struct {
	ln     net.Listener
	sess   *Session
	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
	mu     sync.Mutex // guards conns and closed
	conns  map[net.Conn]struct{}
	closed bool
}

// StartRPCSocketServer binds sockPath (0666 inside sandbox) and forwards calls over sess.
func StartRPCSocketServer(ctx context.Context, sockPath string, sess *Session) (*RPCSocketServer, error) {
	if sockPath == "" {
		sockPath = DefaultRPCSocketPath
	}
	if err := os.MkdirAll(filepath.Dir(sockPath), 0o755); err != nil {
		return nil, wrapErr("creating rpc socket directory", err)
	}
	_ = os.Remove(sockPath)

	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: sockPath, Net: "unix"})
	if err != nil {
		return nil, wrapErr("listening on "+sockPath, err)
	}
	if err := os.Chmod(sockPath, 0o666); err != nil {
		_ = ln.Close()
		return nil, wrapErr("chmod "+sockPath, err)
	}

	srvCtx, cancel := context.WithCancel(ctx)
	s := &RPCSocketServer{
		ln:     ln,
		sess:   sess,
		ctx:    srvCtx,
		cancel: cancel,
		conns:  make(map[net.Conn]struct{}),
	}

	s.wg.Add(1)
	go s.acceptLoop()
	go func() {
		<-srvCtx.Done()
		s.closeAllConns()
	}()
	return s, nil
}

func (s *RPCSocketServer) trackConn(c net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.conns[c] = struct{}{}
	return true
}

func (s *RPCSocketServer) untrackConn(c net.Conn) {
	s.mu.Lock()
	delete(s.conns, c)
	s.mu.Unlock()
}

func (s *RPCSocketServer) closeAllConns() {
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

func (s *RPCSocketServer) acceptLoop() {
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
			s.handleConn(c)
		}(conn)
	}
}

func (s *RPCSocketServer) handleConn(c net.Conn) {
	firstFrame, err := ReadFrame(c)
	if err != nil || firstFrame.Type != FrameHostRPCReq {
		return
	}
	firstChunk, err := DecodeRPCFrame(firstFrame.Payload)
	if err != nil {
		return
	}
	op := firstChunk.Code
	localReqID := firstChunk.ReqID

	_ = s.sess.CallHostRPCStream(
		s.ctx,
		op,
		func(send func(chunk []byte, fin bool) error) error {
			cur := firstChunk
			for {
				fin := (cur.Flags & RPCFlagFIN) != 0
				if err := send(cur.Data, fin); err != nil {
					return err
				}
				if fin {
					return nil
				}
				nextFrame, err := ReadFrame(c)
				if err != nil || nextFrame.Type != FrameHostRPCReq {
					return io.ErrUnexpectedEOF
				}
				cur, err = DecodeRPCFrame(nextFrame.Payload)
				if err != nil {
					return err
				}
			}
		},
		func(status uint8, chunk []byte, fin bool) error {
			var flags uint8
			if fin {
				flags = RPCFlagFIN
			}
			raw, err := EncodeRPCFrame(RPCChunk{
				ReqID: localReqID,
				Code:  status,
				Flags: flags,
				Data:  chunk,
			})
			if err != nil {
				return err
			}
			return WriteFrame(c, Frame{
				Type:     FrameHostRPCResp,
				StreamID: 0,
				Payload:  raw,
			})
		},
	)
}

// Close stops the Unix RPC listener and waits for active handlers.
func (s *RPCSocketServer) Close() error {
	s.cancel()
	s.closeAllConns()
	s.wg.Wait()
	return nil
}

// SetupBinWrappers installs 0755 helper links for termvr-open, termvr-copy, termvr-paste, and termvr-share.
func SetupBinWrappers(binDir, agentExecutable string) error {
	if binDir == "" {
		binDir = "/run/termvr-agent/bin"
	}
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		return wrapErr("creating wrapper dir "+binDir, err)
	}
	if err := os.Chmod(binDir, 0o755); err != nil {
		return err
	}
	if agentExecutable == "" {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		agentExecutable = exe
	}
	tools := []string{"termvr-open", "termvr-copy", "termvr-paste", "termvr-share"}
	for _, name := range tools {
		targetPath := filepath.Join(binDir, name)
		_ = os.Remove(targetPath)
		if err := os.Symlink(agentExecutable, targetPath); err == nil {
			continue
		}
		// Fallback wrapper script if symlinks are unsupported on the mount.
		sub := strings.TrimPrefix(name, "termvr-")
		script := "#!/bin/sh\nexec " + strconv.Quote(agentExecutable) + " " + sub + " \"$@\"\n"
		if err := os.WriteFile(targetPath, []byte(script), 0o755); err != nil {
			return wrapErr("creating wrapper "+targetPath, err)
		}
		if err := os.Chmod(targetPath, 0o755); err != nil {
			return err
		}
	}
	return nil
}

func validateHTTPURL(raw string) (string, error) {
	u := asciiTrimSpace(raw)
	if u == "" || hasCtrlOrWS(u) {
		return "", ErrInvalidURLScheme
	}
	var rest string
	switch {
	case strings.HasPrefix(u, "https://"):
		rest = u[len("https://"):]
	case strings.HasPrefix(u, "http://"):
		rest = u[len("http://"):]
	default:
		return "", ErrInvalidURLScheme
	}
	host := rest
	if idx := strings.IndexAny(rest, "/?#"); idx >= 0 {
		host = rest[:idx]
	}
	if host == "" {
		return "", ErrInvalidURLScheme
	}
	return u, nil
}

// RunCLITool executes termvr-open, termvr-copy, termvr-paste, or termvr-share via sockPath.
func RunCLITool(toolName string, args []string, stdin io.Reader, stdout, stderr io.Writer, sockPath string) error {
	if sockPath == "" {
		if sockPath = os.Getenv("TERMVR_AGENT_SOCK"); sockPath == "" {
			sockPath = DefaultRPCSocketPath
		}
	}
	base := filepath.Base(toolName)
	switch base {
	case "termvr-open", "open":
		if len(args) != 1 {
			return errStr("usage: " + base + " <http|https url>")
		}
		cleanURL, err := validateHTTPURL(args[0])
		if err != nil {
			return err
		}
		_, err = callSocketRPC(sockPath, RPCOpOpenURL, []byte(cleanURL), nil, io.Discard)
		return err

	case "termvr-copy", "copy":
		var payload []byte
		if len(args) > 0 {
			for i, a := range args {
				if i > 0 {
					payload = append(payload, ' ')
				}
				payload = append(payload, a...)
			}
		} else {
			var err error
			if payload, err = io.ReadAll(io.LimitReader(stdin, MaxClipboardBytes+1)); err != nil {
				return err
			}
		}
		if len(payload) > MaxClipboardBytes {
			return ErrClipboardTooLarge
		}
		_, err := callSocketRPC(sockPath, RPCOpClipboardWrite, payload, nil, io.Discard)
		return err

	case "termvr-paste", "paste":
		_, err := callSocketRPC(sockPath, RPCOpClipboardRead, nil, nil, stdout)
		return err

	case "termvr-share", "share":
		if len(args) != 1 {
			return errStr("usage: " + base + " <file>")
		}
		f, err := os.Open(args[0])
		if err != nil {
			return err
		}
		defer f.Close()
		info, err := f.Stat()
		if err != nil {
			return err
		}
		if info.IsDir() {
			return errStr(args[0] + " is a directory")
		}
		hdr := append([]byte(nil), `{"name":`...)
		hdr = appendJSONString(hdr, filepath.Base(args[0]))
		hdr = append(hdr, `,"size":`...)
		hdr = strconv.AppendInt(hdr, info.Size(), 10)
		hdr = append(hdr, '}', '\n')
		_, err = callSocketRPC(sockPath, RPCOpShareFile, hdr, f, io.Discard)
		return err

	default:
		return errStr("unknown cli tool " + toolName)
	}
}

func callSocketRPC(sockPath string, op uint8, hdr []byte, body io.Reader, respOut io.Writer) (uint8, error) {
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: sockPath, Net: "unix"})
	if err != nil {
		return RPCStatusError, wrapErr("connecting to "+sockPath, err)
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	defer conn.Close()

	if body == nil {
		if err := sendStreamedChunksOverConn(conn, 1, op, hdr, true); err != nil {
			return RPCStatusError, err
		}
	} else {
		if len(hdr) > 0 {
			if err := sendStreamedChunksOverConn(conn, 1, op, hdr, false); err != nil {
				return RPCStatusError, err
			}
		}
		buf := make([]byte, MaxRPCChunkBytes)
		for {
			n, rerr := body.Read(buf)
			eof := errors.Is(rerr, io.EOF)
			if n > 0 {
				if err := sendStreamedChunksOverConn(conn, 1, op, buf[:n], eof); err != nil {
					return RPCStatusError, err
				}
				if eof {
					break
				}
			}
			if rerr != nil {
				if eof {
					if err := sendStreamedChunksOverConn(conn, 1, op, nil, true); err != nil {
						return RPCStatusError, err
					}
					break
				}
				return RPCStatusError, rerr
			}
		}
	}

	var errText []byte
	for {
		f, err := ReadFrame(conn)
		if err != nil {
			return RPCStatusError, err
		}
		if f.Type != FrameHostRPCResp {
			continue
		}
		ch, err := DecodeRPCFrame(f.Payload)
		if err != nil {
			return RPCStatusError, err
		}
		if ch.Code == RPCStatusOK {
			if len(ch.Data) > 0 && respOut != nil {
				if _, werr := respOut.Write(ch.Data); werr != nil {
					return RPCStatusError, werr
				}
			}
		} else {
			errText = append(errText, ch.Data...)
		}
		if (ch.Flags & RPCFlagFIN) != 0 {
			if ch.Code != RPCStatusOK {
				msg := asciiTrimSpace(string(errText))
				if msg == "" {
					msg = "host rpc failed with status " + strconv.Itoa(int(ch.Code))
				}
				return ch.Code, errStr(msg)
			}
			return ch.Code, nil
		}
	}
}

func sendStreamedChunksOverConn(w io.Writer, reqID uint32, op uint8, data []byte, fin bool) error {
	if len(data) == 0 {
		var flags uint8
		if fin {
			flags = RPCFlagFIN
		}
		raw, err := EncodeRPCFrame(RPCChunk{ReqID: reqID, Code: op, Flags: flags})
		if err != nil {
			return err
		}
		return WriteFrame(w, Frame{Type: FrameHostRPCReq, StreamID: 0, Payload: raw})
	}
	for len(data) > 0 {
		take := len(data)
		if take > MaxRPCChunkBytes {
			take = MaxRPCChunkBytes
		}
		part := data[:take]
		data = data[take:]
		var flags uint8
		if fin && len(data) == 0 {
			flags = RPCFlagFIN
		}
		raw, err := EncodeRPCFrame(RPCChunk{ReqID: reqID, Code: op, Flags: flags, Data: part})
		if err != nil {
			return err
		}
		if err := WriteFrame(w, Frame{Type: FrameHostRPCReq, StreamID: 0, Payload: raw}); err != nil {
			return err
		}
	}
	return nil
}
