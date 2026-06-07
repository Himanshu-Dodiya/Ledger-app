// Package llm is a Go port of llm.ts: calls Gemini Flash → Flash-Lite as fallback,
// using structured JSON output and thinking disabled. No-ops when GEMINI_API_KEY is unset.
package llm

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/himanshu/ledger-api/internal/model"
)

const (
	primaryModel  = "gemini-2.5-flash"
	fallbackModel = "gemini-2.5-flash-lite"
	endpoint      = "https://generativelanguage.googleapis.com/v1beta/models"
)

const systemPrompt = `You extract structured data from Indian bank, UPI, and credit/debit card transaction alerts (SMS or email).
Rules:
- If the message is NOT a real financial transaction (OTP, statement-ready notice, promo/offer, balance enquiry, login alert), set isTransaction=false and leave other fields null/default.
- amount is the transaction value as a positive number, no currency symbol or commas.
- direction is "debit" for money leaving the account (spent/paid/debited/withdrawn) and "credit" for money received (credited/refund/cashback/reversal).
- merchant is the counterparty/payee name or UPI handle, cleaned of trailing reference junk. Null if absent.
- date is the transaction date as YYYY-MM-DD. If the year is missing, assume the current year. Null if no date is present.
- referenceId is the UPI/bank reference or transaction id if present, else null.
- paymentMethod is one of UPI, Credit Card, Debit Card, Net Banking, Wallet, or null.
- category is your best guess from the allowed list.`

var httpClient = &http.Client{Timeout: 20 * time.Second}

// In-process cache: same text → same result within a process lifetime.
var (
	mu    sync.Mutex
	cache = map[string]model.ParsedTransaction{}
)

// responseSchema mirrors the TypeScript RESPONSE_SCHEMA passed to Gemini.
var responseSchema = map[string]any{
	"type": "OBJECT",
	"properties": map[string]any{
		"isTransaction": map[string]any{"type": "BOOLEAN"},
		"amount":        map[string]any{"type": "NUMBER", "nullable": true},
		"direction":     map[string]any{"type": "STRING", "enum": []string{"debit", "credit"}},
		"merchant":      map[string]any{"type": "STRING", "nullable": true},
		"paymentMethod": map[string]any{
			"type":     "STRING",
			"enum":     []string{"UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet"},
			"nullable": true,
		},
		"date":        map[string]any{"type": "STRING", "nullable": true, "description": "YYYY-MM-DD"},
		"referenceId": map[string]any{"type": "STRING", "nullable": true},
		"category":    map[string]any{"type": "STRING", "enum": model.Categories},
	},
	"required":         []string{"isTransaction", "amount", "direction", "category"},
	"propertyOrdering": []string{"isTransaction", "amount", "direction", "merchant", "paymentMethod", "date", "referenceId", "category"},
}

func callGemini(ctx context.Context, mdl, apiKey, text string) (model.ParsedTransaction, error) {
	if len(text) > 4000 {
		text = text[:4000]
	}
	reqBody := map[string]any{
		"systemInstruction": map[string]any{
			"parts": []map[string]any{{"text": systemPrompt}},
		},
		"contents": []map[string]any{
			{"role": "user", "parts": []map[string]any{{"text": text}}},
		},
		"generationConfig": map[string]any{
			"responseMimeType": "application/json",
			"responseSchema":   responseSchema,
			"temperature":      0,
			"maxOutputTokens":  512,
			"thinkingConfig":   map[string]any{"thinkingBudget": 0},
		},
	}
	bodyBytes, err := json.Marshal(reqBody)
	if err != nil {
		return model.Empty(), err
	}

	url := fmt.Sprintf("%s/%s:generateContent?key=%s", endpoint, mdl, apiKey)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(bodyBytes))
	if err != nil {
		return model.Empty(), err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := httpClient.Do(req)
	if err != nil {
		return model.Empty(), err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return model.Empty(), fmt.Errorf("gemini %s: HTTP %d: %s", mdl, resp.StatusCode, raw)
	}

	var out struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return model.Empty(), fmt.Errorf("parse response: %w", err)
	}
	if len(out.Candidates) == 0 || len(out.Candidates[0].Content.Parts) == 0 {
		return model.Empty(), fmt.Errorf("empty candidates from %s", mdl)
	}

	var data map[string]any
	if err := json.Unmarshal([]byte(out.Candidates[0].Content.Parts[0].Text), &data); err != nil {
		return model.Empty(), fmt.Errorf("parse JSON part: %w", err)
	}
	return coerce(data), nil
}

// coerce converts the raw Gemini JSON object into a ParsedTransaction, applying the same
// sanitization as the TypeScript coerce() function.
func coerce(m map[string]any) model.ParsedTransaction {
	p := model.Empty()
	if v, ok := m["isTransaction"].(bool); ok {
		p.IsTransaction = v
	}
	if v, ok := m["amount"].(float64); ok && v > 0 {
		p.Amount = &v
	}
	if v, ok := m["direction"].(string); ok && v == "credit" {
		p.Direction = model.Credit
	}
	if v, ok := m["merchant"].(string); ok {
		if s := strings.TrimSpace(v); s != "" {
			p.Merchant = &s
		}
	}
	if v, ok := m["paymentMethod"].(string); ok && v != "" {
		p.PaymentMethod = &v
	}
	if v, ok := m["date"].(string); ok && len(v) == 10 && v[4] == '-' && v[7] == '-' {
		p.Date = &v
	}
	if v, ok := m["referenceId"].(string); ok {
		if s := strings.TrimSpace(v); s != "" {
			p.ReferenceID = &s
		}
	}
	if v, ok := m["category"].(string); ok && model.IsValidCategory(v) {
		p.Category = v
	}
	return p
}

// Parse calls Gemini with Flash → Flash-Lite fallback and returns a ParsedTransaction.
// Returns model.Empty() (without error) when GEMINI_API_KEY is unset.
// Results are cached by SHA-256 of the input text.
func Parse(ctx context.Context, text string) (model.ParsedTransaction, error) {
	apiKey := os.Getenv("GEMINI_API_KEY")
	if apiKey == "" {
		return model.Empty(), nil
	}

	h := sha256.Sum256([]byte(text))
	cacheKey := hex.EncodeToString(h[:])

	mu.Lock()
	if cached, ok := cache[cacheKey]; ok {
		mu.Unlock()
		return cached, nil
	}
	mu.Unlock()

	result, err := callGemini(ctx, primaryModel, apiKey, text)
	if err != nil {
		var err2 error
		result, err2 = callGemini(ctx, fallbackModel, apiKey, text)
		if err2 != nil {
			return model.Empty(), fmt.Errorf("llm primary (%v); fallback: %w", err, err2)
		}
	}

	mu.Lock()
	cache[cacheKey] = result
	mu.Unlock()
	return result, nil
}
