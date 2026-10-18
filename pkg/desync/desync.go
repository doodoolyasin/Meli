package desync

import (
	"encoding/binary"
	"math/rand"
	"time"
)

// BadChecksumPacket returns a copy of pkt with invalid TCP checksum (set to 0xDEAD)
// Assumes pkt is a raw IPv4 TCP packet (IP header + TCP header+data)
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
func EDNS0Option(pkt []byte, optionCode uint16, optionData []byte) []byte {
	// We assume pkt ends with the DNS header+question+answer+authority+additional
	// For simplicity, we just append the OPT RR as additional record.
	// Format: NAME (0x00 for root), TYPE=OPT (0x0029), UDP payload size, ext RCODE, version, RDLENGTH, option-data
	// We'll set UDP payload size to 4096, ext RCODE=0, version=0
	var buf []byte
	buf = append(buf, 0x00) // root name
	buf = append(buf, 0x00, 0x29) // TYPE OPT
	buf = append(buf, 0x10, 0x00) // UDP payload size 4096 (0x1000)
	buf = append(buf, 0x00, 0x00) // ext RCODE (high byte) and version (low byte)
	buf = append(buf, 0x00, 0x00) // RDLENGTH placeholder
	// Now option-data: option code + length + data
	buf = append(buf, byte(optionCode>>8), byte(optionCode))
	buf = append(byte(len(optionData)>>8), byte(len(optionData)))
	buf = append(buf, optionData...)
	// Now fix RDLENGTH
	optStart := len(buf) - len(optionData) - 4 // from start of option-data? Actually RDLENGTH is before option data
	// We'll recompute: easier to rebuild.
	// Let's rebuild whole thing simpler:
	return append(pkt, buildEDNS0(optionCode, optionData)...)
}

func buildEDNS0(optionCode uint16, optionData []byte) []byte {
	var buf []byte
	buf = append(buf, 0x00) // NAME
	buf = append(buf, 0x00, 0x29) // TYPE OPT
	buf = append(buf, 0x10, 0x00) // UDP payload size 4096
	buf = append(buf, 0x00, 0x00) // ext RCODE + version
	// RDLENGTH = 2 (option code) + 2 (option length) + len(optionData)
	rdlength := 4 + len(optionData)
	buf = append(buf, byte(rdlength>>8), byte(rdlength))
	buf = append(buf, byte(optionCode>>8), byte(optionCode))
	buf = append(buf, byte(len(optionData)>>8), byte(len(optionData)))
	buf = append(buf, optionData...)
	return buf
}

// QUICDCIDStego hides data in the Destination Connection ID field of a QUIC Initial packet.
// Placeholder: just returns data (real implementation would construct QUIC packet).
func QUICDCIDStego(data []byte) []byte {
	// In reality, we would set DCID to data (up to 20 bytes) and construct QUIC header.
	// For now just return data.
	return data
}
