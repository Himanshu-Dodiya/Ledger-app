// Package notify sends FCM push notifications via the Firebase HTTP v1 API.
// It requires no external dependencies — JWT signing uses stdlib crypto/rsa.
// When FIREBASE_SERVICE_ACCOUNT_JSON is absent the Sender is nil and all calls
// are no-ops, so the service degrades gracefully without Firebase credentials.
package notify

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// Sender holds a Firebase service-account key and a cached OAuth2 access token.
// The zero value (nil pointer) is safe; all methods on a nil *Sender are no-ops.
type Sender struct {
	projectID  string
	email      string
	key        *rsa.PrivateKey
	mu         sync.Mutex
	token      string
	tokenExpAt time.Time
}

type serviceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
}

// loadServiceAccountJSON resolves the service-account JSON from one of two env vars.
//
//  1. FIREBASE_SERVICE_ACCOUNT_PATH — a file path; the file is read.
//  2. FIREBASE_SERVICE_ACCOUNT_JSON — either raw JSON (must start with '{')
//     or a file path (anything else); the file is read in the latter case.
//
// Returns ("", nil) when neither variable is set.
func loadServiceAccountJSON() (string, error) {
	// Path variable always wins when set.
	if path := strings.TrimSpace(os.Getenv("FIREBASE_SERVICE_ACCOUNT_PATH")); path != "" {
		b, err := os.ReadFile(path)
		if err != nil {
			return "", fmt.Errorf("notify: read FIREBASE_SERVICE_ACCOUNT_PATH %q: %w", path, err)
		}
		return string(b), nil
	}

	raw := strings.TrimSpace(os.Getenv("FIREBASE_SERVICE_ACCOUNT_JSON"))
	if raw == "" {
		return "", nil
	}

	// If the value looks like JSON, use it directly.
	if strings.HasPrefix(raw, "{") {
		return raw, nil
	}

	// Otherwise treat it as a file path (.env files can't expand $(...) substitutions,
	// so users sometimes put the path here instead of the JSON content).
	b, err := os.ReadFile(raw)
	if err != nil {
		return "", fmt.Errorf("notify: FIREBASE_SERVICE_ACCOUNT_JSON doesn't look like JSON "+
			"and reading it as a file path failed: %w\n"+
			"Set FIREBASE_SERVICE_ACCOUNT_PATH=/path/to/service-account.json instead.", err)
	}
	return string(b), nil
}

// New loads the Firebase service account and returns a Sender. Supported sources:
//
//   - FIREBASE_SERVICE_ACCOUNT_PATH=/path/to/service-account.json   ← easiest
//   - FIREBASE_SERVICE_ACCOUNT_JSON={...inline JSON...}
//
// If FIREBASE_SERVICE_ACCOUNT_JSON is set but looks like a file path (doesn't start
// with '{'), the file is read automatically — so setting the path in either variable
// works.  Returns (nil, nil) when both variables are absent.
func New() (*Sender, error) {
	raw, err := loadServiceAccountJSON()
	if err != nil {
		return nil, err
	}
	if raw == "" {
		return nil, nil
	}
	var sa serviceAccount
	if err := json.Unmarshal([]byte(raw), &sa); err != nil {
		return nil, fmt.Errorf("notify: parse service account JSON: %w", err)
	}
	block, _ := pem.Decode([]byte(sa.PrivateKey))
	if block == nil {
		return nil, fmt.Errorf("notify: no PEM block in private_key field")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("notify: parse PKCS8 key: %w", err)
	}
	rsaKey, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("notify: private key is not RSA")
	}
	slog.Info("notify: FCM sender initialised", "project", sa.ProjectID)
	return &Sender{projectID: sa.ProjectID, email: sa.ClientEmail, key: rsaKey}, nil
}

// Send delivers a notification to each FCM registration token.
// Token-level errors are logged but don't fail the call.
func (s *Sender) Send(ctx context.Context, tokens []string, title, body string) {
	if s == nil || len(tokens) == 0 {
		return
	}
	tok, err := s.accessToken(ctx)
	if err != nil {
		slog.Warn("notify: get access token failed", "err", err)
		return
	}
	for _, fcmToken := range tokens {
		if err := s.sendOne(ctx, tok, fcmToken, title, body); err != nil {
			slog.Warn("notify: FCM send failed", "err", err)
		}
	}
}

// ---- FCM HTTP v1 message types ----

type fcmEnvelope struct {
	Message fcmMsg `json:"message"`
}

type fcmMsg struct {
	Token        string         `json:"token"`
	Notification fcmNotif       `json:"notification"`
	Android      fcmAndroid     `json:"android"`
}

type fcmNotif struct {
	Title string `json:"title"`
	Body  string `json:"body"`
}

type fcmAndroid struct {
	Priority     string          `json:"priority"`
	Notification fcmAndroidNotif `json:"notification"`
}

type fcmAndroidNotif struct {
	ChannelID string `json:"channel_id"`
}

func (s *Sender) sendOne(ctx context.Context, accessToken, fcmToken, title, body string) error {
	payload, _ := json.Marshal(fcmEnvelope{Message: fcmMsg{
		Token:        fcmToken,
		Notification: fcmNotif{Title: title, Body: body},
		Android: fcmAndroid{
			Priority:     "high",
			Notification: fcmAndroidNotif{ChannelID: "ledger_alerts"},
		},
	}})
	endpoint := "https://fcm.googleapis.com/v1/projects/" + s.projectID + "/messages:send"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("FCM HTTP %d: %s", resp.StatusCode, string(b))
	}
	return nil
}

// ---- OAuth2 token management ----

func (s *Sender) accessToken(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.token != "" && time.Until(s.tokenExpAt) > 60*time.Second {
		return s.token, nil
	}
	jwtStr, err := s.makeAssertionJWT()
	if err != nil {
		return "", err
	}
	tok, expAt, err := exchangeJWT(ctx, jwtStr)
	if err != nil {
		return "", err
	}
	s.token, s.tokenExpAt = tok, expAt
	return tok, nil
}

func (s *Sender) makeAssertionJWT() (string, error) {
	now := time.Now().Unix()
	header := base64urlJSON(map[string]string{"alg": "RS256", "typ": "JWT"})
	claims := base64urlJSON(map[string]any{
		"iss":   s.email,
		"sub":   s.email,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   "https://oauth2.googleapis.com/token",
		"iat":   now,
		"exp":   now + 3600,
	})
	data := header + "." + claims
	h := sha256.Sum256([]byte(data))
	sig, err := rsa.SignPKCS1v15(rand.Reader, s.key, crypto.SHA256, h[:])
	if err != nil {
		return "", err
	}
	return data + "." + base64.RawURLEncoding.EncodeToString(sig), nil
}

func base64urlJSON(v any) string {
	b, _ := json.Marshal(v)
	return base64.RawURLEncoding.EncodeToString(b)
}

func exchangeJWT(ctx context.Context, assertion string) (string, time.Time, error) {
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		"https://oauth2.googleapis.com/token",
		strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", time.Time{}, err
	}
	defer resp.Body.Close()
	var result struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
		Error       string `json:"error"`
		ErrorDesc   string `json:"error_description"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", time.Time{}, err
	}
	if result.Error != "" {
		return "", time.Time{}, fmt.Errorf("token exchange: %s: %s", result.Error, result.ErrorDesc)
	}
	expAt := time.Now().Add(time.Duration(result.ExpiresIn) * time.Second)
	return result.AccessToken, expAt, nil
}
