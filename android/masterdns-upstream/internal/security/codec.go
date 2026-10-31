// ==============================================================================
// MasterDnsVPN
// Author: MasterkinG32
// Github: https://github.com/masterking32
// Year: 2026
// ==============================================================================

package security

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"

	baseCodec "masterdnsvpn-go/internal/basecodec"
	"masterdnsvpn-go/internal/config"
)

var (
	ErrInvalidCodecMethod = errors.New("invalid encryption method")
	ErrInvalidCiphertext  = errors.New("invalid ciphertext")
)

const (
	chachaNonceSize = 16
	aesNonceSize    = 12
)

var cryptoBufferPool = sync.Pool{
	New: func() any {
		b := make([]byte, 4096)
		return &b
	},
}

func getCryptoBuffer(size int) *[]byte {
	bufPtr := cryptoBufferPool.Get().(*[]byte)
	if cap(*bufPtr) < size {
		b := make([]byte, size*2)
		bufPtr = &b
	}
	return bufPtr
}

func putCryptoBuffer(bufPtr *[]byte) {
	if bufPtr != nil {
		cryptoBufferPool.Put(bufPtr)
	}
}

type Codec struct {
	method  int
	key     []byte
	encrypt func(dst, src []byte) ([]byte, error)
	decrypt func(dst, src []byte) ([]byte, error)
}

func NewCodecFromConfig(cfg config.ServerConfig, rawKey string) (*Codec, error) {
	return NewCodec(cfg.DataEncryptionMethod, rawKey)
}

func NewCodec(method int, rawKey string) (*Codec, error) {
	if method < 0 || method > 5 {
		return nil, ErrInvalidCodecMethod
	}

	derivedKey := deriveKey(method, rawKey)
	codec := &Codec{
		method: method,
		key:    derivedKey,
	}

	switch method {
	case 0:
		codec.encrypt = codec.noCrypto
		codec.decrypt = codec.noCrypto
	case 1, 2:
		aead, err := chacha20poly1305.New(derivedKey)
		if err != nil {
			return nil, fmt.Errorf("create chacha20poly1305 cipher: %w", err)
		}
		codec.encrypt = codec.makeAEADEncryptor(aead)
		codec.decrypt = codec.makeAEADDecryptor(aead)
	case 3, 4, 5:
		aead, err := newAESGCM(derivedKey)
		if err != nil {
			return nil, err
		}
		codec.encrypt = codec.makeAEADEncryptor(aead)
		codec.decrypt = codec.makeAEADDecryptor(aead)
	default:
		return nil, ErrInvalidCodecMethod
	}

	return codec, nil
}

func (c *Codec) Encrypt(data []byte) ([]byte, error) {
	if c == nil {
		return nil, ErrInvalidCodecMethod
	}
	return c.encrypt(nil, data)
}

func (c *Codec) Decrypt(data []byte) ([]byte, error) {
	if c == nil {
		return nil, ErrInvalidCodecMethod
	}
	return c.decrypt(nil, data)
}

func (c *Codec) Method() int {
	if c == nil {
		return 0
	}
	return c.method
}

func (c *Codec) EncryptAndEncode(data []byte) (string, error) {
	if c == nil {
		return "", ErrInvalidCodecMethod
	}
	if c.method == 0 {
		return baseCodec.Encode(data), nil
	}

	bufPtr := getCryptoBuffer(len(data) + 64)
	defer putCryptoBuffer(bufPtr)

	encrypted, err := c.encrypt((*bufPtr)[:0], data)
	if err != nil {
		return "", err
	}
	return baseCodec.Encode(encrypted), nil
}

func (c *Codec) EncryptAndEncodeBytes(data []byte) ([]byte, error) {
	if c == nil {
		return nil, ErrInvalidCodecMethod
	}
	if c.method == 0 {
		return baseCodec.EncodeToBytes(data), nil
	}

	bufPtr := getCryptoBuffer(len(data) + 64)
	defer putCryptoBuffer(bufPtr)

	encrypted, err := c.encrypt((*bufPtr)[:0], data)
	if err != nil {
		return nil, err
	}
	return baseCodec.EncodeToBytes(encrypted), nil
}

func (c *Codec) DecodeAndDecrypt(data []byte) ([]byte, error) {
	if c == nil {
		return nil, ErrInvalidCodecMethod
	}

	decoded, err := baseCodec.Decode(data)
	if err != nil {
		return nil, err
	}
	if c.method == 0 {
		return decoded, nil
	}
	return c.decrypt(nil, decoded)
}

func (c *Codec) DecodeStringAndDecrypt(data string) ([]byte, error) {
	if c == nil {
		return nil, ErrInvalidCodecMethod
	}

	decoded, err := baseCodec.DecodeString(data)
	if err != nil {
		return nil, err
	}
	if c.method == 0 {
		return decoded, nil
	}
	return c.decrypt(nil, decoded)
}

func (c *Codec) noCrypto(dst, data []byte) ([]byte, error) {
	if cap(dst) < len(data) {
		dst = make([]byte, len(data))
	} else {
		dst = dst[:len(data)]
	}
	copy(dst, data)
	return dst, nil
}

func (c *Codec) xorCrypto(dst, data []byte) ([]byte, error) {
	key := c.key
	keyLen := len(key)
	if len(data) == 0 || keyLen == 0 {
		if cap(dst) < len(data) {
			dst = make([]byte, len(data))
		} else {
			dst = dst[:len(data)]
		}
		copy(dst, data)
		return dst, nil
	}

	if cap(dst) < len(data) {
		dst = make([]byte, len(data))
	} else {
		dst = dst[:len(data)]
	}

	if keyLen == 1 {
		mask := key[0]
		for i := 0; i < len(data); i++ {
			dst[i] = data[i] ^ mask
		}
		return dst, nil
	}

	fullBlocks := len(data) / keyLen
	offset := 0
	for block := 0; block < fullBlocks; block++ {
		for i := 0; i < keyLen; i++ {
			dst[offset+i] = data[offset+i] ^ key[i]
		}
		offset += keyLen
	}
	for i := offset; i < len(data); i++ {
		dst[i] = data[i] ^ key[i-offset]
	}
	return dst, nil
}

func (c *Codec) chachaEncrypt(dst, data []byte) ([]byte, error) {
	aead, err := chacha20poly1305.New(c.key)
	if err != nil {
		return nil, err
	}
	return c.makeAEADEncryptor(aead)(dst, data)
}

func (c *Codec) chachaDecrypt(dst, data []byte) ([]byte, error) {
	aead, err := chacha20poly1305.New(c.key)
	if err != nil {
		return nil, err
	}
	return c.makeAEADDecryptor(aead)(dst, data)
}

func (c *Codec) makeAEADEncryptor(aead cipher.AEAD) func(dst, src []byte) ([]byte, error) {
	nonceSize := aead.NonceSize()
	return func(dst, src []byte) ([]byte, error) {
		if len(src) == 0 {
			if cap(dst) == 0 {
				dst = make([]byte, 0)
			} else {
				dst = dst[:0]
			}
			return dst, nil
		}

		reqSize := nonceSize + len(src) + aead.Overhead()
		if cap(dst) < reqSize {
			dst = make([]byte, nonceSize, reqSize)
		} else {
			dst = dst[:nonceSize]
		}

		nonce := dst[:nonceSize]
		if _, err := rand.Read(nonce); err != nil {
			return nil, fmt.Errorf("generate aead nonce: %w", err)
		}

		dst = aead.Seal(dst, nonce, src, nil)
		return dst, nil
	}
}

func (c *Codec) makeAEADDecryptor(aead cipher.AEAD) func(dst, src []byte) ([]byte, error) {
	nonceSize := aead.NonceSize()
	return func(dst, src []byte) ([]byte, error) {
		if len(src) == 0 {
			return nil, nil
		}
		if len(src) <= nonceSize {
			return nil, ErrInvalidCiphertext
		}

		nonce := src[:nonceSize]
		ciphertext := src[nonceSize:]

		if cap(dst) == 0 {
			dst = make([]byte, 0, len(ciphertext))
		} else {
			dst = dst[:0]
		}

		plaintext, err := aead.Open(dst, nonce, ciphertext, nil)
		if err != nil {
			return nil, ErrInvalidCiphertext
		}
		return plaintext, nil
	}
}

func (c *Codec) makeAESEncryptor(aead cipher.AEAD) func(dst, src []byte) ([]byte, error) {
	return c.makeAEADEncryptor(aead)
}

func (c *Codec) makeAESDecryptor(aead cipher.AEAD) func(dst, src []byte) ([]byte, error) {
	return c.makeAEADDecryptor(aead)
}

func newAESGCM(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create aes cipher: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("create aes-gcm: %w", err)
	}
	return aead, nil
}

func deriveKey(method int, rawKey string) []byte {
	bKey := []byte(rawKey)
	targetLen := requiredDerivedKeyLength(method)

	switch method {
	case 1, 2, 5:
		sum := sha256.Sum256(bKey)
		return sum[:]
	case 3:
		sum := sha256.Sum256(bKey)
		return sum[:16]
	case 4:
		sum := sha256.Sum256(bKey)
		return sum[:24]
	default:
		sum := sha256.Sum256(bKey)
		if targetLen <= 32 {
			return sum[:targetLen]
		}
		key := make([]byte, targetLen)
		copy(key, sum[:])
		return key
	}
}

func requiredDerivedKeyLength(method int) int {
	switch method {
	case 2, 5:
		return 32
	case 3:
		return 16
	case 4:
		return 24
	default:
		return 32
	}
}
