package desync

import (
	"encoding/binary"
)

// BadChecksumPacket returns a copy of pkt with invalid TCP checksum (set to 0xDEAD)
// Assumes pkt is a raw IPv4 TCP packet (IP header + TCP header+data)
// Note: This may be ineffective if the kernel rewrites the TCP checksum after userspace.
// For true checksum manipulation, consider using raw sockets or adjusting at the IP layer.
func BadChecksumPacket(pkt []byte) []byte {
	if len(pkt) < 40 { // IPv4 header min 20 + TCP header min 20
		return pkt
	}
	// IPv4 header length
	ihl := pkt[0] & 0x0F
	ipHeaderLen := int(ihl) * 4
	tcpOff := ipHeaderLen
	if len(pkt) < tcpOff+4 {
		return pkt
	}
	// Zero out checksum field
	pkt[tcpOff+16] = 0xDE
	pkt[tcpOff+17] = 0xAD
	return pkt
}

// EDNS0Option builds an EDNS0 OPT RR with option code and data (RFC 6891)
// Appends to existing DNS packet; returns new packet.
// Fixes the ARCOUNT field in the DNS header to reflect the added OPT RR.
func EDNS0Option(pkt []byte, optionCode uint16, optionData []byte) []byte {
	// We assume pkt ends with the DNS header+question+answer+authority+additional
	// DNS header is 12 bytes: ID, QR/OPCODE/AA/TC/RD, RA/Z/RCODE, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT (each 2 bytes big endian)
	if len(pkt) < 12 {
		// Not enough for a DNS header, return as is
		return pkt
	}
	// Make a copy of the packet to avoid modifying the original
	newPkt := make([]byte, len(pkt))
	copy(newPkt, pkt)

	// Extract ARCOUNT (bytes 10 and 11, 0-indexed)
	arcount := binary.BigEndian.Uint16(newPkt[10:12])
	// Increment ARCOUNT by 1 for the OPT RR we are about to add
	arcount++
	binary.BigEndian.PutUint16(newPkt[10:12], arcount)

	// Build the OPT RR
	optRR := buildEDNS0(optionCode, optionData)
	// Append the OPT RR to the packet
	newPkt = append(newPkt, optRR...)

	return newPkt
}

func buildEDNS0(optionCode uint16, optionData []byte) []byte {
	var buf []byte
	buf = append(buf, 0x00) // NAME (root)
	buf = append(buf, 0x00, 0x29) // TYPE OPT
	buf = append(buf, 0x10, 0x00) // UDP payload size 4096 (0x1000)
	buf = append(buf, 0x00, 0x00) // ext RCODE (high byte) and version (low byte)
	// RDLENGTH = 2 (option code) + 2 (option length) + len(optionData)
	rdlength := 4 + len(optionData)
	buf = append(buf, byte(rdlength>>8), byte(rdlength))
	buf = append(buf, byte(optionCode>>8), byte(optionCode))
	buf = append(buf, byte(len(optionData)>>8), byte(len(optionData)))
	buf = append(buf, optionData...)
	return buf
}

// QUICDCIDStego embeds covert tunnel data (up to 20 bytes) inside the Destination Connection ID (DCID)
// of an authentic RFC 9000 QUIC Initial packet, camouflaging tunnel signaling as HTTPS/QUIC traffic.
func QUICDCIDStego(data []byte) []byte {
	// DCID in QUIC v1 can be up to 20 bytes
	dcidLen := len(data)
	if dcidLen > 20 {
		dcidLen = 20
	}
	dcid := make([]byte, dcidLen)
	copy(dcid, data[:dcidLen])

	// QUIC Long Header Initial Packet (RFC 9000 Section 17.2.2)
	// Byte 0: Header Form (1) | Fixed Bit (1) | Long Packet Type 0x00 (Initial) | Reserved (0) | Packet Number Length (0)
	buf := make([]byte, 0, 64)
	buf = append(buf, 0xC0)

	// Version: QUIC v1 (0x00000001)
	buf = append(buf, 0x00, 0x00, 0x00, 0x01)

	// Destination Connection ID Length + DCID (holding our covert data)
	buf = append(buf, byte(dcidLen))
	buf = append(buf, dcid...)

	// Source Connection ID Length + SCID (dummy 8-byte server connection ID)
	scid := []byte{0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04}
	buf = append(buf, byte(len(scid)))
	buf = append(buf, scid...)

	// Token Length: 0 (varint 0x00)
	buf = append(buf, 0x00)

	// Length: Length of Packet Number (1) + Payload (minimum 4 bytes padding) = 5
	buf = append(buf, 0x05)

	// 1-byte Packet Number: 0x01
	buf = append(buf, 0x01)

	// PADDING frames (0x00)
	buf = append(buf, 0x00, 0x00, 0x00, 0x00)

	return buf
}
