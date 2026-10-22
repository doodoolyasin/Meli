// Package frame implements the Bale-DataChannel transport framing for the
// MasterDNS companion and the Android peer. It follows the 19-byte header
// layout used by qr-tunnel (https://github.com/aminsaedi/qr-tunnel
// /internal/transport/transport.go) so both ends stay wire-compatible:
//
//	[1B flags][2B stream_id][4B seq_num][4B ack_num][2B window_size]
//	[2B payload_len][4B crc32][N payload]
//
// DataChannels are SCTP ordered+reliable by default; this framing adds
// integrity (CRC32) plus seq tracking so a maxRetransmits/unreliable channel
// can still be served (loss detection + ack bitmap on the receiver).
package frame

import (
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"sync"
)

// Frame flags (values must match qr-tunnel's transport.go exactly).
const (
	FlagSYN  byte = 0x01
	FlagACK  byte = 0x02
	FlagFIN  byte = 0x04
	FlagDATA byte = 0x08
	FlagRST  byte = 0x10
	FlagPING byte = 0x20
	FlagPONG byte = 0x40
)

const HeaderSize = 19

// Frame is one wire frame.
type Frame struct {
	Flags      byte
	StreamID   uint16
	SeqNum     uint32
	AckNum     uint32
	WindowSize uint16
	Payload    []byte
}

func Encode(f *Frame) []byte {
	buf := make([]byte, HeaderSize+len(f.Payload))
	buf[0] = f.Flags
	binary.BigEndian.PutUint16(buf[1:3], f.StreamID)
	binary.BigEndian.PutUint32(buf[3:7], f.SeqNum)
	binary.BigEndian.PutUint32(buf[7:11], f.AckNum)
	binary.BigEndian.PutUint16(buf[11:13], f.WindowSize)
	binary.BigEndian.PutUint16(buf[13:15], uint16(len(f.Payload)))
	copy(buf[HeaderSize:], f.Payload)
	checksum := crc32.ChecksumIEEE(buf)
	binary.BigEndian.PutUint32(buf[15:19], checksum)
	return buf
}

// Decode parses raw bytes (one message from a DataChannel) into a Frame.
func Decode(data []byte) (*Frame, error) {
	if len(data) < HeaderSize {
		return nil, fmt.Errorf("frame too short: %d < %d", len(data), HeaderSize)
	}
	// Integrity: CRC over the frame with the CRC field zeroed, exactly as
	// Encode computed it.
	probe := make([]byte, len(data))
	copy(probe, data)
	want := binary.BigEndian.Uint32(probe[15:19])
	probe[15], probe[16], probe[17], probe[18] = 0, 0, 0, 0
	got := crc32.ChecksumIEEE(probe)
	if got != want {
		return nil, fmt.Errorf("crc mismatch: got %08x want %08x", got, want)
	}
	n := int(binary.BigEndian.Uint16(data[13:15]))
	if HeaderSize+n != len(data) {
		return nil, fmt.Errorf("payload length mismatch: declared %d, frame %d", n, len(data))
	}
	return &Frame{
		Flags:      data[0],
		StreamID:   binary.BigEndian.Uint16(data[1:3]),
		SeqNum:     binary.BigEndian.Uint32(data[3:7]),
		AckNum:     binary.BigEndian.Uint32(data[7:11]),
		WindowSize: binary.BigEndian.Uint16(data[11:13]),
		Payload:    data[HeaderSize:],
	}, nil
}

// MaxReorderWindow limits how far ahead out-of-order frames can be buffered
// to protect against memory exhaustion from malicious or broken peers.
const MaxReorderWindow = 512

// Reassembler orders DATA frames into a sequential byte stream, dropping
// duplicates and tracking gaps. Not thread-safe; the owning channel pump
// serializes delivery.
type Reassembler struct {
	next   uint32                 // lowest missing sequence number
	pend   map[uint32][]byte      // resync buffer (received out-of-order)
	gapped bool                   // saw a gap (used for ACK policy)
	mu     sync.Mutex
	onGap  uint64                 // statistics: frames buffered out-of-order
	dupCnt uint64
}

func NewReassembler(startSeq uint32) *Reassembler {
	return &Reassembler{next: startSeq, pend: make(map[uint32][]byte)}
}

// Feed consumes one DATA payload. It returns the newly-in-order bytes
// (possibly empty) and the highest contiguous end sequence after delivery.
func (r *Reassembler) Feed(seq uint32, payload []byte) ([][]byte, uint32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if seq == r.next {
		out := [][]byte{payload}
		r.next = seq + 1
		for {
			p, ok := r.pend[r.next]
			if !ok {
				break
			}
			delete(r.pend, r.next)
			out = append(out, p)
			r.next = r.next + 1
		}
		return out, r.next
	}
	if seq < r.next { // duplicate of already-delivered data
		r.dupCnt++
		return nil, r.next
	}
	if seq > r.next+MaxReorderWindow || len(r.pend) >= MaxReorderWindow {
		// Out of sliding window or buffer full: drop to prevent memory bloat
		return nil, r.next
	}
	if r.pend[seq] == nil {
		r.onGap++
		r.gapped = true
		r.pend[seq] = payload
		return nil, r.next
	}
	r.pend[seq] = payload
	return nil, r.next
}

// NextSeq returns the lowest still-missing sequence number.
func (r *Reassembler) NextSeq() uint32 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.next
}

// Stats reports out-of-order buffered frames and duplicates observed.
func (r *Reassembler) Stats() (gapped uint64, dups uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.onGap, r.dupCnt
}

//-gapFree reports whether there is no pending gap (receiver may ACK lowest).
func GapFreeState(r *Reassembler) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.pend) == 0
}
