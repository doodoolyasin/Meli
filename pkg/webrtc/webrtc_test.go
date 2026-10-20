package webrtc

import (
	"bytes"
	"context"
	"net"
	"testing"
	"time"
)

func TestEncodeDecodeFrame(t *testing.T) {
	connID := uint32(1042)
	msgType := MsgData
	payload := []byte("MELI_WEBRTC_FRAME_TEST")

	encoded := EncodeFrame(connID, msgType, payload)
	if len(encoded) != 9+len(payload) {
		t.Fatalf("Expected length %d, got %d", 9+len(payload), len(encoded))
	}

	frame, err := DecodeFrame(bytes.NewReader(encoded))
	if err != nil {
		t.Fatalf("Failed to decode frame: %v", err)
	}

	if frame.ConnID != connID {
		t.Errorf("Expected ConnID %d, got %d", connID, frame.ConnID)
	}
	if frame.MsgType != msgType {
		t.Errorf("Expected MsgType 0x%02x, got 0x%02x", msgType, frame.MsgType)
	}
	if !bytes.Equal(frame.Payload, payload) {
		t.Errorf("Payload mismatch: got %s, expected %s", string(frame.Payload), string(payload))
	}
}

func TestDecodeFrameCorruptedLength(t *testing.T) {
	// Frame claiming 100 bytes payload but only supplying 2 bytes
	corrupted := []byte{0x00, 0x00, 0x00, 109, 0, 0, 0, 1, MsgData, 0xAA, 0xBB}
	_, err := DecodeFrame(bytes.NewReader(corrupted))
	if err == nil {
		t.Fatal("Expected error for truncated frame, got nil")
	}
}

func TestMultiplexerDispatchAndKeepalive(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	mux := NewChannelMultiplexer(ctx)

	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	connID := uint32(77)
	mux.Register(connID, serverConn)

	testData := []byte("PIPED_DATA_THROUGH_MULTIPLEXER")
	dataFrame := &Frame{
		ConnID:  connID,
		MsgType: MsgData,
		Payload: testData,
	}

	readDone := make(chan []byte)
	go func() {
		buf := make([]byte, len(testData))
		_, _ = clientConn.Read(buf)
		readDone <- buf
	}()

	if err := mux.Dispatch(dataFrame); err != nil {
		t.Fatalf("Dispatch failed: %v", err)
	}

	select {
	case received := <-readDone:
		if !bytes.Equal(received, testData) {
			t.Errorf("Received mismatch: %s vs %s", string(received), string(testData))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Timed out waiting for dispatched data")
	}

	// Test close dispatch
	closeFrame := &Frame{
		ConnID:  connID,
		MsgType: MsgClose,
	}
	if err := mux.Dispatch(closeFrame); err != nil {
		t.Fatalf("Dispatch close failed: %v", err)
	}

	if mux.ActiveCount() != 0 {
		t.Errorf("Expected active count 0, got %d", mux.ActiveCount())
	}
}
