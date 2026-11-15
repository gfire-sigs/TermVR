package mux

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

const (
	// FrameMagic identifies TermVR mux frames on the binary wire ("TV").
	FrameMagic uint16 = 0x5456
	// ProtocolVersion identifies version 1 of the binary mux wire format.
	ProtocolVersion uint8 = 0x01
	// HeaderLength is the fixed byte size of every mux frame header.
	HeaderLength = 12
	// MaxPayloadLength caps a single frame body at 64 KiB.
	MaxPayloadLength = 65536
	// InitialStreamWindow sets the 256 KiB per-stream sliding window credit.
	InitialStreamWindow uint32 = 256 * 1024
	// MaxStreamWindow bounds cumulative window credit to detect overflow faults.
	MaxStreamWindow uint64 = 64 * 1024 * 1024
	// MaxRPCChunkBytes caps each HOST_RPC_REQ or HOST_RPC_RESP data chunk at 32 KiB.
	MaxRPCChunkBytes = 32 * 1024
	// RPCFrameHeaderLength is the 6-byte header inside HOST_RPC frames.
	RPCFrameHeaderLength = 6
	// GuestStreamIDBase separates guest-allocated proxy stream IDs from host PTY IDs.
	GuestStreamIDBase uint32 = 0x40000000
	// AgentVersion reports the semantic version of termvr-agent in MUX_HELLO.
	AgentVersion = "0.1.0"
	// RPCFlagFIN marks the final chunk of an RPC request or response stream.
	RPCFlagFIN uint8 = 0x01
	// RPCStatusOK indicates a completed RPC request.
	RPCStatusOK uint8 = 0x00
	// RPCStatusBadRequest indicates invalid RPC input parameters.
	RPCStatusBadRequest uint8 = 0x01
	// RPCStatusDenied indicates policy or confinement rejection.
	RPCStatusDenied uint8 = 0x02
	// RPCStatusNotFound indicates a missing target path.
	RPCStatusNotFound uint8 = 0x03
	// RPCStatusError indicates an internal or I/O error during RPC execution.
	RPCStatusError uint8 = 0x04
	// ProxyStatusOK confirms that the host connected the requested proxy stream.
	ProxyStatusOK uint8 = 0x00
	// ProxyStatusDirectBypass instructs the guest to dial the target directly.
	ProxyStatusDirectBypass uint8 = 0xFE

	linuxClockTicksPerSec = 100
	nanosPerClockTick     = 1_000_000_000 / linuxClockTicksPerSec
)

// FrameType enumerates the 16 wire opcodes defined in Design 0001 Section 15.4.
type FrameType uint8

const (
	FrameMuxHello        FrameType = 0x01
	FrameOpenPTY         FrameType = 0x02
	FrameOpenPTYAck      FrameType = 0x03
	FramePTYData         FrameType = 0x04
	FramePTYResize       FrameType = 0x05
	FramePTYWindowUpdate FrameType = 0x06
	FramePTYSignal       FrameType = 0x07
	FramePTYExit         FrameType = 0x08
	FrameTelemetryReq    FrameType = 0x09
	FrameTelemetryResp   FrameType = 0x0A
	FrameHostRPCReq      FrameType = 0x0B
	FrameHostRPCResp     FrameType = 0x0C
	FrameMuxResync       FrameType = 0x0D
	FrameMuxResyncAck    FrameType = 0x0E
	FrameProxyConnect    FrameType = 0x0F
	FrameProxyConnectAck FrameType = 0x10
)

var (
	ErrInvalidMagic         = errors.New("invalid mux frame magic")
	ErrUnsupportedVersion   = errors.New("unsupported mux protocol version")
	ErrUnknownFrameType     = errors.New("unknown mux frame type")
	ErrPayloadTooLarge      = errors.New("mux payload exceeds 65536 bytes")
	ErrInvalidStreamID      = errors.New("invalid stream id for frame type")
	ErrInvalidPayload       = errors.New("malformed mux frame payload")
	ErrInvalidWindowDelta   = errors.New("window update delta must be positive")
	ErrFlowControlViolation = errors.New("stream exceeded sliding window credit")
	ErrRPCChunkTooLarge     = errors.New("rpc chunk exceeds 32768 bytes")
	ErrDirectBypass         = errors.New("host requested direct guest dial")
	ErrProxyStreamReset     = errors.New("proxy stream reset by peer")
)

// Frame represents one decoded or outgoing binary mux frame.
type Frame struct {
	Type     FrameType
	StreamID uint32
	Payload  []byte
}

// ValidateFrameTypeAndStream checks opcode bounds and stream ID rules.
func ValidateFrameTypeAndStream(ft FrameType, streamID uint32) error {
	if ft < FrameMuxHello || ft > FrameProxyConnectAck {
		return wrapErr("0x"+strconv.FormatUint(uint64(ft), 16), ErrUnknownFrameType)
	}
	switch ft {
	case FrameMuxHello, FrameTelemetryReq, FrameTelemetryResp,
		FrameHostRPCReq, FrameHostRPCResp, FrameMuxResync, FrameMuxResyncAck:
		if streamID != 0 {
			return wrapErr("frame type requires stream 0, got "+strconv.FormatUint(uint64(streamID), 10), ErrInvalidStreamID)
		}
	case FrameOpenPTY, FrameOpenPTYAck, FramePTYResize, FramePTYSignal,
		FrameProxyConnect, FrameProxyConnectAck:
		if streamID == 0 {
			return wrapErr("frame type requires stream >= 1", ErrInvalidStreamID)
		}
	case FramePTYData, FramePTYWindowUpdate, FramePTYExit:
		// Valid on stream 0 and streams >= 1.
	}
	return nil
}

// WriteFrame validates and writes one 12-byte big-endian header and payload.
func WriteFrame(w io.Writer, f Frame) error {
	if len(f.Payload) > MaxPayloadLength {
		return wrapErr(strconv.Itoa(len(f.Payload)), ErrPayloadTooLarge)
	}
	if err := ValidateFrameTypeAndStream(f.Type, f.StreamID); err != nil {
		return err
	}
	var hdr [HeaderLength]byte
	putBE16(hdr[0:2], FrameMagic)
	hdr[2] = ProtocolVersion
	hdr[3] = uint8(f.Type)
	putBE32(hdr[4:8], f.StreamID)
	putBE32(hdr[8:12], uint32(len(f.Payload)))

	if _, err := w.Write(hdr[:]); err != nil {
		return wrapErr("writing mux header", err)
	}
	if len(f.Payload) > 0 {
		if _, err := w.Write(f.Payload); err != nil {
			return wrapErr("writing mux payload", err)
		}
	}
	return nil
}

// ReadFrame reads and validates one 12-byte big-endian header and payload.
func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [HeaderLength]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}
	magic := be16(hdr[0:2])
	if magic != FrameMagic {
		return Frame{}, wrapErr("0x"+strconv.FormatUint(uint64(magic), 16), ErrInvalidMagic)
	}
	ver := hdr[2]
	if ver != ProtocolVersion {
		return Frame{}, wrapErr(strconv.Itoa(int(ver)), ErrUnsupportedVersion)
	}
	ft := FrameType(hdr[3])
	streamID := be32(hdr[4:8])
	length := be32(hdr[8:12])
	if length > MaxPayloadLength {
		return Frame{}, wrapErr(strconv.FormatUint(uint64(length), 10), ErrPayloadTooLarge)
	}
	if err := ValidateFrameTypeAndStream(ft, streamID); err != nil {
		return Frame{}, err
	}
	var payload []byte
	if length > 0 {
		payload = make([]byte, int(length))
		if _, err := io.ReadFull(r, payload); err != nil {
			if errors.Is(err, io.EOF) {
				return Frame{}, io.ErrUnexpectedEOF
			}
			return Frame{}, err
		}
	}
	return Frame{
		Type:     ft,
		StreamID: streamID,
		Payload:  payload,
	}, nil
}

// HelloPayload carries the JSON handshake on MUX_HELLO (0x01).
type HelloPayload struct {
	Protocol     int    `json:"protocol"`
	AgentVersion string `json:"agentVersion,omitempty"`
	InitDaemon   bool   `json:"initDaemon"`
}

func EncodeMuxHello(h HelloPayload) ([]byte, error) {
	if h.Protocol != int(ProtocolVersion) {
		return nil, wrapErr("unsupported hello protocol", ErrInvalidPayload)
	}
	buf := make([]byte, 1, 96)
	buf[0] = '{'
	buf = appendJSONIntKV(buf, false, "protocol", int64(h.Protocol))
	if h.AgentVersion != "" {
		buf = appendJSONStrKV(buf, true, "agentVersion", h.AgentVersion)
	}
	buf = appendJSONBoolKV(buf, true, "initDaemon", h.InitDaemon)
	buf = append(buf, '}')
	return buf, nil
}

func DecodeMuxHello(b []byte) (HelloPayload, error) {
	var h HelloPayload
	p := jsonParser{src: b}
	has, err := p.beginObj()
	for first := true; err == nil && has; {
		key, more, kerr := p.nextObjKey(&first)
		if kerr != nil || !more {
			err = kerr
			break
		}
		switch key {
		case "protocol":
			var v int64
			if v, err = p.readInt64(); err == nil {
				h.Protocol = int(v)
			}
		case "agentVersion":
			if !p.readNull() {
				h.AgentVersion, err = p.readString()
			}
		case "initDaemon":
			h.InitDaemon, err = p.readBool()
		default:
			err = p.skipValue()
		}
	}
	if err == nil {
		err = p.finish()
	}
	if err != nil {
		return HelloPayload{}, wrapErr(err.Error(), ErrInvalidPayload)
	}
	if h.Protocol != int(ProtocolVersion) {
		return HelloPayload{}, wrapErr("unsupported hello protocol", ErrUnsupportedVersion)
	}
	return h, nil
}

// WinSize encodes terminal cell and pixel dimensions for OPEN_PTY and PTY_RESIZE.
type WinSize struct {
	Rows   uint16
	Cols   uint16
	XPixel uint16
	YPixel uint16
}

func EncodeWinSize(ws WinSize) []byte {
	var b [8]byte
	putBE16(b[0:2], ws.Rows)
	putBE16(b[2:4], ws.Cols)
	putBE16(b[4:6], ws.XPixel)
	putBE16(b[6:8], ws.YPixel)
	return b[:]
}

func DecodeWinSize(b []byte) (WinSize, error) {
	if len(b) != 8 {
		return WinSize{}, wrapErr("winsize length != 8", ErrInvalidPayload)
	}
	return WinSize{
		Rows:   be16(b[0:2]),
		Cols:   be16(b[2:4]),
		XPixel: be16(b[4:6]),
		YPixel: be16(b[6:8]),
	}, nil
}

// OpenPTYSpec carries the JSON command specification inside OPEN_PTY (0x02).
type OpenPTYSpec struct {
	Argv []string          `json:"argv"`
	Cwd  string            `json:"cwd"`
	Env  map[string]string `json:"env"`
}

// OpenPTYRequest combines initial terminal dimensions and process configuration.
type OpenPTYRequest struct {
	WinSize WinSize
	Spec    OpenPTYSpec
}

func EncodeOpenPTY(req OpenPTYRequest) ([]byte, error) {
	buf := make([]byte, 8, 128)
	copy(buf[0:8], EncodeWinSize(req.WinSize))
	buf = append(buf, `{"argv":[`...)
	for i, a := range req.Spec.Argv {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = appendJSONString(buf, a)
	}
	buf = append(buf, `],"cwd":`...)
	buf = appendJSONString(buf, req.Spec.Cwd)
	buf = append(buf, `,"env":{`...)
	if len(req.Spec.Env) > 0 {
		keys := make([]string, 0, len(req.Spec.Env))
		for k := range req.Spec.Env {
			keys = append(keys, k)
		}
		sortStrings(keys)
		for i, k := range keys {
			if i > 0 {
				buf = append(buf, ',')
			}
			buf = appendJSONString(buf, k)
			buf = append(buf, ':')
			buf = appendJSONString(buf, req.Spec.Env[k])
		}
	}
	buf = append(buf, `}}`...)
	if len(buf) > MaxPayloadLength {
		return nil, ErrPayloadTooLarge
	}
	return buf, nil
}

func DecodeOpenPTY(b []byte) (OpenPTYRequest, error) {
	if len(b) < 8 {
		return OpenPTYRequest{}, wrapErr("open_pty shorter than 8 bytes", ErrInvalidPayload)
	}
	ws, err := DecodeWinSize(b[0:8])
	if err != nil {
		return OpenPTYRequest{}, err
	}
	var spec OpenPTYSpec
	if len(trimWSBytes(b[8:])) > 0 {
		p := jsonParser{src: b[8:]}
		if err := decodeOpenPTYSpec(&p, &spec); err != nil {
			return OpenPTYRequest{}, wrapErr("open_pty json: "+err.Error(), ErrInvalidPayload)
		}
		if err := p.finish(); err != nil {
			return OpenPTYRequest{}, wrapErr("open_pty json: "+err.Error(), ErrInvalidPayload)
		}
	}
	return OpenPTYRequest{WinSize: ws, Spec: spec}, nil
}

func decodeOpenPTYSpec(p *jsonParser, spec *OpenPTYSpec) error {
	has, err := p.beginObj()
	if err != nil || !has {
		return err
	}
	for first := true; ; {
		key, more, err := p.nextObjKey(&first)
		if err != nil || !more {
			return err
		}
		switch key {
		case "argv":
			if !p.readNull() {
				ah, err := p.beginArr()
				if err != nil {
					return err
				}
				for af := true; ah; {
					moreA, err := p.nextArrElem(&af)
					if err != nil {
						return err
					}
					if !moreA {
						break
					}
					s, err := p.readString()
					if err != nil {
						return err
					}
					spec.Argv = append(spec.Argv, s)
				}
			}
		case "cwd":
			if !p.readNull() {
				s, err := p.readString()
				if err != nil {
					return err
				}
				spec.Cwd = s
			}
		case "env":
			if !p.readNull() {
				spec.Env = make(map[string]string)
				eh, err := p.beginObj()
				if err != nil {
					return err
				}
				for ef := true; eh; {
					ek, moreE, err := p.nextObjKey(&ef)
					if err != nil {
						return err
					}
					if !moreE {
						break
					}
					ev, err := p.readString()
					if err != nil {
						return err
					}
					spec.Env[ek] = ev
				}
			}
		default:
			if err := p.skipValue(); err != nil {
				return err
			}
		}
	}
}

// OpenPTYAck reports status (0 = ok) and optional UTF-8 error text on 0x03.
type OpenPTYAck struct {
	Status uint8
	Error  string
}

//go:noinline
func EncodeOpenPTYAck(ack OpenPTYAck) []byte {
	if ack.Status == 0 || ack.Error == "" {
		return []byte{ack.Status}
	}
	errBytes := []byte(ack.Error)
	if len(errBytes) > MaxPayloadLength-1 {
		errBytes = errBytes[:MaxPayloadLength-1]
	}
	out := make([]byte, 1+len(errBytes))
	out[0] = ack.Status
	copy(out[1:], errBytes)
	return out
}

func DecodeOpenPTYAck(b []byte) (OpenPTYAck, error) {
	if len(b) < 1 {
		return OpenPTYAck{}, wrapErr("empty open_pty_ack", ErrInvalidPayload)
	}
	return OpenPTYAck{
		Status: b[0],
		Error:  string(b[1:]),
	}, nil
}

//go:noinline
func EncodeWindowUpdate(delta uint32) []byte {
	var b [4]byte
	putBE32(b[0:4], delta)
	return b[:]
}

func DecodeWindowUpdate(b []byte) (uint32, error) {
	if len(b) != 4 {
		return 0, wrapErr("window_update length != 4", ErrInvalidPayload)
	}
	delta := be32(b[0:4])
	if delta == 0 {
		return 0, ErrInvalidWindowDelta
	}
	return delta, nil
}

func EncodePTYSignal(sig uint8) []byte {
	return []byte{sig}
}

func DecodePTYSignal(b []byte) (uint8, error) {
	if len(b) != 1 {
		return 0, wrapErr("pty_signal length != 1", ErrInvalidPayload)
	}
	return b[0], nil
}

//go:noinline
func EncodePTYExit(code uint8) []byte {
	return []byte{code}
}

func DecodePTYExit(b []byte) (uint8, error) {
	if len(b) != 1 {
		return 0, wrapErr("pty_exit length != 1", ErrInvalidPayload)
	}
	return b[0], nil
}

// TelemetrySnapshot holds guest resource telemetry returned on 0x0A.
type TelemetrySnapshot struct {
	CPUNanos    uint64   `json:"cpuNanos"`
	RSSBytes    uint64   `json:"rssBytes"`
	ProcCount   uint32   `json:"procCount"`
	ListenPorts []uint16 `json:"listenPorts"`
}

func EncodeTelemetryResp(s TelemetrySnapshot) ([]byte, error) {
	buf := make([]byte, 1, 128)
	buf[0] = '{'
	buf = appendJSONUintKV(buf, false, "cpuNanos", s.CPUNanos)
	buf = appendJSONUintKV(buf, true, "rssBytes", s.RSSBytes)
	buf = appendJSONUintKV(buf, true, "procCount", uint64(s.ProcCount))
	buf = append(buf, `,"listenPorts":[`...)
	for i, port := range s.ListenPorts {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = strconv.AppendUint(buf, uint64(port), 10)
	}
	buf = append(buf, `]}`...)
	return buf, nil
}

func DecodeTelemetryResp(b []byte) (TelemetrySnapshot, error) {
	s := TelemetrySnapshot{ListenPorts: []uint16{}}
	p := jsonParser{src: b}
	if err := decodeTelemetryJSON(&p, &s); err != nil {
		return TelemetrySnapshot{}, wrapErr("telemetry json: "+err.Error(), ErrInvalidPayload)
	}
	if err := p.finish(); err != nil {
		return TelemetrySnapshot{}, wrapErr("telemetry json: "+err.Error(), ErrInvalidPayload)
	}
	return s, nil
}

func decodeTelemetryJSON(p *jsonParser, s *TelemetrySnapshot) error {
	has, err := p.beginObj()
	if err != nil || !has {
		return err
	}
	for first := true; ; {
		key, more, err := p.nextObjKey(&first)
		if err != nil || !more {
			return err
		}
		switch key {
		case "cpuNanos":
			v, err := p.readUint64()
			if err != nil {
				return err
			}
			s.CPUNanos = v
		case "rssBytes":
			v, err := p.readUint64()
			if err != nil {
				return err
			}
			s.RSSBytes = v
		case "procCount":
			v, err := p.readUint64()
			if err != nil {
				return err
			}
			s.ProcCount = uint32(v)
		case "listenPorts":
			if !p.readNull() {
				ah, err := p.beginArr()
				if err != nil {
					return err
				}
				for af := true; ah; {
					moreA, err := p.nextArrElem(&af)
					if err != nil {
						return err
					}
					if !moreA {
						break
					}
					v, err := p.readUint64()
					if err != nil {
						return err
					}
					s.ListenPorts = append(s.ListenPorts, uint16(v))
				}
			}
		default:
			if err := p.skipValue(); err != nil {
				return err
			}
		}
	}
}

// RPCChunk encodes one HOST_RPC_REQ (0x0B) or HOST_RPC_RESP (0x0C) frame body.
type RPCChunk struct {
	ReqID uint32
	Code  uint8 // op on HOST_RPC_REQ, status on HOST_RPC_RESP
	Flags uint8
	Data  []byte
}

func EncodeRPCFrame(c RPCChunk) ([]byte, error) {
	if len(c.Data) > MaxRPCChunkBytes {
		return nil, wrapErr(strconv.Itoa(len(c.Data)), ErrRPCChunkTooLarge)
	}
	out := make([]byte, RPCFrameHeaderLength+len(c.Data))
	putBE32(out[0:4], c.ReqID)
	out[4] = c.Code
	out[5] = c.Flags
	copy(out[6:], c.Data)
	return out, nil
}

func DecodeRPCFrame(b []byte) (RPCChunk, error) {
	if len(b) < RPCFrameHeaderLength {
		return RPCChunk{}, wrapErr("rpc frame shorter than 6 bytes", ErrInvalidPayload)
	}
	data := b[RPCFrameHeaderLength:]
	if len(data) > MaxRPCChunkBytes {
		return RPCChunk{}, wrapErr(strconv.Itoa(len(data)), ErrRPCChunkTooLarge)
	}
	cp := make([]byte, len(data))
	copy(cp, data)
	return RPCChunk{
		ReqID: be32(b[0:4]),
		Code:  b[4],
		Flags: b[5],
		Data:  cp,
	}, nil
}

// OpenStreamInfo describes one live guest PTY stream during MUX_RESYNC_ACK.
type OpenStreamInfo struct {
	ID   uint32 `json:"id"`
	Rows uint16 `json:"rows"`
	Cols uint16 `json:"cols"`
}

// ResyncAckPayload lists open PTY streams after checkpoint restore on 0x0E.
type ResyncAckPayload struct {
	OpenStreams []OpenStreamInfo `json:"openStreams"`
}

func EncodeResyncAck(p ResyncAckPayload) ([]byte, error) {
	buf := make([]byte, 0, 64)
	buf = append(buf, `{"openStreams":[`...)
	for i, st := range p.OpenStreams {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = append(buf, '{')
		buf = appendJSONUintKV(buf, false, "id", uint64(st.ID))
		buf = appendJSONUintKV(buf, true, "rows", uint64(st.Rows))
		buf = appendJSONUintKV(buf, true, "cols", uint64(st.Cols))
		buf = append(buf, '}')
	}
	buf = append(buf, `]}`...)
	return buf, nil
}

func DecodeResyncAck(b []byte) (ResyncAckPayload, error) {
	res := ResyncAckPayload{OpenStreams: []OpenStreamInfo{}}
	p := jsonParser{src: b}
	if err := decodeResyncAckJSON(&p, &res); err != nil {
		return ResyncAckPayload{}, wrapErr("resync_ack json: "+err.Error(), ErrInvalidPayload)
	}
	if err := p.finish(); err != nil {
		return ResyncAckPayload{}, wrapErr("resync_ack json: "+err.Error(), ErrInvalidPayload)
	}
	return res, nil
}

func decodeResyncAckJSON(p *jsonParser, res *ResyncAckPayload) error {
	has, err := p.beginObj()
	if err != nil || !has {
		return err
	}
	for first := true; ; {
		key, more, err := p.nextObjKey(&first)
		if err != nil || !more {
			return err
		}
		if key != "openStreams" {
			if err := p.skipValue(); err != nil {
				return err
			}
			continue
		}
		if p.readNull() {
			continue
		}
		ah, err := p.beginArr()
		if err != nil {
			return err
		}
		for af := true; ah; {
			moreA, err := p.nextArrElem(&af)
			if err != nil {
				return err
			}
			if !moreA {
				break
			}
			var info OpenStreamInfo
			oh, err := p.beginObj()
			if err != nil {
				return err
			}
			for of := true; oh; {
				fk, moreO, err := p.nextObjKey(&of)
				if err != nil {
					return err
				}
				if !moreO {
					break
				}
				v, err := p.readUint64()
				if err != nil {
					return err
				}
				switch fk {
				case "id":
					info.ID = uint32(v)
				case "rows":
					info.Rows = uint16(v)
				case "cols":
					info.Cols = uint16(v)
				}
			}
			res.OpenStreams = append(res.OpenStreams, info)
		}
	}
}

// ProxyConnectRequest carries outbound target host and port on 0x0F.
type ProxyConnectRequest struct {
	Port uint16
	Host string
}

func EncodeProxyConnect(req ProxyConnectRequest) ([]byte, error) {
	host := asciiTrimSpace(req.Host)
	if host == "" || req.Port == 0 || strings.IndexByte(host, 0) >= 0 {
		return nil, wrapErr("invalid proxy target", ErrInvalidPayload)
	}
	hb := []byte(host)
	if len(hb) > MaxPayloadLength-2 {
		return nil, ErrPayloadTooLarge
	}
	out := make([]byte, 2+len(hb))
	putBE16(out[0:2], req.Port)
	copy(out[2:], hb)
	return out, nil
}

func DecodeProxyConnect(b []byte) (ProxyConnectRequest, error) {
	if len(b) < 3 {
		return ProxyConnectRequest{}, wrapErr("proxy_connect too short", ErrInvalidPayload)
	}
	port := be16(b[0:2])
	host := string(b[2:])
	if port == 0 || asciiTrimSpace(host) == "" || strings.IndexByte(host, 0) >= 0 {
		return ProxyConnectRequest{}, wrapErr("invalid proxy_connect host or port", ErrInvalidPayload)
	}
	return ProxyConnectRequest{Port: port, Host: host}, nil
}

// ProxyConnectAck reports connection result on 0x10.
type ProxyConnectAck struct {
	Status  uint8
	Message string
}

func EncodeProxyConnectAck(ack ProxyConnectAck) []byte {
	if ack.Message == "" {
		return []byte{ack.Status}
	}
	mb := []byte(ack.Message)
	if len(mb) > MaxPayloadLength-1 {
		mb = mb[:MaxPayloadLength-1]
	}
	out := make([]byte, 1+len(mb))
	out[0] = ack.Status
	copy(out[1:], mb)
	return out
}

func DecodeProxyConnectAck(b []byte) (ProxyConnectAck, error) {
	if len(b) < 1 {
		return ProxyConnectAck{}, wrapErr("empty proxy_connect_ack", ErrInvalidPayload)
	}
	return ProxyConnectAck{
		Status:  b[0],
		Message: string(b[1:]),
	}, nil
}

// ProxyConnectError surfaces a non-zero PROXY_CONNECT_ACK status from the host.
type ProxyConnectError struct {
	Status  uint8
	Message string
}

func (e *ProxyConnectError) Error() string {
	base := "proxy connect failed (status " + strconv.Itoa(int(e.Status)) + ")"
	if e.Message != "" {
		return base + ": " + e.Message
	}
	return base
}

// ReadProcTelemetry parses cpuNanos, rssBytes, procCount, and listenPorts from Linux /proc.
func ReadProcTelemetry(procRoot string) (TelemetrySnapshot, error) {
	if procRoot == "" {
		procRoot = "/proc"
	}
	f, err := os.Open(procRoot)
	if err != nil {
		return TelemetrySnapshot{ListenPorts: []uint16{}}, wrapErr("reading proc root", err)
	}
	names, err := f.Readdirnames(-1)
	_ = f.Close()
	if err != nil {
		return TelemetrySnapshot{ListenPorts: []uint16{}}, wrapErr("reading proc root", err)
	}

	pageSize := uint64(os.Getpagesize())
	var totalTicks uint64
	var totalRSSPages uint64
	var procCount uint32

	for _, name := range names {
		pid, err := strconv.Atoi(name)
		if err != nil || pid <= 0 {
			continue
		}
		statBytes, err := os.ReadFile(filepath.Join(procRoot, name, "stat"))
		if err != nil {
			continue
		}
		ticks, rssPages, ok := parseProcPIDStat(string(statBytes), pid == 1)
		if !ok {
			continue
		}
		procCount++
		totalTicks += ticks
		totalRSSPages += rssPages
	}

	var ports []uint16
	for _, rel := range []string{"net/tcp", "net/tcp6"} {
		if raw, err := os.ReadFile(filepath.Join(procRoot, rel)); err == nil {
			ports = parseProcNetTCPListenPorts(string(raw), ports)
		}
	}
	sortUint16s(ports)

	return TelemetrySnapshot{
		CPUNanos:    totalTicks * nanosPerClockTick,
		RSSBytes:    totalRSSPages * pageSize,
		ProcCount:   procCount,
		ListenPorts: ports,
	}, nil
}

func parseProcPIDStat(line string, includeChildren bool) (ticks uint64, rssPages uint64, ok bool) {
	idx := strings.LastIndexByte(line, ')')
	if idx < 0 || idx+1 >= len(line) {
		return 0, 0, false
	}
	fields := splitASCIIFields(line[idx+1:])
	// After "(comm)", fields[11]=utime, fields[12]=stime, fields[13]=cutime, fields[14]=cstime, fields[21]=rss.
	if len(fields) < 22 {
		return 0, 0, false
	}
	utime, err1 := strconv.ParseUint(fields[11], 10, 64)
	stime, err2 := strconv.ParseUint(fields[12], 10, 64)
	if err1 != nil || err2 != nil {
		return 0, 0, false
	}
	ticks = utime + stime
	if includeChildren {
		cutime, _ := strconv.ParseInt(fields[13], 10, 64)
		cstime, _ := strconv.ParseInt(fields[14], 10, 64)
		if cutime > 0 {
			ticks += uint64(cutime)
		}
		if cstime > 0 {
			ticks += uint64(cstime)
		}
	}
	rssSigned, err := strconv.ParseInt(fields[21], 10, 64)
	if err == nil && rssSigned > 0 {
		rssPages = uint64(rssSigned)
	}
	return ticks, rssPages, true
}

func parseProcNetTCPListenPorts(content string, dst []uint16) []uint16 {
	_, rest, ok := strings.Cut(content, "\n")
	if !ok {
		return dst
	}
	for rest != "" {
		var line string
		line, rest, _ = strings.Cut(rest, "\n")
		fields := splitASCIIFields(line)
		if len(fields) < 4 || !asciiEqualFold(fields[3], "0A") {
			continue
		}
		_, hexPort, found := strings.Cut(fields[1], ":")
		if !found {
			continue
		}
		if portVal, err := strconv.ParseUint(hexPort, 16, 16); err == nil && portVal > 0 {
			p := uint16(portVal)
			seen := false
			for _, cur := range dst {
				if cur == p {
					seen = true
					break
				}
			}
			if !seen {
				dst = append(dst, p)
			}
		}
	}
	return dst
}

type flowWindow struct {
	mu     sync.Mutex // guards avail, waitCh, and closed
	avail  uint32
	waitCh chan struct{}
	closed bool
}

func newFlowWindow(initial uint32) *flowWindow {
	return &flowWindow{
		avail:  initial,
		waitCh: make(chan struct{}),
	}
}

func (w *flowWindow) acquire(ctx context.Context, want int) (int, error) {
	if want <= 0 {
		return 0, nil
	}
	for {
		w.mu.Lock()
		if w.closed {
			w.mu.Unlock()
			return 0, io.ErrClosedPipe
		}
		if w.avail > 0 {
			take := want
			if take > MaxPayloadLength {
				take = MaxPayloadLength
			}
			if uint32(take) > w.avail {
				take = int(w.avail)
			}
			w.avail -= uint32(take)
			w.mu.Unlock()
			return take, nil
		}
		ch := w.waitCh
		w.mu.Unlock()

		select {
		case <-ch:
		case <-ctx.Done():
			return 0, ctx.Err()
		}
	}
}

func (w *flowWindow) grant(delta uint32) error {
	if delta == 0 {
		return ErrInvalidWindowDelta
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if uint64(w.avail)+uint64(delta) > MaxStreamWindow {
		return ErrFlowControlViolation
	}
	w.avail += delta
	if !w.closed {
		close(w.waitCh)
		w.waitCh = make(chan struct{})
	}
	return nil
}

func (w *flowWindow) reset(val uint32) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.avail = val
	if !w.closed {
		close(w.waitCh)
		w.waitCh = make(chan struct{})
	}
}

func (w *flowWindow) close() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.closed {
		w.closed = true
		close(w.waitCh)
	}
}

// InboundRPCHandler processes Host-to-Guest RPC streams on stream 0.
type InboundRPCHandler interface {
	HandleHostRPC(ctx context.Context, reqID uint32, op uint8, reqBody io.Reader, sendResp func(status uint8, chunk []byte, fin bool) error)
}

// SessionConfig configures a guest mux Session.
type SessionConfig struct {
	Reader         io.Reader
	Writer         io.Writer
	InitDaemonArgv []string
	RootDir        string
	ProcRoot       string
	BinDir         string
	VolumeRoots    []string
	PTYBackend     PTYBackend
	Reaper         *Reaper
	RPCHandler     InboundRPCHandler
}

type ptyStreamEntry struct {
	id        uint32
	pty       PTYHandle
	sendWin   *flowWindow
	inMu      sync.Mutex // guards recvAvail and inboundCh close
	recvAvail uint32
	inboundCh chan []byte
	inboundCl bool
	wsMu      sync.Mutex // guards winSize
	winSize   WinSize
	cancel    context.CancelFunc
}

type inboundRPCState struct {
	pw *io.PipeWriter
}

type pendingRPC struct {
	ch   chan RPCChunk
	done chan struct{}
}

// Session multiplexes PTYs, Stream-0 init daemons, outbound proxy tunnels, and RPCs.
type Session struct {
	cfg        SessionConfig
	ptyBackend PTYBackend
	rpcHandler InboundRPCHandler

	writeMu sync.Mutex // serializes WriteFrame calls to cfg.Writer

	mu           sync.Mutex // guards stream maps, rpc maps, stream0Win, and closed
	ptyStreams   map[uint32]*ptyStreamEntry
	proxyStreams map[uint32]*ProxyStream
	pendingRPCs  map[uint32]pendingRPC
	inboundRPCs  map[uint32]*inboundRPCState
	stream0Send  *flowWindow
	stream0Stdin io.WriteCloser
	nextProxyID  uint32
	closed       bool
	doneCh       chan struct{}

	nextRPCReqID atomic.Uint32
}

// NewSession constructs a thread-safe mux Session.
func NewSession(cfg SessionConfig) *Session {
	if cfg.RootDir == "" {
		cfg.RootDir = "/"
	}
	if cfg.ProcRoot == "" {
		cfg.ProcRoot = "/proc"
	}
	if cfg.BinDir == "" {
		cfg.BinDir = "/run/termvr-agent/bin"
	}
	backend := cfg.PTYBackend
	if backend == nil {
		backend = newDefaultPTYBackend(cfg.Reaper)
	}
	s := &Session{
		cfg:          cfg,
		ptyBackend:   backend,
		rpcHandler:   cfg.RPCHandler,
		ptyStreams:   make(map[uint32]*ptyStreamEntry),
		proxyStreams: make(map[uint32]*ProxyStream),
		pendingRPCs:  make(map[uint32]pendingRPC),
		inboundRPCs:  make(map[uint32]*inboundRPCState),
		stream0Send:  newFlowWindow(InitialStreamWindow),
		nextProxyID:  GuestStreamIDBase,
		doneCh:       make(chan struct{}),
	}
	if s.rpcHandler == nil && len(cfg.VolumeRoots) > 0 && defaultVolumeHandlerFactory != nil {
		s.rpcHandler = defaultVolumeHandlerFactory(cfg.VolumeRoots)
	}
	return s
}

var defaultVolumeHandlerFactory func(roots []string) InboundRPCHandler

// SetInboundRPCHandler configures the handler for Host-to-Guest RPC requests.
func (s *Session) SetInboundRPCHandler(h InboundRPCHandler) {
	s.mu.Lock()
	s.rpcHandler = h
	s.mu.Unlock()
}

//go:noinline
func (s *Session) writeFrame(f Frame) error {
	s.writeMu.Lock()
	err := WriteFrame(s.cfg.Writer, f)
	s.writeMu.Unlock()
	return err
}

func (s *Session) sendWindowedData(ctx context.Context, streamID uint32, win *flowWindow, data []byte) error {
	for len(data) > 0 {
		take, err := win.acquire(ctx, len(data))
		if err != nil {
			return err
		}
		chunk := data[:take]
		data = data[take:]
		if err := s.writeFrame(Frame{
			Type:     FramePTYData,
			StreamID: streamID,
			Payload:  chunk,
		}); err != nil {
			return err
		}
	}
	return nil
}

// Run executes the session handshake, optional Stream-0 daemon, and read loop.
func (s *Session) Run(ctx context.Context) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	defer s.shutdown()

	helloBytes, err := EncodeMuxHello(HelloPayload{
		Protocol:     int(ProtocolVersion),
		AgentVersion: AgentVersion,
		InitDaemon:   len(s.cfg.InitDaemonArgv) > 0,
	})
	if err != nil {
		return err
	}
	if err := s.writeFrame(Frame{
		Type:     FrameMuxHello,
		StreamID: 0,
		Payload:  helloBytes,
	}); err != nil {
		return err
	}

	var wg sync.WaitGroup
	if len(s.cfg.InitDaemonArgv) > 0 {
		if err := s.startInitDaemon(ctx, &wg); err != nil {
			errMsg := "termvr-agent: init daemon error: " + err.Error() + "\n"
			_ = s.sendWindowedData(ctx, 0, s.stream0Send, []byte(errMsg))
			_ = s.writeFrame(Frame{Type: FramePTYExit, StreamID: 0, Payload: EncodePTYExit(1)})
		}
	}

	readErrCh := make(chan error, 1)
	go func() {
		for {
			f, err := ReadFrame(s.cfg.Reader)
			if err != nil {
				readErrCh <- err
				return
			}
			if err := s.dispatchFrame(ctx, &wg, f); err != nil {
				readErrCh <- err
				return
			}
		}
	}()

	var runErr error
	select {
	case <-ctx.Done():
		runErr = ctx.Err()
	case err := <-readErrCh:
		if !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrClosedPipe) {
			runErr = err
		}
	}
	cancel()
	s.shutdown()
	wg.Wait()
	return runErr
}

func (s *Session) shutdown() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	close(s.doneCh)
	s.stream0Send.close()
	if s.stream0Stdin != nil {
		_ = s.stream0Stdin.Close()
	}

	ptys := make([]*ptyStreamEntry, 0, len(s.ptyStreams))
	for _, entry := range s.ptyStreams {
		ptys = append(ptys, entry)
	}
	clear(s.ptyStreams)

	proxies := make([]*ProxyStream, 0, len(s.proxyStreams))
	for _, ps := range s.proxyStreams {
		proxies = append(proxies, ps)
	}
	clear(s.proxyStreams)

	clear(s.pendingRPCs)
	for _, in := range s.inboundRPCs {
		_ = in.pw.CloseWithError(io.ErrClosedPipe)
	}
	clear(s.inboundRPCs)
	s.mu.Unlock()

	for _, entry := range ptys {
		entry.cancel()
		entry.sendWin.close()
		entry.closeInbound()
		_ = entry.pty.Close()
	}
	for _, ps := range proxies {
		ps.abortLocal()
	}
}

func (s *Session) startInitDaemon(ctx context.Context, wg *sync.WaitGroup) error {
	env := BuildGuestEnv(os.Environ(), nil, s.cfg.BinDir)
	cwd, err := ResolveWorkDir("", s.cfg.RootDir)
	if err != nil {
		cwd = "/"
	}
	daemon, err := s.ptyBackend.StartDaemon(s.cfg.InitDaemonArgv, cwd, env)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.stream0Stdin = daemon.Stdin()
	s.mu.Unlock()

	wg.Add(1)
	go func() {
		defer wg.Done()
		buf := make([]byte, 32*1024)
		out := daemon.Output()
		for {
			n, rerr := out.Read(buf)
			if n > 0 {
				if werr := s.sendWindowedData(ctx, 0, s.stream0Send, buf[:n]); werr != nil {
					break
				}
			}
			if rerr != nil {
				break
			}
		}
		code, _ := daemon.Wait()
		_ = s.writeFrame(Frame{
			Type:     FramePTYExit,
			StreamID: 0,
			Payload:  EncodePTYExit(code),
		})
	}()
	return nil
}

func (s *Session) dispatchFrame(ctx context.Context, wg *sync.WaitGroup, f Frame) error {
	switch f.Type {
	case FrameMuxHello:
		_, err := DecodeMuxHello(f.Payload)
		return err
	case FrameOpenPTY:
		return s.handleOpenPTY(ctx, wg, f.StreamID, f.Payload)
	case FramePTYData:
		return s.handlePTYData(f.StreamID, f.Payload)
	case FramePTYResize:
		ws, err := DecodeWinSize(f.Payload)
		if err != nil {
			return err
		}
		return s.handlePTYResize(f.StreamID, ws)
	case FramePTYWindowUpdate:
		delta, err := DecodeWindowUpdate(f.Payload)
		if err != nil {
			return err
		}
		return s.handleWindowUpdate(f.StreamID, delta)
	case FramePTYSignal:
		sig, err := DecodePTYSignal(f.Payload)
		if err != nil {
			return err
		}
		return s.handlePTYSignal(f.StreamID, sig)
	case FramePTYExit:
		code, err := DecodePTYExit(f.Payload)
		if err != nil {
			return err
		}
		s.handleInboundExit(f.StreamID, code)
		return nil
	case FrameTelemetryReq:
		if len(f.Payload) != 0 {
			return wrapErr("telemetry_req must be empty", ErrInvalidPayload)
		}
		snap, _ := ReadProcTelemetry(s.cfg.ProcRoot)
		raw, err := EncodeTelemetryResp(snap)
		if err != nil {
			return err
		}
		return s.writeFrame(Frame{Type: FrameTelemetryResp, StreamID: 0, Payload: raw})
	case FrameMuxResync:
		if len(f.Payload) != 0 {
			return wrapErr("mux_resync must be empty", ErrInvalidPayload)
		}
		return s.handleMuxResync()
	case FrameHostRPCReq:
		chunk, err := DecodeRPCFrame(f.Payload)
		if err != nil {
			return err
		}
		return s.handleInboundRPCReq(ctx, wg, chunk)
	case FrameHostRPCResp:
		chunk, err := DecodeRPCFrame(f.Payload)
		if err != nil {
			return err
		}
		s.handleInboundRPCResp(chunk)
		return nil
	case FrameProxyConnectAck:
		ack, err := DecodeProxyConnectAck(f.Payload)
		if err != nil {
			return err
		}
		s.handleProxyConnectAck(f.StreamID, ack)
		return nil
	default:
		return wrapErr("unexpected inbound frame", ErrInvalidPayload)
	}
}

func (e *ptyStreamEntry) closeInbound() {
	e.inMu.Lock()
	if !e.inboundCl {
		e.inboundCl = true
		close(e.inboundCh)
	}
	e.inMu.Unlock()
}

func (s *Session) sendPTYAck(streamID uint32, status uint8, msg string) error {
	return s.writeFrame(Frame{
		Type:     FrameOpenPTYAck,
		StreamID: streamID,
		Payload:  EncodeOpenPTYAck(OpenPTYAck{Status: status, Error: msg}),
	})
}

func (s *Session) pumpPTYInbound(wg *sync.WaitGroup, entry *ptyStreamEntry, streamID uint32) {
	defer wg.Done()
	for chunk := range entry.inboundCh {
		n, werr := entry.pty.Write(chunk)
		if n > 0 {
			entry.inMu.Lock()
			entry.recvAvail += uint32(n)
			entry.inMu.Unlock()
			_ = s.writeFrame(Frame{
				Type:     FramePTYWindowUpdate,
				StreamID: streamID,
				Payload:  EncodeWindowUpdate(uint32(n)),
			})
		}
		if werr != nil {
			return
		}
	}
}

func (s *Session) pumpPTYOutbound(wg *sync.WaitGroup, ctx context.Context, streamCancel context.CancelFunc, entry *ptyStreamEntry, streamID uint32) {
	defer wg.Done()
	defer func() {
		streamCancel()
		entry.closeInbound()
		_ = entry.pty.Close()
		s.mu.Lock()
		delete(s.ptyStreams, streamID)
		s.mu.Unlock()
	}()
	buf := make([]byte, 32*1024)
	for {
		n, rerr := entry.pty.Read(buf)
		if n > 0 {
			if werr := s.sendWindowedData(ctx, streamID, entry.sendWin, buf[:n]); werr != nil {
				return
			}
		}
		if rerr != nil {
			break
		}
	}
	exitCode, _ := entry.pty.Wait()
	_ = s.writeFrame(Frame{
		Type:     FramePTYExit,
		StreamID: streamID,
		Payload:  EncodePTYExit(exitCode),
	})
}

func (s *Session) handleOpenPTY(ctx context.Context, wg *sync.WaitGroup, streamID uint32, payload []byte) error {
	req, err := DecodeOpenPTY(payload)
	if err != nil {
		return s.sendPTYAck(streamID, 1, err.Error())
	}

	s.mu.Lock()
	_, dupPTY := s.ptyStreams[streamID]
	_, dupProxy := s.proxyStreams[streamID]
	s.mu.Unlock()
	if dupPTY || dupProxy {
		return s.sendPTYAck(streamID, 1, "stream id already active")
	}

	argv, err := ResolveShellArgv(req.Spec.Argv, s.cfg.RootDir)
	if err != nil {
		return s.sendPTYAck(streamID, 2, err.Error())
	}
	cwd, err := ResolveWorkDir(req.Spec.Cwd, s.cfg.RootDir)
	if err != nil {
		return s.sendPTYAck(streamID, 3, err.Error())
	}
	env := BuildGuestEnv(os.Environ(), req.Spec.Env, s.cfg.BinDir)

	ptyHandle, err := s.ptyBackend.OpenPTY(argv, cwd, env, req.WinSize)
	if err != nil {
		return s.sendPTYAck(streamID, 4, err.Error())
	}

	streamCtx, streamCancel := context.WithCancel(ctx)
	entry := &ptyStreamEntry{
		id:        streamID,
		pty:       ptyHandle,
		sendWin:   newFlowWindow(InitialStreamWindow),
		recvAvail: InitialStreamWindow,
		inboundCh: make(chan []byte, 64),
		winSize:   req.WinSize,
		cancel:    streamCancel,
	}

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		streamCancel()
		_ = ptyHandle.Close()
		return nil
	}
	s.ptyStreams[streamID] = entry
	s.mu.Unlock()

	if err := s.sendPTYAck(streamID, 0, ""); err != nil {
		streamCancel()
		_ = ptyHandle.Close()
		return err
	}

	wg.Add(2)
	go s.pumpPTYInbound(wg, entry, streamID)
	go s.pumpPTYOutbound(wg, streamCtx, streamCancel, entry, streamID)
	return nil
}

func (s *Session) handlePTYData(streamID uint32, payload []byte) error {
	if len(payload) == 0 {
		return nil
	}
	if streamID == 0 {
		s.mu.Lock()
		in := s.stream0Stdin
		s.mu.Unlock()
		if in != nil {
			n, _ := in.Write(payload)
			if n > 0 {
				_ = s.writeFrame(Frame{
					Type:     FramePTYWindowUpdate,
					StreamID: 0,
					Payload:  EncodeWindowUpdate(uint32(n)),
				})
			}
		}
		return nil
	}

	s.mu.Lock()
	entry := s.ptyStreams[streamID]
	ps := s.proxyStreams[streamID]
	s.mu.Unlock()

	if entry != nil {
		cp := make([]byte, len(payload))
		copy(cp, payload)
		entry.inMu.Lock()
		if uint32(len(cp)) > entry.recvAvail {
			entry.inMu.Unlock()
			return ErrFlowControlViolation
		}
		entry.recvAvail -= uint32(len(cp))
		if entry.inboundCl {
			entry.inMu.Unlock()
			return nil
		}
		select {
		case entry.inboundCh <- cp:
			entry.inMu.Unlock()
			return nil
		default:
			entry.inMu.Unlock()
			select {
			case entry.inboundCh <- cp:
				return nil
			case <-s.doneCh:
				return nil
			}
		}
	}
	if ps != nil {
		return ps.pushInboundData(payload)
	}
	return nil
}

func (s *Session) handlePTYResize(streamID uint32, ws WinSize) error {
	s.mu.Lock()
	entry := s.ptyStreams[streamID]
	s.mu.Unlock()
	if entry == nil {
		return nil
	}
	entry.wsMu.Lock()
	entry.winSize = ws
	entry.wsMu.Unlock()
	_ = entry.pty.Resize(ws)
	return nil
}

func (s *Session) handleWindowUpdate(streamID uint32, delta uint32) error {
	if streamID == 0 {
		return s.stream0Send.grant(delta)
	}
	s.mu.Lock()
	entry := s.ptyStreams[streamID]
	ps := s.proxyStreams[streamID]
	s.mu.Unlock()
	if entry != nil {
		return entry.sendWin.grant(delta)
	}
	if ps != nil {
		return ps.sendWin.grant(delta)
	}
	return nil
}

func (s *Session) handlePTYSignal(streamID uint32, sig uint8) error {
	s.mu.Lock()
	entry := s.ptyStreams[streamID]
	s.mu.Unlock()
	if entry != nil {
		_ = entry.pty.Signal(sig)
	}
	return nil
}

func (s *Session) handleInboundExit(streamID uint32, code uint8) {
	s.mu.Lock()
	ps := s.proxyStreams[streamID]
	if ps != nil {
		delete(s.proxyStreams, streamID)
	}
	entry := s.ptyStreams[streamID]
	s.mu.Unlock()

	if ps != nil {
		ps.handleRemoteExit(code)
	}
	if entry != nil {
		_ = entry.pty.Close()
	}
}

func (s *Session) handleMuxResync() error {
	s.mu.Lock()
	open := make([]OpenStreamInfo, 0, len(s.ptyStreams))
	s.stream0Send.reset(InitialStreamWindow)
	for id, entry := range s.ptyStreams {
		entry.wsMu.Lock()
		ws := entry.winSize
		entry.wsMu.Unlock()
		entry.sendWin.reset(InitialStreamWindow)
		entry.inMu.Lock()
		entry.recvAvail = InitialStreamWindow
		entry.inMu.Unlock()
		open = append(open, OpenStreamInfo{
			ID:   id,
			Rows: ws.Rows,
			Cols: ws.Cols,
		})
	}
	for _, ps := range s.proxyStreams {
		ps.sendWin.reset(InitialStreamWindow)
		ps.resetRecvWindow()
	}
	s.mu.Unlock()

	for i := 1; i < len(open); i++ {
		for j := i; j > 0 && open[j-1].ID > open[j].ID; j-- {
			open[j-1], open[j] = open[j], open[j-1]
		}
	}

	raw, err := EncodeResyncAck(ResyncAckPayload{OpenStreams: open})
	if err != nil {
		return err
	}
	return s.writeFrame(Frame{
		Type:     FrameMuxResyncAck,
		StreamID: 0,
		Payload:  raw,
	})
}

func (s *Session) handleInboundRPCReq(ctx context.Context, wg *sync.WaitGroup, chunk RPCChunk) error {
	s.mu.Lock()
	handler := s.rpcHandler
	if handler == nil {
		s.mu.Unlock()
		raw, err := EncodeRPCFrame(RPCChunk{
			ReqID: chunk.ReqID,
			Code:  RPCStatusDenied,
			Flags: RPCFlagFIN,
			Data:  []byte("no inbound rpc handler configured"),
		})
		if err != nil {
			return err
		}
		return s.writeFrame(Frame{Type: FrameHostRPCResp, StreamID: 0, Payload: raw})
	}

	state, exists := s.inboundRPCs[chunk.ReqID]
	if !exists {
		pr, pw := io.Pipe()
		state = &inboundRPCState{pw: pw}
		s.inboundRPCs[chunk.ReqID] = state
		reqID := chunk.ReqID
		op := chunk.Code

		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				_ = pr.Close()
				s.mu.Lock()
				delete(s.inboundRPCs, reqID)
				s.mu.Unlock()
			}()
			sendResp := func(status uint8, data []byte, fin bool) error {
				return s.sendRPCResponseChunks(reqID, status, data, fin)
			}
			handler.HandleHostRPC(ctx, reqID, op, pr, sendResp)
		}()
	}
	fin := (chunk.Flags & RPCFlagFIN) != 0
	if fin {
		delete(s.inboundRPCs, chunk.ReqID)
	}
	s.mu.Unlock()

	if len(chunk.Data) > 0 {
		if _, err := state.pw.Write(chunk.Data); err != nil && !errors.Is(err, io.ErrClosedPipe) {
			return nil
		}
	}
	if fin {
		_ = state.pw.Close()
	}
	return nil
}

func (s *Session) sendRPCResponseChunks(reqID uint32, status uint8, data []byte, fin bool) error {
	if len(data) == 0 {
		var flags uint8
		if fin {
			flags = RPCFlagFIN
		}
		raw, err := EncodeRPCFrame(RPCChunk{ReqID: reqID, Code: status, Flags: flags})
		if err != nil {
			return err
		}
		return s.writeFrame(Frame{Type: FrameHostRPCResp, StreamID: 0, Payload: raw})
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
		raw, err := EncodeRPCFrame(RPCChunk{
			ReqID: reqID,
			Code:  status,
			Flags: flags,
			Data:  part,
		})
		if err != nil {
			return err
		}
		if err := s.writeFrame(Frame{Type: FrameHostRPCResp, StreamID: 0, Payload: raw}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Session) handleInboundRPCResp(chunk RPCChunk) {
	s.mu.Lock()
	entry, ok := s.pendingRPCs[chunk.ReqID]
	if ok && (chunk.Flags&RPCFlagFIN) != 0 {
		delete(s.pendingRPCs, chunk.ReqID)
	}
	s.mu.Unlock()
	if !ok {
		return
	}
	select {
	case entry.ch <- chunk:
	case <-entry.done:
	case <-s.doneCh:
	}
}

// CallHostRPCStream executes a Guest-to-Host RPC over stream 0 with 32 KiB chunking.
func (s *Session) CallHostRPCStream(
	ctx context.Context,
	op uint8,
	writeReq func(send func(chunk []byte, fin bool) error) error,
	onResp func(status uint8, chunk []byte, fin bool) error,
) error {
	reqID := s.nextRPCReqID.Add(1)
	if reqID == 0 {
		reqID = s.nextRPCReqID.Add(1)
	}
	respCh := make(chan RPCChunk, 64)
	callDone := make(chan struct{})
	defer close(callDone)

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return io.ErrClosedPipe
	}
	s.pendingRPCs[reqID] = pendingRPC{ch: respCh, done: callDone}
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.pendingRPCs, reqID)
		s.mu.Unlock()
	}()

	sendChunk := func(data []byte, fin bool) error {
		if len(data) == 0 {
			var flags uint8
			if fin {
				flags = RPCFlagFIN
			}
			raw, err := EncodeRPCFrame(RPCChunk{ReqID: reqID, Code: op, Flags: flags})
			if err != nil {
				return err
			}
			return s.writeFrame(Frame{Type: FrameHostRPCReq, StreamID: 0, Payload: raw})
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
			if err := s.writeFrame(Frame{Type: FrameHostRPCReq, StreamID: 0, Payload: raw}); err != nil {
				return err
			}
		}
		return nil
	}

	if err := writeReq(sendChunk); err != nil {
		return err
	}

	for {
		select {
		case chunk := <-respCh:
			fin := (chunk.Flags & RPCFlagFIN) != 0
			if err := onResp(chunk.Code, chunk.Data, fin); err != nil {
				return err
			}
			if fin {
				return nil
			}
		case <-ctx.Done():
			return ctx.Err()
		case <-s.doneCh:
			return io.ErrClosedPipe
		}
	}
}

// ProxyStream adapts a mux-tunnelled TCP connection to io.ReadWriteCloser.
type ProxyStream struct {
	sess      *Session
	id        uint32
	sendWin   *flowWindow
	ackCh     chan ProxyConnectAck
	inMu      sync.Mutex // guards recvBuf, recvAvail, remoteEOF, remoteErr, inSignal
	recvBuf   []byte
	recvAvail uint32
	remoteEOF bool
	remoteErr error
	inSignal  chan struct{}
	closeOnce sync.Once
}

func (s *Session) allocProxyStreamIDLocked() uint32 {
	for {
		id := s.nextProxyID
		s.nextProxyID++
		if s.nextProxyID > 0x7FFFFFFF || s.nextProxyID < GuestStreamIDBase {
			s.nextProxyID = GuestStreamIDBase
		}
		if _, inPTY := s.ptyStreams[id]; inPTY {
			continue
		}
		if _, inProxy := s.proxyStreams[id]; inProxy {
			continue
		}
		return id
	}
}

// DialProxy opens a flow-controlled outbound proxy stream over mux (0x0F to 0x10).
func (s *Session) DialProxy(ctx context.Context, host string, port uint16) (*ProxyStream, error) {
	payload, err := EncodeProxyConnect(ProxyConnectRequest{Host: host, Port: port})
	if err != nil {
		return nil, err
	}

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, io.ErrClosedPipe
	}
	streamID := s.allocProxyStreamIDLocked()
	ps := &ProxyStream{
		sess:      s,
		id:        streamID,
		sendWin:   newFlowWindow(InitialStreamWindow),
		ackCh:     make(chan ProxyConnectAck, 1),
		recvAvail: InitialStreamWindow,
		inSignal:  make(chan struct{}),
	}
	s.proxyStreams[streamID] = ps
	s.mu.Unlock()

	cleanup := func() {
		ps.sendWin.close()
		s.mu.Lock()
		delete(s.proxyStreams, streamID)
		s.mu.Unlock()
	}

	if err := s.writeFrame(Frame{
		Type:     FrameProxyConnect,
		StreamID: streamID,
		Payload:  payload,
	}); err != nil {
		cleanup()
		return nil, err
	}

	select {
	case ack := <-ps.ackCh:
		if ack.Status == ProxyStatusOK {
			return ps, nil
		}
		cleanup()
		if ack.Status == ProxyStatusDirectBypass || asciiEqualFold(asciiTrimSpace(ack.Message), "DIRECT") {
			return nil, ErrDirectBypass
		}
		return nil, &ProxyConnectError{Status: ack.Status, Message: ack.Message}
	case <-ctx.Done():
		cleanup()
		_ = s.writeFrame(Frame{Type: FramePTYExit, StreamID: streamID, Payload: EncodePTYExit(1)})
		return nil, ctx.Err()
	case <-s.doneCh:
		cleanup()
		return nil, io.ErrClosedPipe
	}
}

func (s *Session) handleProxyConnectAck(streamID uint32, ack ProxyConnectAck) {
	s.mu.Lock()
	ps := s.proxyStreams[streamID]
	s.mu.Unlock()
	if ps == nil {
		return
	}
	select {
	case ps.ackCh <- ack:
	default:
	}
}

func (ps *ProxyStream) resetRecvWindow() {
	ps.inMu.Lock()
	ps.recvAvail = InitialStreamWindow
	ps.inMu.Unlock()
}

func (ps *ProxyStream) pushInboundData(data []byte) error {
	ps.inMu.Lock()
	if uint32(len(data)) > ps.recvAvail {
		ps.inMu.Unlock()
		return ErrFlowControlViolation
	}
	ps.recvAvail -= uint32(len(data))
	ps.recvBuf = append(ps.recvBuf, data...)
	close(ps.inSignal)
	ps.inSignal = make(chan struct{})
	ps.inMu.Unlock()
	return nil
}

func (ps *ProxyStream) handleRemoteExit(code uint8) {
	ps.sendWin.close()
	ps.inMu.Lock()
	ps.remoteEOF = true
	if code != 0 {
		ps.remoteErr = ErrProxyStreamReset
	}
	close(ps.inSignal)
	ps.inSignal = make(chan struct{})
	ps.inMu.Unlock()
}

func (ps *ProxyStream) abortLocal() {
	ps.sendWin.close()
	ps.inMu.Lock()
	ps.remoteEOF = true
	if ps.remoteErr == nil {
		ps.remoteErr = io.ErrClosedPipe
	}
	close(ps.inSignal)
	ps.inSignal = make(chan struct{})
	ps.inMu.Unlock()
}

func (ps *ProxyStream) Read(p []byte) (int, error) {
	for {
		ps.inMu.Lock()
		if len(ps.recvBuf) > 0 {
			n := copy(p, ps.recvBuf)
			ps.recvBuf = ps.recvBuf[n:]
			if len(ps.recvBuf) == 0 {
				ps.recvBuf = nil
			}
			ps.recvAvail += uint32(n)
			ps.inMu.Unlock()
			if n > 0 {
				_ = ps.sess.writeFrame(Frame{
					Type:     FramePTYWindowUpdate,
					StreamID: ps.id,
					Payload:  EncodeWindowUpdate(uint32(n)),
				})
			}
			return n, nil
		}
		if ps.remoteEOF {
			err := ps.remoteErr
			ps.inMu.Unlock()
			if err != nil {
				return 0, err
			}
			return 0, io.EOF
		}
		sig := ps.inSignal
		ps.inMu.Unlock()

		select {
		case <-sig:
		case <-ps.sess.doneCh:
			return 0, io.ErrClosedPipe
		}
	}
}

func (ps *ProxyStream) Write(p []byte) (int, error) {
	total := len(p)
	if err := ps.sess.sendWindowedData(context.Background(), ps.id, ps.sendWin, p); err != nil {
		return 0, err
	}
	return total, nil
}

// CloseWrite closes the outbound write side while preserving inbound reads.
func (ps *ProxyStream) CloseWrite() error {
	ps.sendWin.close()
	return nil
}

// Close sends a clean EOF (exitCode = 0) over PTY_EXIT if still open.
func (ps *ProxyStream) Close() error {
	return ps.closeWithCode(0)
}

// CloseReset sends an abnormal reset (exitCode = 1) over PTY_EXIT if still open.
func (ps *ProxyStream) CloseReset() error {
	return ps.closeWithCode(1)
}

func (ps *ProxyStream) closeWithCode(code uint8) error {
	var err error
	ps.closeOnce.Do(func() {
		ps.sendWin.close()
		ps.sess.mu.Lock()
		_, stillActive := ps.sess.proxyStreams[ps.id]
		delete(ps.sess.proxyStreams, ps.id)
		ps.sess.mu.Unlock()
		ps.abortLocal()
		if stillActive {
			err = ps.sess.writeFrame(Frame{
				Type:     FramePTYExit,
				StreamID: ps.id,
				Payload:  EncodePTYExit(code),
			})
		}
	})
	return err
}
