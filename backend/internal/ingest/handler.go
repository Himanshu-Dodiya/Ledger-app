// Package ingest implements POST /v1/ingest/sms.
// It mirrors the web app's /api/sms route: parse → LLM fallback → dedupe → insert.
package ingest

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/categorize"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/devices"
	"github.com/himanshu/ledger-api/internal/httpx"
	"github.com/himanshu/ledger-api/internal/llm"
	"github.com/himanshu/ledger-api/internal/model"
	"github.com/himanshu/ledger-api/internal/notify"
	"github.com/himanshu/ledger-api/internal/parser"
)

// Handler holds ingest dependencies.
type Handler struct {
	pool     *db.Pool
	notifier *notify.Sender
}

func NewHandler(pool *db.Pool, notifier *notify.Sender) *Handler {
	return &Handler{pool: pool, notifier: notifier}
}

// Register wires ingest endpoints into mux under authMW.
func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/ingest/sms", authMW(http.HandlerFunc(h.sms)))
}

type smsReq struct {
	Text      string `json:"text"`
	Sender    string `json:"sender"`
	Timestamp int64  `json:"timestamp"` // epoch ms; 0 = now
}

type ingestResp struct {
	Inserted bool   `json:"inserted"`
	Reason   string `json:"reason,omitempty"`
	ID       string `json:"id,omitempty"`
}

func (h *Handler) sms(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())

	var req smsReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	if req.Text == "" {
		httpx.BadRequest(w, "text is required")
		return
	}

	resp, err := ingestSMS(r.Context(), h.pool, uid, req)
	if err != nil {
		httpx.InternalError(w)
		return
	}

	// Push a notification if we actually inserted a new transaction.
	if resp.Inserted {
		go func() {
			tokens, err := devices.GetTokens(r.Context(), h.pool, uid)
			if err == nil && len(tokens) > 0 {
				h.notifier.Send(r.Context(), tokens,
					"New SMS transaction",
					"A new transaction was detected and is waiting for review.",
				)
			}
		}()
	}

	httpx.OK(w, resp)
}

func ingestSMS(ctx context.Context, pool *db.Pool, userID string, req smsReq) (ingestResp, error) {
	in := parser.Input{From: req.Sender, Body: req.Text}
	parsed := parser.ParseText(in)

	// Regex missed it but text still looks financial → try the LLM.
	if (!parsed.IsTransaction || parsed.Amount == nil) && parser.MightBeFinancial("", req.Text) {
		llmResult, err := llm.Parse(ctx, req.Text)
		if err == nil && llmResult.IsTransaction && llmResult.Amount != nil {
			parsed = llmResult
		}
	}

	if !parsed.IsTransaction || parsed.Amount == nil {
		return ingestResp{Inserted: false, Reason: "not a transaction"}, nil
	}

	// Determine transaction date
	txnDate := ""
	if parsed.Date != nil {
		txnDate = *parsed.Date
	} else if req.Timestamp > 0 {
		txnDate = time.UnixMilli(req.Timestamp).Format("2006-01-02")
	} else {
		txnDate = time.Now().Format("2006-01-02")
	}

	merchantNorm := categorize.NormalizeMerchant(func() string {
		if parsed.Merchant != nil {
			return *parsed.Merchant
		}
		return ""
	}())

	// Load user merchant rules for category resolution.
	userRules, err := loadUserRules(ctx, pool, userID)
	if err != nil {
		return ingestResp{}, fmt.Errorf("load rules: %w", err)
	}

	category := categorize.ResolveCategory(parsed, merchantNorm, userRules)
	hash := categorize.DedupeHash(
		*parsed.Amount, txnDate, merchantNorm,
		parsed.ReferenceID,
		string(parsed.Direction),
		req.Text, // fallback discriminator for symbol-less, merchant-less SMS
	)

	id, err := insertSMS(ctx, pool, userID, parsed, merchantNorm, category, txnDate, hash, req.Text)
	if err != nil {
		if isDuplicate(err) {
			return ingestResp{Inserted: false, Reason: "duplicate"}, nil
		}
		return ingestResp{}, fmt.Errorf("insert: %w", err)
	}
	return ingestResp{Inserted: true, ID: id}, nil
}

func loadUserRules(ctx context.Context, pool *db.Pool, userID string) (map[string]string, error) {
	rows, err := pool.Query(ctx, `SELECT merchant_normalized, category FROM merchant_rules WHERE user_id = $1`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	rules := map[string]string{}
	for rows.Next() {
		var mn, cat string
		if err := rows.Scan(&mn, &cat); err != nil {
			return nil, err
		}
		rules[mn] = cat
	}
	return rules, rows.Err()
}

func insertSMS(ctx context.Context, pool *db.Pool, userID string, parsed model.ParsedTransaction, merchantNorm, category, txnDate, hash, rawText string) (string, error) {
	merchant := ""
	if parsed.Merchant != nil {
		merchant = *parsed.Merchant
	}
	pm := ""
	if parsed.PaymentMethod != nil {
		pm = *parsed.PaymentMethod
	}
	refID := (*string)(nil)
	if parsed.ReferenceID != nil {
		refID = parsed.ReferenceID
	}
	snippet := rawText
	if len(snippet) > 280 {
		snippet = snippet[:280]
	}

	var id string
	err := pool.QueryRow(ctx, `
		INSERT INTO transactions
		  (user_id, amount, currency, direction, merchant_raw, merchant_normalized,
		   category, payment_method, txn_date, reference_id, source, dedupe_hash,
		   raw_snippet, reviewed)
		VALUES ($1,$2,'INR',$3,$4,$5,$6,$7,$8,$9,'sms',$10,$11,false)
		RETURNING id`,
		userID, *parsed.Amount, string(parsed.Direction),
		merchant, merchantNorm,
		category, pm, txnDate, refID, hash, snippet,
	).Scan(&id)
	return id, err
}

func isDuplicate(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	for i := 0; i <= len(s)-5; i++ {
		if s[i:i+5] == "23505" {
			return true
		}
	}
	return false
}
