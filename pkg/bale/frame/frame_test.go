package frame

import (
	"encoding/binary"
	"math/rand"
	"testing"
)

func TestEncodeDecodeRoundTrip(t *testing.T) {
	f := &Frame{Flags: FlagSYN | FlagDATA, StreamID: 9, SeqNum: 42, AckNum: 17,
		WindowSize: 512, Payload: []byte("hello Bale DataChannel")}
	wire := Encode(f)
	if len(wire) != HeaderSize+len(f.Payload) {
		t.Fatalf("wire length %d", len(wire))
	}
	got, err := Decode(wire)
	if err != nil {
		t.Fatal(err)
	}
	if got.Flags != f.Flags || got.StreamID != 9 || got.SeqNum != 42 ||
		got.AckNum != 17 || got.WindowSize != 512 ||
		string(got.Payload) != string(f.Payload) {
		t.Fatalf("round trip got %+v", got)
	}
	// Header layout must match qr-tunnel: flags@0, streamID@1-3, seq@3-7,
	// ack@7-11, win@11-13, plen@13-15, crc@15-19.
	if binary.BigEndian.Uint16(wire[1:3]) != 9 {
		t.Fatal("stream_id offset wrong")
	}
	if binary.BigEndian.Uint16(wire[13:15]) != uint16(len(f.Payload)) {
		t.Fatal("payload_len offset wrong")
	}
}

func TestDecodeRejectsCorruption(t *testing.T) {
	wire := Encode(&Frame{Flags: FlagDATA, StreamID: 1, SeqNum: 1, Payload: []byte("payload")})
	// Flip one payload byte -> CRC mismatch.
	bad := append([]byte{}, wire...)
	bad[HeaderSize] ^= 0xFF
	if _, err := Decode(bad); err == nil {
		t.Fatal("corrupted frame accepted")
	}
	// Truncate header.
	if _, err := Decode(wire[:10]); err == nil {
		t.Fatal("short frame accepted")
	}
	// Declared payload length mismatch.
	bad2 := append([]byte{}, wire...)
	binary.BigEndian.PutUint16(bad2[13:15], 123)
	if _, err := Decode(bad2); err == nil {
		t.Fatal("length-mismatch frame accepted")
	}
}

func TestReassemblerOrderAndDup(t *testing.T) {
	r := NewReassembler(1)
	// Out-of-order delivery: seq 2,3 then 1 fills the gap and flushes 1,2,3.
	if out, next := r.Feed(2, []byte("two")); next != 1 || len(out) != 0 {
		t.Fatalf("seq2 out=%v next=%d", out, next)
	}
	if out, next := r.Feed(3, []byte("three")); next != 1 || len(out) != 0 {
		t.Fatalf("seq3 out=%v next=%d", out, next)
	}
	out, next := r.Feed(1, []byte("one"))
	if next != 4 || len(out) != 3 {
		t.Fatalf("gap flush out=%v next=%d", out, next)
	}
	want := "onetwothree"
	all := []byte{}
	for _, o := range out {
		all = append(all, o...)
	}
	if string(all) != want {
		t.Fatalf("ordered stream: got %q want %q", all, want)
	}
	// Duplicate of delivered seq must be dropped silently.
	if out, _ := r.Feed(1, []byte("one")); out != nil || r.NextSeq() != 4 {
		t.Fatal("duplicate not handled")
	}
	if _, dups := r.Stats(); dups != 1 {
		t.Fatalf("dup counter=%d", dups)
	}
}

func BenchmarkFrameEncode(b *testing.B) {
	const payloadSize = 12_000
	p := make([]byte, payloadSize)
	rand.New(rand.NewSource(1)).Read(p)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = Encode(&Frame{Flags: FlagDATA, StreamID: 2, SeqNum: uint32(i), Payload: p})
	}
}
