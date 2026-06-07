package gmail

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"strings"
)

// encryptionKey returns the 32-byte AES key from TOKEN_ENCRYPTION_KEY env (64 hex chars).
func encryptionKey() ([]byte, error) {
	h := os.Getenv("TOKEN_ENCRYPTION_KEY")
	if h == "" {
		return nil, fmt.Errorf("TOKEN_ENCRYPTION_KEY is not set")
	}
	b, err := hex.DecodeString(h)
	if err != nil || len(b) != 32 {
		return nil, fmt.Errorf("TOKEN_ENCRYPTION_KEY must be 64 hex chars (32 bytes)")
	}
	return b, nil
}

// EncryptToken encrypts a plain-text refresh token with AES-256-GCM and encodes the
// result as "iv:tag:ciphertext" hex — identical format to the TypeScript encryptToken().
func EncryptToken(plain string) (string, error) {
	key, err := encryptionKey()
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	iv := make([]byte, gcm.NonceSize()) // 12 bytes
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", err
	}
	// Seal appends the authentication tag to the ciphertext.
	sealed := gcm.Seal(nil, iv, []byte(plain), nil)
	tagSize := gcm.Overhead() // 16 bytes
	ciphertext := sealed[:len(sealed)-tagSize]
	tag := sealed[len(sealed)-tagSize:]
	blob := strings.Join([]string{
		hex.EncodeToString(iv),
		hex.EncodeToString(tag),
		hex.EncodeToString(ciphertext),
	}, ":")
	return blob, nil
}

// DecryptToken decrypts a blob in "iv:tag:ciphertext" hex format (as produced by
// EncryptToken and the TypeScript encryptToken()).
func DecryptToken(blob string) (string, error) {
	key, err := encryptionKey()
	if err != nil {
		return "", err
	}
	parts := strings.SplitN(blob, ":", 3)
	if len(parts) != 3 {
		return "", fmt.Errorf("invalid token blob format")
	}
	iv, err := hex.DecodeString(parts[0])
	if err != nil {
		return "", fmt.Errorf("bad iv: %w", err)
	}
	tag, err := hex.DecodeString(parts[1])
	if err != nil {
		return "", fmt.Errorf("bad tag: %w", err)
	}
	ciphertext, err := hex.DecodeString(parts[2])
	if err != nil {
		return "", fmt.Errorf("bad ciphertext: %w", err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	// GCM.Open expects ciphertext+tag concatenated.
	combined := append(ciphertext, tag...)
	plain, err := gcm.Open(nil, iv, combined, nil)
	if err != nil {
		return "", fmt.Errorf("decryption failed: %w", err)
	}
	return string(plain), nil
}
