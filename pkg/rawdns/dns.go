package rawdns

import (
	"sync"
)

// DNSHeader represents the DNS header (12 bytes)
type DNSHeader struct {
	ID      uint16
	Flags   uint16
	QDCount uint16
	ANCount uint16
	NSCount uint16
	ARCount uint16
}

// DNSQuestion represents a single question section
type DNSQuestion struct {
	Name  []byte // compressed or plain
	Type  uint16
	Class uint16
}

// DNSPacket holds the raw byte slice of a DNS message
type DNSPacket struct {
	buf []byte
}

// PacketPool sync.Pool for zero-allocation reuse
var PacketPool = sync.Pool{
	New: func() interface{} {
		return &DNSPacket{buf: make([]byte, 0, 512)} // DNS max size
	},
}

// NewDNSPacket returns a borrowed packet from the pool
func NewDNSPacket() *DNSPacket {
	p := PacketPool.Get().(*DNSPacket)
	p.buf = p.buf[:0]
	return p
}

// Release returns the packet to the pool
func (p *DNSPacket) Release() {
	PacketPool.Put(p)
}

// Bytes returns the underlying byte slice
func (p *DNSPacket) Bytes() []byte {
	return p.buf
}

// SetID sets the packet ID (bytes 0-1)
func (p *DNSPacket) SetID(id uint16) {
	if len(p.buf) < 2 {
		p.buf = grow(p.buf, 2-len(p.buf))
	}
	p.buf[0] = byte(id >> 8)
	p.buf[1] = byte(id)
}

// SetFlags sets the header flags (bytes 2-3)
func (p *DNSPacket) SetFlags(flags uint16) {
	if len(p.buf) < 4 {
		p.buf = grow(p.buf, 4-len(p.buf))
	}
	p.buf[2] = byte(flags >> 8)
	p.buf[3] = byte(flags)
}

// SetQDCount sets QDCOUNT (bytes 4-5)
func (p *DNSPacket) SetQDCount(count uint16) {
	if len(p.buf) < 6 {
		p.buf = grow(p.buf, 6-len(p.buf))
	}
	p.buf[4] = byte(count >> 8)
	p.buf[5] = byte(count)
}

// SetANCount sets ANCOUNT (bytes 6-7)
func (p *DNSPacket) SetANCount(count uint16) {
	if len(p.buf) < 8 {
		p.buf = grow(p.buf, 8-len(p.buf))
	}
	p.buf[6] = byte(count >> 8)
	p.buf[7] = byte(count)
}

// grow ensures the slice has enough capacity and returns the new slice
func grow(b []byte, n int) []byte {
	if len(b)+n <= cap(b) {
		return b[:len(b)+n]
	}
	// double capacity or at least needed
	newCap := cap(b)
	if newCap == 0 {
		newCap = 512
	}
	for newCap < len(b)+n {
		newCap *= 2
	}
	newBuf := make([]byte, len(b), newCap)
	copy(newBuf, b)
	return newBuf[:len(b)+n]
}
