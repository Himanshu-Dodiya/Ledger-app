// Package auth verifies Supabase JWTs locally without any external library.
//
// Supabase projects now sign access tokens with asymmetric keys (ES256/RS256) by default;
// older projects use the legacy HS256 shared secret. This package supports all three:
//
//   - HS256: verified with SUPABASE_JWT_SECRET (if configured).
//   - ES256 / RS256: verified with the project's public key fetched from the JWKS endpoint
//     ({SUPABASE_URL}/auth/v1/.well-known/jwks.json), matched by the token's "kid" header.
//
// JWKS keys are cached in memory and refreshed on an unknown kid (key rotation).
package auth

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log/slog"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/himanshu/ledger-api/internal/httpx"
)

type ctxKey int

const (
	ctxUserID ctxKey = iota
	ctxEmail
)

// UserID returns the authenticated Supabase user ID from ctx (the JWT "sub" claim).
func UserID(ctx context.Context) string {
	v, _ := ctx.Value(ctxUserID).(string)
	return v
}

// UserEmail returns the user's email from ctx, or "".
func UserEmail(ctx context.Context) string {
	v, _ := ctx.Value(ctxEmail).(string)
	return v
}

// Middleware validates the Supabase JWT in the Authorization header and injects the
// userID and email into the request context. hsSecret may be empty (HS256 disabled);
// supabaseURL is used to build the JWKS endpoint for ES256/RS256 verification.
func Middleware(hsSecret, supabaseURL string) func(http.Handler) http.Handler {
	v := newVerifier(hsSecret, supabaseURL)
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			tok := bearerToken(r)
			if tok == "" {
				slog.Warn("auth: rejected request", "path", r.URL.Path, "reason", "missing or malformed Authorization header")
				httpx.UnauthorizedReason(w, "missing bearer token")
				return
			}
			claims, err := v.verify(tok)
			if err != nil {
				slog.Warn("auth: rejected request", "path", r.URL.Path, "reason", err.Error())
				httpx.UnauthorizedReason(w, err.Error())
				return
			}
			sub, _ := claims["sub"].(string)
			if sub == "" {
				slog.Warn("auth: rejected request", "path", r.URL.Path, "reason", "token has no sub claim")
				httpx.UnauthorizedReason(w, "no subject claim")
				return
			}
			ctx := context.WithValue(r.Context(), ctxUserID, sub)
			if email, ok := claims["email"].(string); ok {
				ctx = context.WithValue(ctx, ctxEmail, email)
			}
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(h, "Bearer ")
}

// ---- verifier ----

type verifier struct {
	hsSecret []byte
	jwksURL  string

	mu        sync.RWMutex
	keys      map[string]any // kid -> *ecdsa.PublicKey | *rsa.PublicKey
	fetchedAt time.Time
}

func newVerifier(hsSecret, supabaseURL string) *verifier {
	jwksURL := ""
	if supabaseURL != "" {
		jwksURL = strings.TrimRight(supabaseURL, "/") + "/auth/v1/.well-known/jwks.json"
	}
	return &verifier{
		hsSecret: []byte(hsSecret),
		jwksURL:  jwksURL,
		keys:     map[string]any{},
	}
}

// verify parses and verifies a JWT, returning its claims.
func (v *verifier) verify(token string) (map[string]any, error) {
	parts := strings.SplitN(token, ".", 3)
	if len(parts) != 3 {
		return nil, fmt.Errorf("malformed JWT")
	}

	hdrJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, fmt.Errorf("bad header encoding: %w", err)
	}
	var hdr struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	if err := json.Unmarshal(hdrJSON, &hdr); err != nil {
		return nil, fmt.Errorf("bad header JSON: %w", err)
	}

	signingInput := parts[0] + "." + parts[1]
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, fmt.Errorf("bad signature encoding: %w", err)
	}

	switch hdr.Alg {
	case "HS256":
		if len(v.hsSecret) == 0 {
			return nil, fmt.Errorf("HS256 token but SUPABASE_JWT_SECRET not configured")
		}
		mac := hmac.New(sha256.New, v.hsSecret)
		mac.Write([]byte(signingInput))
		if !hmac.Equal(mac.Sum(nil), sig) {
			return nil, fmt.Errorf("invalid signature")
		}
	case "ES256":
		pub, err := v.ecKey(hdr.Kid)
		if err != nil {
			return nil, err
		}
		if !verifyES256(pub, signingInput, sig) {
			return nil, fmt.Errorf("invalid signature")
		}
	case "RS256":
		pub, err := v.rsaKey(hdr.Kid)
		if err != nil {
			return nil, err
		}
		h := sha256.Sum256([]byte(signingInput))
		if err := rsa.VerifyPKCS1v15(pub, crypto.SHA256, h[:], sig); err != nil {
			return nil, fmt.Errorf("invalid signature")
		}
	default:
		return nil, fmt.Errorf("unexpected alg %q", hdr.Alg)
	}

	claimsJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("bad claims encoding: %w", err)
	}
	var claims map[string]any
	if err := json.Unmarshal(claimsJSON, &claims); err != nil {
		return nil, fmt.Errorf("bad claims JSON: %w", err)
	}

	if exp, ok := claims["exp"].(float64); ok {
		if time.Now().Unix() > int64(exp) {
			return nil, fmt.Errorf("token expired")
		}
	}
	return claims, nil
}

// verifyES256 checks an ECDSA P-256 signature. JWT ES256 signatures are the raw
// concatenation R||S (64 bytes), not ASN.1 DER.
func verifyES256(pub *ecdsa.PublicKey, signingInput string, sig []byte) bool {
	if len(sig) != 64 {
		return false
	}
	r := new(big.Int).SetBytes(sig[:32])
	s := new(big.Int).SetBytes(sig[32:])
	h := sha256.Sum256([]byte(signingInput))
	return ecdsa.Verify(pub, h[:], r, s)
}

// ---- JWKS cache ----

func (v *verifier) ecKey(kid string) (*ecdsa.PublicKey, error) {
	k, err := v.key(kid)
	if err != nil {
		return nil, err
	}
	pub, ok := k.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("kid %q is not an EC key", kid)
	}
	return pub, nil
}

func (v *verifier) rsaKey(kid string) (*rsa.PublicKey, error) {
	k, err := v.key(kid)
	if err != nil {
		return nil, err
	}
	pub, ok := k.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("kid %q is not an RSA key", kid)
	}
	return pub, nil
}

func (v *verifier) key(kid string) (any, error) {
	v.mu.RLock()
	k := v.keys[kid]
	v.mu.RUnlock()
	if k != nil {
		return k, nil
	}
	// Unknown kid — refresh JWKS once (handles key rotation / cold start).
	if err := v.refresh(); err != nil {
		return nil, fmt.Errorf("fetch JWKS: %w", err)
	}
	v.mu.RLock()
	k = v.keys[kid]
	v.mu.RUnlock()
	if k == nil {
		return nil, fmt.Errorf("no JWKS key for kid %q", kid)
	}
	return k, nil
}

func (v *verifier) refresh() error {
	if v.jwksURL == "" {
		return fmt.Errorf("SUPABASE_URL not configured (required for ES256/RS256)")
	}
	// Throttle: avoid hammering the endpoint on a barrage of bad kids.
	v.mu.RLock()
	recent := time.Since(v.fetchedAt) < 30*time.Second && len(v.keys) > 0
	v.mu.RUnlock()
	if recent {
		return nil
	}

	req, _ := http.NewRequest(http.MethodGet, v.jwksURL, nil)
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("JWKS HTTP %d", resp.StatusCode)
	}

	var doc struct {
		Keys []jwk `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&doc); err != nil {
		return err
	}

	parsed := map[string]any{}
	for _, k := range doc.Keys {
		pub, err := k.publicKey()
		if err != nil {
			slog.Warn("auth: skipping unparseable JWK", "kid", k.Kid, "err", err)
			continue
		}
		parsed[k.Kid] = pub
	}

	v.mu.Lock()
	v.keys = parsed
	v.fetchedAt = time.Now()
	v.mu.Unlock()
	slog.Info("auth: loaded JWKS", "keys", len(parsed))
	return nil
}

// jwk is a JSON Web Key (subset of fields we support: EC P-256 and RSA).
type jwk struct {
	Kty string `json:"kty"`
	Kid string `json:"kid"`
	Crv string `json:"crv"`
	X   string `json:"x"`
	Y   string `json:"y"`
	N   string `json:"n"`
	E   string `json:"e"`
}

func (k jwk) publicKey() (any, error) {
	switch k.Kty {
	case "EC":
		if k.Crv != "P-256" {
			return nil, fmt.Errorf("unsupported EC curve %q", k.Crv)
		}
		x, err := base64.RawURLEncoding.DecodeString(k.X)
		if err != nil {
			return nil, fmt.Errorf("bad x: %w", err)
		}
		y, err := base64.RawURLEncoding.DecodeString(k.Y)
		if err != nil {
			return nil, fmt.Errorf("bad y: %w", err)
		}
		return &ecdsa.PublicKey{
			Curve: elliptic.P256(),
			X:     new(big.Int).SetBytes(x),
			Y:     new(big.Int).SetBytes(y),
		}, nil
	case "RSA":
		n, err := base64.RawURLEncoding.DecodeString(k.N)
		if err != nil {
			return nil, fmt.Errorf("bad n: %w", err)
		}
		e, err := base64.RawURLEncoding.DecodeString(k.E)
		if err != nil {
			return nil, fmt.Errorf("bad e: %w", err)
		}
		return &rsa.PublicKey{
			N: new(big.Int).SetBytes(n),
			E: int(new(big.Int).SetBytes(e).Int64()),
		}, nil
	default:
		return nil, fmt.Errorf("unsupported kty %q", k.Kty)
	}
}
