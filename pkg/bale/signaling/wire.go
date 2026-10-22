// Package signaling implements the bale.meet.v1.Meet RPC plumbing needed by
// the MasterDNS companion to answer incoming Bale calls, ported 1:1 from the
// reverse-engineered Rust in vendored android/balevpn/bale-signaling-rust
// (ws.rs / proto.rs / signaling.rs). Wire facts (with upstream citations):
//
//	wss://next-ws.bale.ai/ws/            (ws.rs:27-28, 1285)
//	handshake {f3={f1=PROTO_VERSION=1, f2=API_VERSION=151668}}
//	ping      {f2={f1=ping_idx}}
//	rpc       {f1={f1=service, f2=method, f3=payload, f5=idx}}
//	callStarted/52807, callAccepted/52808, callEnded/52809, callReceived/52810
//	CallEntity tags: 1=call_id 2=token 3=room 4=url(wrapped-string)
//	                 5=video 6=create_date 7=start_date 8=caller_id(adminUid)
//	                 10=duration 11=discard_reason 12=is_livekit
package signaling

import (
	"errors"
	"fmt"
)

// Wire constants (android/balevpn/bale-signaling-rust/bale-signaling/src/ws.rs:27-30, 487-490).
const (
	WSHost      = "next-ws.bale.ai"
	WSPath      = "/ws/"
	Origin      = "https://web.bale.ai"
	ProtoVer    = int32(1)
	APIVer      = int64(151_668)
	XCCallStarted  = 52807
	XCCallAccepted = 52808
	XCCallEnded    = 52809
	XCCallReceived = 52810
)

// CallEntity mirrors CallEntity in ws.rs:48-62.
type CallEntity struct {
	CallID        int64
	Token         string
	Room          string
	URL           string // wrapped-string field 4
	IsLivekit     bool
	CallerID      int64 // adminUid, field 8
	Video         bool
	CreateDate    int64
	StartDate     int64
	Duration      int32
	DiscardReason int32
}

// --- hand-rolled protobuf writer (proto.rs ProtoWriter) ---

func putVarint(b []byte, v uint64) []byte {
	for v&^uint64(0x7F) != 0 {
		b = append(b, byte(v&0x7F)|0x80)
		v >>= 7
	}
	return append(b, byte(v))
}

func writeTag(b []byte, field, wire uint32) []byte {
	return putVarint(b, (uint64(field)<<3)|uint64(wire))
}

func writeInt32(b []byte, field uint32, v int32) []byte {
	b = writeTag(b, field, 0)
	return putVarint(b, uint64(v))
}

func writeInt64(b []byte, field uint32, v int64) []byte {
	b = writeTag(b, field, 0)
	return putVarint(b, uint64(v))
}

func writeBytes(b []byte, field uint32, v []byte) []byte {
	b = writeTag(b, field, 2)
	b = putVarint(b, uint64(len(v)))
	return append(b, v...)
}

func writeString(b []byte, field uint32, v string) []byte {
	return writeBytes(b, field, []byte(v))
}

// encodeRPC builds the RPC request frame exactly like ws.rs encode_rpc.
func encodeRPC(service, method string, payload []byte, idx int64) []byte {
	inner := []byte{}
	inner = writeString(inner, 1, service)
	inner = writeString(inner, 2, method)
	inner = writeBytes(inner, 3, payload)
	inner = writeInt64(inner, 5, idx)
	return writeBytes(nil, 1, inner)
}

// encodeHandshake mirrors ws.rs encode_handshake.
func encodeHandshake() []byte {
	inner := []byte{}
	inner = writeInt32(inner, 1, ProtoVer)
	inner = writeInt64(inner, 2, APIVer)
	return writeBytes(nil, 3, inner)
}

// encodePing mirrors ws.rs encode_ping.
func encodePing(idx int32) []byte {
	inner := writeInt32(nil, 1, idx)
	return writeBytes(nil, 2, inner)
}

// --- hand-rolled protobuf reader (proto.rs ProtoReader) ---

var errEOF = errors.New("proto: unexpected EOF")

type reader struct {
	buf []byte
	pos int
}

func (r *reader) hasMore() bool { return r.pos < len(r.buf) }

func (r *reader) varint() (uint64, error) {
	var res uint64
	shift := uint32(0)
	for r.pos < len(r.buf) {
		b := r.buf[r.pos]
		r.pos++
		res |= uint64(b&0x7F) << shift
		if b&0x80 == 0 {
			return res, nil
		}
		shift += 7
		if shift >= 64 {
			return 0, errEOF
		}
	}
	return 0, errEOF
}

func (r *reader) tag() (uint32, uint32, error) {
	v, err := r.varint()
	if err != nil {
		return 0, 0, err
	}
	return uint32(v >> 3), uint32(v & 7), nil
}

func (r *reader) bytesField() ([]byte, error) {
	l, err := r.varint()
	if err != nil {
		return nil, err
	}
	if int(l) > len(r.buf)-r.pos {
		return nil, fmt.Errorf("proto: truncated length need=%d have=%d", l, len(r.buf)-r.pos)
	}
	out := r.buf[r.pos : r.pos+int(l)]
	r.pos += int(l)
	return out, nil
}

func (r *reader) stringField() (string, error) {
	b, err := r.bytesField()
	return string(b), err
}

func (r *reader) skip(wire uint32) error {
	switch wire {
	case 0:
		_, err := r.varint()
		return err
	case 2:
		_, err := r.bytesField()
		return err
	default:
		return fmt.Errorf("proto: unsupported wire type %d", wire)
	}
}

// parseWrappedString mirrors ws.rs parse_wrapped_string: field 1 string.
func parseWrappedString(buf []byte) string {
	r := &reader{buf: buf}
	for r.hasMore() {
		_, w, err := r.tag()
		if err != nil {
			return ""
		}
		if w == 2 {
			s, err := r.stringField()
			if err == nil {
				return s
			}
			return ""
		}
		_ = r.skip(w)
	}
	return ""
}
