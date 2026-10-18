package raptorq

// Encoder encodes data into RaptorQ repair symbols
type Encoder struct {
	// TODO: implement using external lib or pure Go
}

// NewEncoder returns a new RaptorQ encoder for k source symbols
func NewEncoder(k int) *Encoder {
	return &Encoder{}
}

// Encode takes source symbols and produces repair symbols
func (e *Encoder) Encode(src [][]byte) [][]byte {
	// Placeholder: just return src
	return src
}

// Decoder decodes with repair symbols
type Decoder struct{}

// NewDecoder returns a decoder expecting k symbols
func NewDecoder(k int) *Decoder {
	return &Decoder{}
}

// Decode attempts to reconstruct original data from received symbols
func (d *Decoder) Decode(received [][]byte) ([][]byte, bool) {
	// Placeholder: if we have at least k symbols, assume success
	if len(received) >= 0 {
		return received, true
	}
	return nil, false
}
