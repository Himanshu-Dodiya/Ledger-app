package auth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

// makeToken builds a signed JWT-like string for testing.
func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func es256Token(t *testing.T, key *ecdsa.PrivateKey, kid string, claims map[string]any) string {
	t.Helper()
	hdr := map[string]any{"alg": "ES256", "typ": "JWT", "kid": kid}
	hdrJSON, _ := json.Marshal(hdr)
	claimsJSON, _ := json.Marshal(claims)
	signingInput := b64(hdrJSON) + "." + b64(claimsJSON)

	h := sha256.Sum256([]byte(signingInput))
	r, s, err := ecdsa.Sign(rand.Reader, key, h[:])
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	// JWT ES256 = R||S, each left-padded to 32 bytes.
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return signingInput + "." + b64(sig)
}

func TestVerifyES256(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	kid := "test-kid"

	v := newVerifier("", "")
	// Inject the public key directly (simulating a loaded JWKS).
	v.keys[kid] = &key.PublicKey

	claims := map[string]any{"sub": "user-123", "exp": float64(time.Now().Add(time.Hour).Unix())}
	tok := es256Token(t, key, kid, claims)

	got, err := v.verify(tok)
	if err != nil {
		t.Fatalf("verify valid ES256 token: %v", err)
	}
	if got["sub"] != "user-123" {
		t.Fatalf("sub = %v, want user-123", got["sub"])
	}
}

func TestVerifyES256_Tampered(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	kid := "test-kid"

	v := newVerifier("", "")
	v.keys[kid] = &other.PublicKey // wrong public key

	claims := map[string]any{"sub": "user-123", "exp": float64(time.Now().Add(time.Hour).Unix())}
	tok := es256Token(t, key, kid, claims)

	if _, err := v.verify(tok); err == nil {
		t.Fatal("expected verification to fail with mismatched key, got nil")
	}
}

func TestVerifyES256_Expired(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	kid := "test-kid"
	v := newVerifier("", "")
	v.keys[kid] = &key.PublicKey

	claims := map[string]any{"sub": "user-123", "exp": float64(time.Now().Add(-time.Hour).Unix())}
	tok := es256Token(t, key, kid, claims)

	if _, err := v.verify(tok); err == nil {
		t.Fatal("expected expired token to be rejected")
	}
}

func TestVerifyHS256(t *testing.T) {
	secret := "my-shared-secret"
	v := newVerifier(secret, "")

	hdrJSON, _ := json.Marshal(map[string]any{"alg": "HS256", "typ": "JWT"})
	claimsJSON, _ := json.Marshal(map[string]any{"sub": "u1", "exp": float64(time.Now().Add(time.Hour).Unix())})
	signingInput := b64(hdrJSON) + "." + b64(claimsJSON)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(signingInput))
	tok := signingInput + "." + b64(mac.Sum(nil))

	got, err := v.verify(tok)
	if err != nil {
		t.Fatalf("verify HS256: %v", err)
	}
	if got["sub"] != "u1" {
		t.Fatalf("sub = %v, want u1", got["sub"])
	}
}

// TestJWKParsing_EC confirms an EC public key round-trips through JWK x/y encoding.
func TestJWKParsing_EC(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	xBytes := make([]byte, 32)
	yBytes := make([]byte, 32)
	key.X.FillBytes(xBytes)
	key.Y.FillBytes(yBytes)

	k := jwk{Kty: "EC", Crv: "P-256", Kid: "k1", X: b64(xBytes), Y: b64(yBytes)}
	pub, err := k.publicKey()
	if err != nil {
		t.Fatalf("publicKey: %v", err)
	}
	ec, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		t.Fatal("not an *ecdsa.PublicKey")
	}
	if ec.X.Cmp(key.X) != 0 || ec.Y.Cmp(key.Y) != 0 {
		t.Fatal("parsed EC key does not match original")
	}
}
