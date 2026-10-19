package desync

import (
	"bytes"
	"encoding/binary"
	"fmt"
)

// DomesticWhitelistedSNIs contains recognized high-reputation Iranian platforms and CDNs
// that remain accessible during strict national whitelist mode.
var DomesticWhitelistedSNIs = []string{
	"bale.ir",
	"live.bale.ir",
	"arvancloud.ir",
	"derak.cloud",
	"aparat.com",
	"telewebion.com",
	"snapp.ir",
	"digikala.com",
	"divar.ir",
}

// RewriteTLSClientHelloSNI replaces or injects a domestic whitelisted SNI inside an authentic TLS ClientHello packet.
// Middlebox DPI checking the SNI extension observes a connection to a permitted domestic service,
// allowing the initial handshake to pass through national gateway filters.
func RewriteTLSClientHelloSNI(clientHello []byte, newSNI string) ([]byte, error) {
	if len(clientHello) < 43 {
		return nil, fmt.Errorf("packet too short to be a TLS ClientHello")
	}

	// Verify ContentType = Handshake (0x16) and HandshakeType = ClientHello (0x01)
	if clientHello[0] != 0x16 || clientHello[5] != 0x01 {
		return nil, fmt.Errorf("not a valid TLS ClientHello record")
	}

	// Session ID length offset: 43
	sessionIDLen := int(clientHello[43])
	offset := 44 + sessionIDLen
	if len(clientHello) < offset+2 {
		return nil, fmt.Errorf("truncated cipher suites length")
	}

	// Cipher Suites
	cipherSuitesLen := int(binary.BigEndian.Uint16(clientHello[offset : offset+2]))
	offset += 2 + cipherSuitesLen
	if len(clientHello) < offset+1 {
		return nil, fmt.Errorf("truncated compression methods length")
	}

	// Compression Methods
	compressionMethodsLen := int(clientHello[offset])
	offset += 1 + compressionMethodsLen
	if len(clientHello) < offset+2 {
		return nil, fmt.Errorf("truncated extensions length")
	}

	// Build new Server Name Indication (SNI) extension (type 0x0000)
	sniBytes := []byte(newSNI)
	sniEntryLen := 1 + 2 + len(sniBytes) // NameType(1) + NameLength(2) + HostName
	sniListLen := sniEntryLen

	extData := make([]byte, 2+sniListLen)
	binary.BigEndian.PutUint16(extData[0:2], uint16(sniListLen))
	extData[2] = 0 // host_name
	binary.BigEndian.PutUint16(extData[3:5], uint16(len(sniBytes)))
	copy(extData[5:], sniBytes)

	newExt := make([]byte, 4+len(extData))
	binary.BigEndian.PutUint16(newExt[0:2], 0x0000) // Extension Type: server_name
	binary.BigEndian.PutUint16(newExt[2:4], uint16(len(extData)))
	copy(newExt[4:], extData)

	// Construct modified ClientHello prefix + new extension + original trailing extensions
	prefix := clientHello[:offset]
	var buf bytes.Buffer
	buf.Write(prefix)

	// Compute new extensions length
	remainingExts := clientHello[offset+2:]
	newExtsTotalLen := len(newExt) + len(remainingExts)

	extLenBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(extLenBytes, uint16(newExtsTotalLen))
	buf.Write(extLenBytes)
	buf.Write(newExt)
	buf.Write(remainingExts)

	out := buf.Bytes()

	// Update Record Layer Length (bytes 3-4) and Handshake Length (bytes 6-8)
	recLen := len(out) - 5
	binary.BigEndian.PutUint16(out[3:5], uint16(recLen))

	hsLen := len(out) - 9
	out[6] = byte(hsLen >> 16)
	out[7] = byte(hsLen >> 8)
	out[8] = byte(hsLen)

	return out, nil
}
