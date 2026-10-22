// Package bale — channel.go: adaptation layer between the app's frames
// (pkg/webrtc Frame, 9-byte header) and the wire transport frames
// (pkg/bale/frame, 19-byte qr-tunnel layout, so both ends behind a Bale
// DataChannel stay qr-compatible and gain seq/CRC integrity).
//
// The companion (and the Android gomobile peer) uses ChannelPump; the
// existing TunnelSession/RelayBridge code keeps its own Frame type unchanged.
package bale

import (
	"io"

	"github.com/doodoolyasin/meli/pkg/bale/frame"
	"github.com/doodoolyasin/meli/pkg/webrtc"
)

// ChannelTransport abstracts the ordered DataChannel both directions
// (pion/real SFU in production; net.Pipe in tests).
type ChannelTransport struct {
	// Send delivers one raw transport frame as a single DataChannel message.
	Send func(raw []byte) error
	// SetOnMessage installs the receiver for incoming DataChannel messages.
	SetOnMessage func(onMessage func(raw []byte))
	// Close tears the channel down.
	Close func() error
}

// ChannelPump adapts RelayBridge/TunnelSession frames over a transport.
// Send-side wraps with a monotonic seq; receive side reorders with
// Reassembler then dispatches the inner webrtc.Frame.
type ChannelPump struct {
	t       ChannelTransport
	seq     uint32
	start   uint32
	reasm   *frame.Reassembler
	onFrame func(f *webrtc.Frame) error
	// seed/handler wiring for the client side mux dispatch
	muxDispatch func(f *webrtc.Frame) error
}

// NewChannelPump wires pump over transport; onFrame receives inner frames
// (RelayBridge.ReceiveFrame or TunnelSession.ReceiveFrame on the other side).
func NewChannelPump(t ChannelTransport, startSeq uint32, onFrame func(f *webrtc.Frame) error) *ChannelPump {
	p := &ChannelPump{
		t:       t,
		start:   startSeq,
		seq:     startSeq,
		reasm:   frame.NewReassembler(startSeq),
		onFrame: onFrame,
	}
	t.SetOnMessage(p.onRawMessage)
	return p
}

// SendFrame wraps and ships one inner frame with the next sequence number.
func (p *ChannelPump) SendFrame(f *webrtc.Frame) error {
	// Inner frame becomes the transport payload: the receiving end
	// unwraps back exactly (mine header format preserved verbatim).
	inner := webrtc.EncodeFrame(f.ConnID, f.MsgType, f.Payload)
	raw := frame.Encode(&frame.Frame{
		Flags:    frame.FlagDATA,
		StreamID: 1, // single logical stream: the relay channel itself
		SeqNum:   p.seq,
		Payload:  inner,
	})
	p.seq++
	return p.t.Send(raw)
}

// SendKeepAlive ships a PING so peers judge link liveness without payload.
func (p *ChannelPump) SendKeepAlive() error {
	raw := frame.Encode(&frame.Frame{Flags: frame.FlagPING, StreamID: 1, SeqNum: p.seq})
	p.seq++
	return p.t.Send(raw)
}

func (p *ChannelPump) onRawMessage(raw []byte) {
	wf, err := frame.Decode(raw)
	if err != nil {
		return // corrupt/interrupted datagram: drop, never crash the pump
	}
	switch {
	case wf.Flags&frame.FlagPING != 0:
		pong := frame.Encode(&frame.Frame{Flags: frame.FlagPONG, StreamID: wf.StreamID,
			SeqNum: p.seq, AckNum: wf.SeqNum})
		p.seq++
		_ = p.t.Send(pong)
		return
	case wf.Flags&frame.FlagPONG != 0:
		return // liveness bookkeeping hook point
	case wf.Flags&frame.FlagDATA == 0:
		return
	}
	parts, _ := p.reasm.Feed(wf.SeqNum, wf.Payload)
	for _, part := range parts {
		inner, err := webrtc.DecodeFrame(newByteReader(part))
		if err != nil {
			continue
		}
		if err := p.onFrame(inner); err != nil && p.muxDispatch != nil {
			_ = p.muxDispatch(inner)
		}
	}
}

func (p *ChannelPump) NextSeq() uint32 { return p.reasm.NextSeq() }

func (p *ChannelPump) CloseFn() error { return p.t.Close() }

// byteReader kept tiny (io.Reader over []byte).
type byteReader = sliceReader

type sliceReader struct{ b []byte; i int }

func newByteReader(b []byte) *sliceReader {
	return &sliceReader{b: b}
}

func (s *sliceReader) Read(p []byte) (int, error) {
	if s.i >= len(s.b) {
		return 0, io.EOF
	}
	n := copy(p, s.b[s.i:])
	s.i += n
	return n, nil
}
