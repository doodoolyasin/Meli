package signaling

import (
	"bytes"
	"reflect"
	"testing"
)

// Parity assertions with the Rust unit tests in vendored
// android/balevpn/bale-signaling-rust/bale-signaling/src/ws.rs
// (encode_rpc_round_trips_through_envelope, and the tag-byte vectors).

func TestEncodeRPCMatchesUpstreamShape(t *testing.T) {
	payload := []byte{0x08, 0x05} // random body per upstream test
	buf := encodeRPC("svc", "Method", payload, 42)

	// rust: outer f1 length-delimited, tag byte 0x0A expected first.
	if buf[0] != 0x0A {
		t.Fatalf("outer tag = %#x, want 0x0A (field 1 wire 2)", buf[0])
	}
	ent := parseCallEntity(buf) // only to ensure no panic on arbitrary bytes
	_ = ent

	r := &reader{buf: buf}
	f, w, err := r.tag()
	if err != nil {
		t.Fatal(err)
	}
	if f != 1 || w != 2 {
		t.Fatalf("outer field=%d wire=%d, want 1/2", f, w)
	}
	inner, err := r.bytesField()
	if err != nil {
		t.Fatal(err)
	}
	ir := &reader{buf: inner}
	var svc, mth string
	var body []byte
	var idx int64
	for ir.hasMore() {
		ff, ww, err := ir.tag()
		if err != nil {
			t.Fatal(err)
		}
		switch {
		case ff == 1 && ww == 2:
			svc, _ = ir.stringField()
		case ff == 2 && ww == 2:
			mth, _ = ir.stringField()
		case ff == 3 && ww == 2:
			body, _ = ir.bytesField()
		case ff == 5 && ww == 0:
			v, _ := ir.varint()
			idx = int64(v)
		default:
			t.Fatalf("unexpected field %d wire %d", ff, ww)
		}
	}
	if svc != "svc" || mth != "Method" || !bytes.Equal(body, payload) || idx != 42 {
		t.Fatalf("mismatch: svc=%q mth=%q body=%v idx=%d", svc, mth, body, idx)
	}
}

func TestHandshakeAndPingShape(t *testing.T) {
	hs := encodeHandshake()
	// outer tag: field 3 wire 2 = 0x1A (Rust comment at ws.rs:1642)
	if hs[0] != 0x1A {
		t.Fatalf("handshake outer tag %#x want 0x1A", hs[0])
	}
	r := &reader{buf: hs}
	_, _, _ = r.tag()
	inner, err := r.bytesField()
	if err != nil {
		t.Fatal(err)
	}
	ir := &reader{buf: inner}
	f1, _, _ := ir.tag()
	v1, _ := ir.varint()
	f2, _, _ := ir.tag()
	v2, _ := ir.varint()
	if f1 != 1 || int32(v1) != ProtoVer {
		t.Fatalf("handshake f1=%d val=%d want proto version %d", f1, v1, ProtoVer)
	}
	if f2 != 2 || int64(v2) != APIVer {
		t.Fatalf("handshake f2=%d val=%d want api version %d", f2, v2, APIVer)
	}
	if pg := encodePing(3); len(pg) < 2 {
		t.Fatal("ping frame suspiciously short")
	}
}

func TestParseCallEntityRoundTrip(t *testing.T) {
	orig := CallEntity{CallID: 777, Token: "tok", Room: "room", URL: "u",
		IsLivekit: true, CallerID: 101001, Video: true}
	b := []byte{}
	b = writeInt64(b, 1, orig.CallID)
	b = writeString(b, 2, orig.Token)
	b = writeString(b, 3, orig.Room)
	b = writeBytes(b, 4, writeString(nil, 1, orig.URL)) // nested wrapped-string, matches upstream field 4
	b = writeInt64(b, 5, 1)
	b = writeInt64(b, 8, orig.CallerID)
	b = writeInt64(b, 12, 1)
	got := parseCallEntity(b)
	if got.CallID != 777 || got.Token != "tok" || got.Room != "room" ||
		got.URL != "u" || !got.Video || got.CallerID != 101001 || !got.IsLivekit {
		t.Fatalf("round trip mismatch: %+v", got)
	}
	// Discord/unknown fields must not break parsing (skip path).
	b = append(b, writeString(nil, 99, "unknown")...)
	if ex := parseCallEntity(b); ex.CallID != 777 {
		t.Fatal("unknown-field skip broke the entity")
	}
}

func TestCallResponseOuterCallerFallback(t *testing.T) {
	entityBytes := writeInt64(nil, 1, int64(55))
	resp := []byte{}
	resp = writeBytes(resp, 1, entityBytes)
	resp = writeBytes(resp, 2, writeInt64(nil, 2, int64(2024))) // participant, length-delimited
	got := parseCallResponse(resp)
	if got == nil || got.CallID != 55 || got.CallerID != 2024 {
		t.Fatalf("call response got %+v want call 55 caller 2024", got)
	}
}

func TestWrappedString(t *testing.T) {
	inner := writeString(nil, 1, "https://sfu.example")
	got := parseWrappedString(inner)
	// wrapped-string: field4 bytes are themselves a nested length-delimited
	// message whose f1 is the string.
	if got != "https://sfu.example" {
		t.Fatalf("wrapped string parse got %q", got)
	}
	var _ = reflect.DeepEqual
}
