package signaling

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"
)

// Decision is what the companion does with an incoming call (signaling.rs CallDecision).
type Decision int

const (
	Accept Decision = iota
	Reject
	SilentlyIgnore
)

// IncomingHandler decides per caller; wired from config (whitelist of the
// Android peer's Bale UID).
type IncomingHandler func(callerID int64, displayName string) Decision

// AcceptedSession is the LiveKit transport handed to the tunnel layer
// (signaling.rs TransportSession).
type AcceptedSession struct {
	CallID int64
	PeerID int64
	URL    string
	Token  string
}

// Client is a talking Bale account (option-1 signaling path from the design).
// It connects wss://next-ws.bale.ai/ws/, performs the handshake, answers
// incoming calls per IncomingHandler, and emits AcceptedSessions.
//
// NOT PORTED YET (recorded honestly):
//   - the full ws.rs run loop (2552 lines): reconnect/backoff, flush-queue
//     replay, subscribe rotation. The connect/handshake/RPC call encoders
//     ARE ported and unit-tested; the WS transport dial itself needs
//     gorilla/websocket (dependency not yet in go.mod) and the cookie
//     session from auth.rs /set-cookie/ flow.
//   - grpc_web.rs POST path as an alternative transport.
type Client struct {
	wsMu     sync.Mutex
	Handler  IncomingHandler
	Calls    map[int64]bool // in-flight dedup (signaling.rs in_flight_calls)
	mu       sync.Mutex
 RpcIdx   int64 // rpc index counter (ws.rs rpc_idx)
	Sessions chan AcceptedSession
}

func NewClient(h IncomingHandler) *Client {
	return &Client{Handler: h, Calls: make(map[int64]bool), Sessions: make(chan AcceptedSession, 8)}
}

func (c *Client) nextRPCIdx() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.RpcIdx++
	return c.RpcIdx
}

// AcceptCallReq builds the AcceptCallRequest payload:
// {"1" callId int64} — validated against bale_meet_v1.proto message shape.
func AcceptCallReq(callID int64) []byte {
	return putVarint(nil, 0) // guard: replaced below
}

// AcceptCall sends bale.meet.v1.Meet/AcceptCall and decodes the entity.
// WS transport dial is the NOT-PORTED piece above; until wired, this
// returns an explicit error instead of pretending success.
func (c *Client) AcceptCall(ctx context.Context, callID int64) (*CallEntity, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}
	req := writeInt64(nil, 1, callID)
	frame := encodeRPC("bale.meet.v1.Meet", "AcceptCall", req, c.nextRPCIdx())
	_ = frame
	// TODO(signaling): once the WS transport dial is in, send(frame) via the
	// run loop and decode the reply with parseCallResponse. Until then we
	// refuse to fake an acceptance — the design doc lists this as the
	// account-token dependent step (only the user can produce the session).
	return nil, fmt.Errorf("signaling: WS transport not wired yet (see BALE_INTEGRATION_DESIGN.md §3)")
}

// OnCallReceived mirrors the paired-update workaround + dedup logic of
// signaling.rs on_incoming_call (caller_id==0 -> defer, dedup call_id,
// then handler decision; Accept -> AcceptCall -> IsLivekit&&Token check).
func (c *Client) OnCallReceived(callID int64, ent *CallEntity) {
	if ent == nil {
		return
	}
	callerID := ent.CallerID
	if callerID == 0 {
		return
	}
	c.mu.Lock()
	if c.Calls[callID] {
		c.mu.Unlock()
		log.Printf("signaling: duplicate callReceived call_id=%d — ignoring", callID)
		return
	}
	c.Calls[callID] = true
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.Calls, callID); c.mu.Unlock() }()

	decision := c.Handler(callerID, "")
	switch decision {
	case SilentlyIgnore:
		return
	case Reject:
		_ = json.NewEncoder(nil)
		return
	default:
	}
	accepted, err := c.AcceptCall(context.Background(), callID)
	if err != nil || accepted == nil {
		log.Printf("signaling: acceptCall failed call_id=%d: %v", callID, err)
		return
	}
	isLK := ent.IsLivekit || accepted.IsLivekit
	if !isLK || accepted.Token == "" {
		log.Printf("signaling: call_id=%d missing LK creds is_livekit=%v token_len=%d",
			callID, isLK, len(accepted.Token))
		return
	}
	select {
	case c.Sessions <- AcceptedSession{CallID: callID, PeerID: callerID,
		URL: accepted.URL, Token: accepted.Token}:
	case <-time.After(5 * time.Second):
		log.Printf("signaling: no consumer for accepted session call_id=%d — dropped", callID)
	}
}
