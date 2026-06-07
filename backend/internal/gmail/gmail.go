// Package gmail is a Go port of gmail.ts + sync.ts.
// It handles OAuth token exchange, Gmail REST calls, and the per-user sync loop.
package gmail

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const gmailBase = "https://gmail.googleapis.com/gmail/v1/users/me"

var httpClient = &http.Client{Timeout: 20 * time.Second}

// ExchangeServerAuthCode exchanges the serverAuthCode from the Android Google Authorization
// API for a refresh token (used once; the code is single-use).
func ExchangeServerAuthCode(ctx context.Context, serverAuthCode string) (refreshToken string, err error) {
	data := url.Values{
		"code":          {serverAuthCode},
		"client_id":     {os.Getenv("GOOGLE_CLIENT_ID")},
		"client_secret": {os.Getenv("GOOGLE_CLIENT_SECRET")},
		"redirect_uri":  {"postmessage"}, // required for server-side code flow
		"grant_type":    {"authorization_code"},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://oauth2.googleapis.com/token", strings.NewReader(data.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("token exchange failed: %d %s", resp.StatusCode, raw)
	}
	var out struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("parse token response: %w", err)
	}
	if out.RefreshToken == "" {
		return "", fmt.Errorf("no refresh_token in response (code already used?)")
	}
	return out.RefreshToken, nil
}

// getAccessToken exchanges a refresh token for a short-lived access token.
func getAccessToken(ctx context.Context, refreshToken string) (string, error) {
	data := url.Values{
		"client_id":     {os.Getenv("GOOGLE_CLIENT_ID")},
		"client_secret": {os.Getenv("GOOGLE_CLIENT_SECRET")},
		"refresh_token": {refreshToken},
		"grant_type":    {"refresh_token"},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://oauth2.googleapis.com/token", strings.NewReader(data.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("token refresh failed: %d %s", resp.StatusCode, raw)
	}
	var out struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("parse token: %w", err)
	}
	return out.AccessToken, nil
}

// ListMessageIDs returns Gmail message IDs matching the transactional keyword query,
// paged up to a safety cap of 500 per run (mirrors listMessageIds in gmail.ts).
func ListMessageIDs(ctx context.Context, accessToken string, afterEpochSec int64) ([]string, error) {
	q := fmt.Sprintf("after:%d -in:chats (debited OR credited OR debit OR credit OR spent OR paid OR payment OR transaction OR txn OR purchase OR received OR withdrawn OR UPI OR IMPS OR NEFT OR RTGS OR card)", afterEpochSec)

	var ids []string
	pageToken := ""
	for {
		u, _ := url.Parse(gmailBase + "/messages")
		p := u.Query()
		p.Set("q", q)
		p.Set("maxResults", "100")
		if pageToken != "" {
			p.Set("pageToken", pageToken)
		}
		u.RawQuery = p.Encode()

		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		req.Header.Set("Authorization", "Bearer "+accessToken)
		resp, err := httpClient.Do(req)
		if err != nil {
			return nil, err
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("gmail list: %d %s", resp.StatusCode, raw)
		}
		var out struct {
			Messages      []struct{ ID string `json:"id"` } `json:"messages"`
			NextPageToken string                             `json:"nextPageToken"`
		}
		if err := json.Unmarshal(raw, &out); err != nil {
			return nil, err
		}
		for _, m := range out.Messages {
			ids = append(ids, m.ID)
		}
		if out.NextPageToken == "" || len(ids) >= 500 {
			break
		}
		pageToken = out.NextPageToken
	}
	return ids, nil
}

// Message holds the extracted fields from a Gmail message.
type Message struct {
	ID           string
	From         string
	Subject      string
	Body         string
	InternalDate int64 // epoch ms
}

// GetMessage fetches and decodes a single Gmail message (full format).
func GetMessage(ctx context.Context, accessToken, id string) (*Message, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, fmt.Sprintf("%s/messages/%s?format=full", gmailBase, id), nil)
	req.Header.Set("Authorization", "Bearer "+accessToken)
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("gmail get %s: %d", id, resp.StatusCode)
	}

	var data map[string]json.RawMessage
	if err := json.Unmarshal(raw, &data); err != nil {
		return nil, err
	}
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(data["payload"], &payload); err != nil {
		return nil, err
	}

	// Headers
	var headers []struct {
		Name  string `json:"name"`
		Value string `json:"value"`
	}
	_ = json.Unmarshal(payload["headers"], &headers)

	from, subject := "", ""
	for _, h := range headers {
		switch strings.ToLower(h.Name) {
		case "from":
			from = h.Value
		case "subject":
			subject = h.Value
		}
	}

	body := extractBody(payload)
	body = strings.Join(strings.Fields(body), " ")

	var internalDate int64
	var rawDate string
	if err := json.Unmarshal(data["internalDate"], &rawDate); err == nil {
		fmt.Sscanf(rawDate, "%d", &internalDate)
	}

	return &Message{
		ID:           id,
		From:         from,
		Subject:      subject,
		Body:         body,
		InternalDate: internalDate,
	}, nil
}

// ---- MIME helpers (port of gmail.ts) ----

func extractBody(payload map[string]json.RawMessage) string {
	var plain, htmlParts []string
	collectParts(payload, &plain, &htmlParts)
	if p := strings.Join(plain, "\n"); strings.TrimSpace(p) != "" {
		return p
	}
	if len(htmlParts) > 0 {
		return htmlToText(strings.Join(htmlParts, "\n"))
	}
	return ""
}

func collectParts(payload map[string]json.RawMessage, plain, htmlList *[]string) {
	if payload == nil {
		return
	}
	var mimeType string
	_ = json.Unmarshal(payload["mimeType"], &mimeType)

	var body map[string]json.RawMessage
	if err := json.Unmarshal(payload["body"], &body); err == nil {
		if rawData, ok := body["data"]; ok {
			var encoded string
			_ = json.Unmarshal(rawData, &encoded)
			if encoded != "" {
				decoded := decodeB64(encoded)
				switch mimeType {
				case "text/plain":
					*plain = append(*plain, decoded)
				case "text/html":
					*htmlList = append(*htmlList, decoded)
				default:
					if !strings.HasPrefix(mimeType, "multipart/") {
						*plain = append(*plain, decoded)
					}
				}
			}
		}
	}

	var parts []json.RawMessage
	_ = json.Unmarshal(payload["parts"], &parts)
	for _, p := range parts {
		var child map[string]json.RawMessage
		if err := json.Unmarshal(p, &child); err == nil {
			collectParts(child, plain, htmlList)
		}
	}
}

func decodeB64(s string) string {
	s = strings.ReplaceAll(s, "-", "+")
	s = strings.ReplaceAll(s, "_", "/")
	b, _ := base64.StdEncoding.DecodeString(s)
	return string(b)
}

func htmlToText(html string) string {
	// Strip style and script blocks first (they dominate the early KB of bank emails).
	html = stripTag(html, "style")
	html = stripTag(html, "script")
	// Remove remaining tags.
	var out strings.Builder
	inTag := false
	for _, r := range html {
		switch {
		case r == '<':
			inTag = true
		case r == '>':
			inTag = false
			out.WriteByte(' ')
		case !inTag:
			out.WriteRune(r)
		}
	}
	// Decode common entities.
	s := out.String()
	s = strings.ReplaceAll(s, "&nbsp;", " ")
	s = strings.ReplaceAll(s, "&amp;", "&")
	s = strings.ReplaceAll(s, "&lt;", "<")
	s = strings.ReplaceAll(s, "&gt;", ">")
	s = strings.ReplaceAll(s, "&#39;", "'")
	s = strings.ReplaceAll(s, "&apos;", "'")
	s = strings.ReplaceAll(s, "&quot;", `"`)
	return s
}

func stripTag(html, tag string) string {
	open := "<" + tag
	close := "</" + tag + ">"
	for {
		start := strings.Index(strings.ToLower(html), open)
		if start < 0 {
			break
		}
		end := strings.Index(strings.ToLower(html[start:]), close)
		if end < 0 {
			break
		}
		html = html[:start] + " " + html[start+end+len(close):]
	}
	return html
}
