package signaling

// parseCallEntity and parseCallResponse — 1:1 port of ws.rs:287-360.

// parseCallEntity decodes a Bale CallEntity (field tags cited in wire.go).
func parseCallEntity(buf []byte) CallEntity {
	r := &reader{buf: buf}
	var c CallEntity
	for r.hasMore() {
		f, w, err := r.tag()
		if err != nil {
			break
		}
		switch {
		case f == 1 && w == 0:
			v, _ := r.varint()
			c.CallID = int64(v)
		case f == 2 && w == 2:
			s, _ := r.stringField()
			c.Token = s
		case f == 3 && w == 2:
			s, _ := r.stringField()
			c.Room = s
		case f == 4 && w == 2:
			b, _ := r.bytesField()
			c.URL = parseWrappedString(b)
		case f == 5 && w == 0:
			v, _ := r.varint()
			c.Video = v != 0
		case f == 6 && w == 0:
			v, _ := r.varint()
			c.CreateDate = int64(v)
		case f == 7 && w == 0:
			v, _ := r.varint()
			c.StartDate = int64(v)
		case f == 8 && w == 0:
			v, _ := r.varint()
			c.CallerID = int64(v)
		case f == 10 && w == 0:
			v, _ := r.varint()
			c.Duration = int32(v)
		case f == 11 && w == 0:
			v, _ := r.varint()
			c.DiscardReason = int32(v)
		case f == 12 && w == 0:
			v, _ := r.varint()
			c.IsLivekit = v != 0
		default:
			if r.skip(w) != nil {
				return c
			}
		}
	}
	return c
}

// parseParticipantID mirrors ws.rs parse_participant_id.
func parseParticipantID(buf []byte) int64 {
	r := &reader{buf: buf}
	var out int64
	for r.hasMore() {
		_, w, err := r.tag()
		if err != nil {
			break
		}
		switch w {
		case 0:
			v, err := r.varint()
			if out == 0 && err == nil {
				iv := int64(v)
				if iv >= 100 && iv <= int64(maxInt32) {
					out = iv
				}
			}
		case 2:
			_, _ = r.bytesField()
		default:
			if r.skip(w) != nil {
				break
			}
		}
	}
	return out
}

const maxInt32 = ^uint32(0) >> 1

// parseCallResponse mirrors ws.rs parse_call_response: f1=CallEntity,
// outer length-delimited fields scanned for a fallback caller_id.
func parseCallResponse(buf []byte) *CallEntity {
	r := &reader{buf: buf}
	var call *CallEntity
	var outerCaller int64
	for r.hasMore() {
		f, w, err := r.tag()
		if err != nil {
			break
		}
		if f == 1 && w == 2 {
			b, err := r.bytesField()
			if err != nil {
				break
			}
			c := parseCallEntity(b)
			call = &c
		} else if w == 2 && outerCaller == 0 {
			b, err := r.bytesField()
			if err != nil {
				break
			}
			outerCaller = parseParticipantID(b)
		} else if r.skip(w) != nil {
			break
		}
	}
	if call == nil {
		return nil
	}
	if call.CallerID == 0 && outerCaller != 0 {
		call.CallerID = outerCaller
	}
	return call
}
