package mux

import (
	"errors"
	"io"
	"strconv"
	"strings"
	"unicode/utf8"
)

type wrappedErr struct {
	msg string
	err error
}

func (e *wrappedErr) Error() string {
	if e.err == nil {
		return e.msg
	}
	return e.msg + ": " + e.err.Error()
}

func (e *wrappedErr) Unwrap() error {
	return e.err
}

//go:noinline
func errStr(s string) error {
	return errors.New(s)
}

//go:noinline
func wrapErr(msg string, err error) error {
	return &wrappedErr{msg: msg, err: err}
}

//go:noinline
func be16(b []byte) uint16 {
	return uint16(b[0])<<8 | uint16(b[1])
}

//go:noinline
func putBE16(b []byte, v uint16) {
	b[0] = byte(v >> 8)
	b[1] = byte(v)
}

//go:noinline
func be32(b []byte) uint32 {
	return uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
}

//go:noinline
func putBE32(b []byte, v uint32) {
	b[0] = byte(v >> 24)
	b[1] = byte(v >> 16)
	b[2] = byte(v >> 8)
	b[3] = byte(v)
}

//go:noinline
func putLE16(b []byte, v uint16) {
	b[0] = byte(v)
	b[1] = byte(v >> 8)
}

//go:noinline
func putLE32(b []byte, v uint32) {
	b[0] = byte(v)
	b[1] = byte(v >> 8)
	b[2] = byte(v >> 16)
	b[3] = byte(v >> 24)
}

func appendJSONKey(dst []byte, comma bool, key string) []byte {
	if comma {
		dst = append(dst, ',')
	}
	dst = append(dst, '"')
	dst = append(dst, key...)
	return append(dst, `":`...)
}

func appendJSONStrKV(dst []byte, comma bool, key, val string) []byte {
	return appendJSONString(appendJSONKey(dst, comma, key), val)
}

func appendJSONIntKV(dst []byte, comma bool, key string, val int64) []byte {
	return strconv.AppendInt(appendJSONKey(dst, comma, key), val, 10)
}

func appendJSONUintKV(dst []byte, comma bool, key string, val uint64) []byte {
	return strconv.AppendUint(appendJSONKey(dst, comma, key), val, 10)
}

func appendJSONBoolKV(dst []byte, comma bool, key string, val bool) []byte {
	return strconv.AppendBool(appendJSONKey(dst, comma, key), val)
}

//go:noinline
func appendJSONString(dst []byte, s string) []byte {
	dst = append(dst, '"')
	for i := 0; i < len(s); {
		b := s[i]
		if b < utf8.RuneSelf {
			switch b {
			case '"':
				dst = append(dst, '\\', '"')
			case '\\':
				dst = append(dst, '\\', '\\')
			case '\b':
				dst = append(dst, '\\', 'b')
			case '\f':
				dst = append(dst, '\\', 'f')
			case '\n':
				dst = append(dst, '\\', 'n')
			case '\r':
				dst = append(dst, '\\', 'r')
			case '\t':
				dst = append(dst, '\\', 't')
			default:
				if b < 0x20 {
					dst = append(dst, '\\', 'u', '0', '0', hexDigit(b>>4), hexDigit(b&0x0F))
				} else {
					dst = append(dst, b)
				}
			}
			i++
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		if r == utf8.RuneError && size == 1 {
			dst = append(dst, `\ufffd`...)
			i++
			continue
		}
		dst = append(dst, s[i:i+size]...)
		i += size
	}
	dst = append(dst, '"')
	return dst
}

func hexDigit(v byte) byte {
	if v < 10 {
		return '0' + v
	}
	return 'a' + (v - 10)
}

type jsonParser struct {
	src []byte
	pos int
}

//go:noinline
func (p *jsonParser) skipWS() {
	for p.pos < len(p.src) {
		switch p.src[p.pos] {
		case ' ', '\t', '\r', '\n':
			p.pos++
		default:
			return
		}
	}
}

//go:noinline
func (p *jsonParser) expectByte(want byte) error {
	p.skipWS()
	if p.pos >= len(p.src) || p.src[p.pos] != want {
		return errStr("unexpected json token")
	}
	p.pos++
	return nil
}

//go:noinline
func (p *jsonParser) peekByte() (byte, bool) {
	p.skipWS()
	if p.pos >= len(p.src) {
		return 0, false
	}
	return p.src[p.pos], true
}

func (p *jsonParser) readString() (string, error) {
	p.skipWS()
	if p.pos >= len(p.src) || p.src[p.pos] != '"' {
		return "", errStr("expected json string")
	}
	p.pos++
	var out []byte
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		p.pos++
		if c == '"' {
			return string(out), nil
		}
		if c < 0x20 {
			return "", errStr("unescaped control byte in json string")
		}
		if c != '\\' {
			out = append(out, c)
			continue
		}
		if p.pos >= len(p.src) {
			return "", errStr("unterminated json escape")
		}
		esc := p.src[p.pos]
		p.pos++
		if esc == 'u' {
			if p.pos+4 > len(p.src) {
				return "", errStr("truncated unicode escape")
			}
			v, ok := parseHex4(p.src[p.pos : p.pos+4])
			if !ok {
				return "", errStr("invalid unicode escape")
			}
			p.pos += 4
			r := rune(v)
			if 0xD800 <= r && r <= 0xDBFF && p.pos+6 <= len(p.src) && p.src[p.pos] == '\\' && p.src[p.pos+1] == 'u' {
				if v2, ok2 := parseHex4(p.src[p.pos+2 : p.pos+6]); ok2 && 0xDC00 <= v2 && v2 <= 0xDFFF {
					p.pos += 6
					r = 0x10000 + ((r - 0xD800) << 10) + (rune(v2) - 0xDC00)
				}
			}
			out = utf8.AppendRune(out, r)
			continue
		}
		var b byte
		switch esc {
		case '"', '\\', '/':
			b = esc
		case 'b':
			b = '\b'
		case 'f':
			b = '\f'
		case 'n':
			b = '\n'
		case 'r':
			b = '\r'
		case 't':
			b = '\t'
		default:
			return "", errStr("invalid json escape")
		}
		out = append(out, b)
	}
	return "", errStr("unterminated json string")
}

func (p *jsonParser) readBool() (bool, error) {
	p.skipWS()
	if strings.HasPrefix(string(p.src[p.pos:]), "true") {
		p.pos += 4
		return true, nil
	}
	if strings.HasPrefix(string(p.src[p.pos:]), "false") {
		p.pos += 5
		return false, nil
	}
	return false, errStr("expected json bool")
}

func (p *jsonParser) readNumberRaw() (string, error) {
	p.skipWS()
	start := p.pos
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' {
			p.pos++
			continue
		}
		break
	}
	if p.pos == start {
		return "", errStr("expected json number")
	}
	return string(p.src[start:p.pos]), nil
}

func (p *jsonParser) readInt64() (int64, error) {
	raw, err := p.readNumberRaw()
	if err != nil {
		return 0, err
	}
	return strconv.ParseInt(raw, 10, 64)
}

func (p *jsonParser) readUint64() (uint64, error) {
	raw, err := p.readNumberRaw()
	if err != nil {
		return 0, err
	}
	return strconv.ParseUint(raw, 10, 64)
}

//go:noinline
func (p *jsonParser) readNull() bool {
	p.skipWS()
	if strings.HasPrefix(string(p.src[p.pos:]), "null") {
		p.pos += 4
		return true
	}
	return false
}

func (p *jsonParser) skipValue() error {
	ch, ok := p.peekByte()
	if !ok {
		return errStr("unexpected end of json")
	}
	switch ch {
	case '"':
		_, err := p.readString()
		return err
	case '{':
		hasFields, err := p.beginObj()
		if err != nil || !hasFields {
			return err
		}
		first := true
		for {
			_, more, err := p.nextObjKey(&first)
			if err != nil || !more {
				return err
			}
			if err := p.skipValue(); err != nil {
				return err
			}
		}
	case '[':
		hasElems, err := p.beginArr()
		if err != nil || !hasElems {
			return err
		}
		first := true
		for {
			more, err := p.nextArrElem(&first)
			if err != nil || !more {
				return err
			}
			if err := p.skipValue(); err != nil {
				return err
			}
		}
	case 't', 'f':
		_, err := p.readBool()
		return err
	case 'n':
		if !p.readNull() {
			return errStr("invalid json null")
		}
		return nil
	default:
		_, err := p.readNumberRaw()
		return err
	}
}

func (p *jsonParser) beginObj() (bool, error) {
	if err := p.expectByte('{'); err != nil {
		return false, err
	}
	if ch, ok := p.peekByte(); ok && ch == '}' {
		p.pos++
		return false, nil
	}
	return true, nil
}

func (p *jsonParser) nextObjKey(first *bool) (string, bool, error) {
	if !*first {
		ch, ok := p.peekByte()
		if !ok {
			return "", false, errStr("unterminated json object")
		}
		if ch == '}' {
			p.pos++
			return "", false, nil
		}
		if ch != ',' {
			return "", false, errStr("expected comma or closing brace in json object")
		}
		p.pos++
	}
	*first = false
	key, err := p.readString()
	if err != nil {
		return "", false, err
	}
	if err := p.expectByte(':'); err != nil {
		return "", false, err
	}
	return key, true, nil
}

func (p *jsonParser) beginArr() (bool, error) {
	if err := p.expectByte('['); err != nil {
		return false, err
	}
	if ch, ok := p.peekByte(); ok && ch == ']' {
		p.pos++
		return false, nil
	}
	return true, nil
}

func (p *jsonParser) nextArrElem(first *bool) (bool, error) {
	if !*first {
		ch, ok := p.peekByte()
		if !ok {
			return false, errStr("unterminated json array")
		}
		if ch == ']' {
			p.pos++
			return false, nil
		}
		if ch != ',' {
			return false, errStr("expected comma or closing bracket in json array")
		}
		p.pos++
	}
	*first = false
	return true, nil
}

func (p *jsonParser) finish() error {
	p.skipWS()
	if p.pos != len(p.src) {
		return errStr("trailing bytes after json value")
	}
	return nil
}

//go:noinline
func sortStrings(s []string) {
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j-1] > s[j]; j-- {
			s[j-1], s[j] = s[j], s[j-1]
		}
	}
}

func sortUint16s(s []uint16) {
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j-1] > s[j]; j-- {
			s[j-1], s[j] = s[j], s[j-1]
		}
	}
}

func containsStr(s []string, target string) bool {
	for _, item := range s {
		if item == target {
			return true
		}
	}
	return false
}

func parseHex4(b []byte) (uint16, bool) {
	var v uint16
	for i := 0; i < 4; i++ {
		c := b[i]
		v <<= 4
		switch {
		case c >= '0' && c <= '9':
			v |= uint16(c - '0')
		case c >= 'a' && c <= 'f':
			v |= uint16(c-'a') + 10
		case c >= 'A' && c <= 'F':
			v |= uint16(c-'A') + 10
		default:
			return 0, false
		}
	}
	return v, true
}

type bufReader struct {
	r    io.Reader
	buf  []byte
	off  int
	end  int
	rerr error
}

func newBufReader(r io.Reader, size int) *bufReader {
	return &bufReader{r: r, buf: make([]byte, size)}
}

func (b *bufReader) fill() error {
	if b.off > 0 {
		n := copy(b.buf, b.buf[b.off:b.end])
		b.off = 0
		b.end = n
	}
	if b.end == len(b.buf) {
		return nil
	}
	if b.rerr != nil {
		err := b.rerr
		b.rerr = nil
		return err
	}
	n, err := b.r.Read(b.buf[b.end:])
	b.end += n
	if err != nil {
		if n > 0 {
			b.rerr = err
			return nil
		}
		return err
	}
	if n == 0 {
		return io.ErrNoProgress
	}
	return nil
}

func (b *bufReader) PeekByte() (byte, error) {
	for b.off == b.end {
		if err := b.fill(); err != nil {
			return 0, err
		}
	}
	return b.buf[b.off], nil
}

func (b *bufReader) ReadByte() (byte, error) {
	v, err := b.PeekByte()
	if err != nil {
		return 0, err
	}
	b.off++
	return v, nil
}

func (b *bufReader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if b.off < b.end {
		n := copy(p, b.buf[b.off:b.end])
		b.off += n
		return n, nil
	}
	if b.rerr != nil {
		err := b.rerr
		b.rerr = nil
		return 0, err
	}
	if len(p) >= len(b.buf) {
		return b.r.Read(p)
	}
	if err := b.fill(); err != nil {
		return 0, err
	}
	n := copy(p, b.buf[b.off:b.end])
	b.off += n
	return n, nil
}

func (b *bufReader) BufferedBytes() []byte {
	return b.buf[b.off:b.end]
}

func (b *bufReader) ReadLine(maxLen int) ([]byte, error) {
	var out []byte
	for {
		if b.off < b.end {
			avail := b.buf[b.off:b.end]
			idx := -1
			for i, c := range avail {
				if c == '\n' {
					idx = i
					break
				}
			}
			if idx >= 0 {
				part := avail[:idx+1]
				b.off += idx + 1
				if len(out)+len(part) > maxLen {
					return nil, errors.New("line exceeds limit")
				}
				return append(out, part...), nil
			}
			if len(out)+len(avail) > maxLen {
				return nil, errors.New("line exceeds limit")
			}
			out = append(out, avail...)
			b.off = b.end
		}
		if err := b.fill(); err != nil {
			if len(out) > 0 && errors.Is(err, io.EOF) {
				return out, io.EOF
			}
			return nil, err
		}
	}
}

func isASCIIWS(c byte) bool {
	return c == ' ' || c == '\t' || c == '\r' || c == '\n'
}

//go:noinline
func trimWSBytes(b []byte) []byte {
	for len(b) > 0 && isASCIIWS(b[0]) {
		b = b[1:]
	}
	for len(b) > 0 && isASCIIWS(b[len(b)-1]) {
		b = b[:len(b)-1]
	}
	return b
}

//go:noinline
func asciiTrimSpace(s string) string {
	i, j := 0, len(s)
	for i < j && isASCIIWS(s[i]) {
		i++
	}
	for j > i && isASCIIWS(s[j-1]) {
		j--
	}
	return s[i:j]
}

//go:noinline
func asciiEqualFold(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := 0; i < len(a); i++ {
		ca, cb := a[i], b[i]
		if ca >= 'A' && ca <= 'Z' {
			ca += 'a' - 'A'
		}
		if cb >= 'A' && cb <= 'Z' {
			cb += 'a' - 'A'
		}
		if ca != cb {
			return false
		}
	}
	return true
}

//go:noinline
func copyStream(dst io.Writer, src io.Reader) (int64, error) {
	buf := make([]byte, 32*1024)
	var total int64
	for {
		nr, rerr := src.Read(buf)
		if nr > 0 {
			nw, werr := dst.Write(buf[:nr])
			total += int64(nw)
			if werr != nil {
				return total, werr
			}
			if nw != nr {
				return total, io.ErrShortWrite
			}
		}
		if rerr != nil {
			if errors.Is(rerr, io.EOF) {
				return total, nil
			}
			return total, rerr
		}
	}
}

//go:noinline
func splitASCIIFields(s string) []string {
	var fields []string
	i := 0
	for i < len(s) {
		for i < len(s) && isASCIIWS(s[i]) {
			i++
		}
		if i >= len(s) {
			break
		}
		start := i
		for i < len(s) && !isASCIIWS(s[i]) {
			i++
		}
		fields = append(fields, s[start:i])
	}
	return fields
}

func hasCtrlOrWS(s string) bool {
	for i := 0; i < len(s); i++ {
		if isASCIIWS(s[i]) || s[i] == 0 {
			return true
		}
	}
	return false
}

//go:noinline
func asciiToLower(s string) string {
	for i := 0; i < len(s); i++ {
		if s[i] >= 'A' && s[i] <= 'Z' {
			b := []byte(s)
			for ; i < len(b); i++ {
				if b[i] >= 'A' && b[i] <= 'Z' {
					b[i] += 'a' - 'A'
				}
			}
			return string(b)
		}
	}
	return s
}
