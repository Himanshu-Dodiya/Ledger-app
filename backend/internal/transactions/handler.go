// Package transactions implements the /v1/transactions and /v1/dashboard endpoints.
package transactions

import (
	"context"
	"net/http"
	"strconv"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/categorize"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
	"github.com/himanshu/ledger-api/internal/model"
)

// Transaction is the JSON shape returned to the Android app.
type Transaction struct {
	ID                 string   `json:"id"`
	Amount             float64  `json:"amount"`
	Currency           string   `json:"currency"`
	Direction          string   `json:"direction"`
	MerchantRaw        *string  `json:"merchant_raw"`
	MerchantNormalized *string  `json:"merchant_normalized"`
	Category           string   `json:"category"`
	PaymentMethod      *string  `json:"payment_method"`
	TxnDate            string   `json:"txn_date"`
	ReferenceID        *string  `json:"reference_id"`
	Source             string   `json:"source"`
	GmailMessageID     *string  `json:"gmail_message_id"`
	RawSnippet         *string  `json:"raw_snippet"`
	Reviewed           bool     `json:"reviewed"`
	CreatedAt          string   `json:"created_at"`
}

// Handler holds dependencies for the transactions endpoints.
type Handler struct {
	pool *db.Pool
}

func NewHandler(pool *db.Pool) *Handler {
	return &Handler{pool: pool}
}

// Register wires the handler into mux under authMW.
func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/transactions", authMW(http.HandlerFunc(h.list)))
	mux.Handle("POST /v1/transactions", authMW(http.HandlerFunc(h.create)))
	mux.Handle("PATCH /v1/transactions/{id}", authMW(http.HandlerFunc(h.patch)))
	mux.Handle("DELETE /v1/transactions/{id}", authMW(http.HandlerFunc(h.delete)))
	mux.Handle("GET /v1/dashboard", authMW(http.HandlerFunc(h.dashboard)))
	mux.Handle("GET /v1/categories", authMW(http.HandlerFunc(h.categories)))
}

// ---- list ----

// list handles GET /v1/transactions
// Supported query params: reviewed, source, category, from, to, q, cursor, limit
func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	q := r.URL.Query()

	limit := 50
	if l, err := strconv.Atoi(q.Get("limit")); err == nil && l > 0 && l <= 200 {
		limit = l
	}

	rows, err := listTransactions(r.Context(), h.pool, uid, listParams{
		Reviewed: q.Get("reviewed"),
		Source:   q.Get("source"),
		Category: q.Get("category"),
		From:     q.Get("from"),
		To:       q.Get("to"),
		Q:        q.Get("q"),
		Cursor:   q.Get("cursor"),
		Limit:    limit,
	})
	if err != nil {
		httpx.InternalError(w)
		return
	}
	httpx.OK(w, rows)
}

// ---- create ----

type createReq struct {
	Amount        float64 `json:"amount"`
	Direction     string  `json:"direction"`
	MerchantRaw   string  `json:"merchant_raw"`
	Category      string  `json:"category"`
	TxnDate       string  `json:"txn_date"`
	Source        string  `json:"source"`
	PaymentMethod string  `json:"payment_method"`
	ReferenceID   string  `json:"reference_id"`
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	var req createReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	if req.Amount <= 0 {
		httpx.BadRequest(w, "amount must be positive")
		return
	}
	if req.TxnDate == "" {
		req.TxnDate = time.Now().Format("2006-01-02")
	}
	if req.Source == "" {
		req.Source = model.SourceManual
	}
	if !model.IsValidCategory(req.Category) {
		req.Category = "Uncategorized"
	}

	merchantNorm := categorize.NormalizeMerchant(req.MerchantRaw)
	var refPtr *string
	if req.ReferenceID != "" {
		refPtr = &req.ReferenceID
	}
	dir := req.Direction
	if dir != "credit" {
		dir = "debit"
	}
	hash := categorize.DedupeHash(req.Amount, req.TxnDate, merchantNorm, refPtr, dir, "")

	txn, err := insertTransaction(r.Context(), h.pool, insertParams{
		UserID:             uid,
		Amount:             req.Amount,
		Direction:          dir,
		MerchantRaw:        req.MerchantRaw,
		MerchantNormalized: merchantNorm,
		Category:           req.Category,
		PaymentMethod:      req.PaymentMethod,
		TxnDate:            req.TxnDate,
		ReferenceID:        refPtr,
		Source:             req.Source,
		DedupeHash:         hash,
		Reviewed:           true, // manual transactions are pre-reviewed
	})
	if err != nil {
		if isDuplicate(err) {
			httpx.Conflict(w, "duplicate transaction")
			return
		}
		httpx.InternalError(w)
		return
	}
	httpx.Created(w, txn)
}

// ---- patch ----

type patchReq struct {
	Category    *string `json:"category"`
	Reviewed    *bool   `json:"reviewed"`
	MerchantRaw *string `json:"merchant_raw"`
}

func (h *Handler) patch(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")

	var req patchReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}

	// If category is being set, validate it and upsert a merchant rule.
	if req.Category != nil && !model.IsValidCategory(*req.Category) {
		httpx.BadRequest(w, "invalid category")
		return
	}

	txn, err := patchTransaction(r.Context(), h.pool, uid, id, req.Category, req.Reviewed, req.MerchantRaw)
	if err != nil {
		if isNotFound(err) {
			httpx.NotFound(w)
			return
		}
		httpx.InternalError(w)
		return
	}

	// When a category is being assigned, upsert a merchant rule so future transactions
	// from the same merchant are auto-categorized (mirrors the web app behaviour).
	if req.Category != nil && txn.MerchantNormalized != nil && *txn.MerchantNormalized != "unknown" {
		_ = upsertMerchantRule(r.Context(), h.pool, uid, *txn.MerchantNormalized, *req.Category)
	}

	httpx.OK(w, txn)
}

// ---- delete ----

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	if err := deleteTransaction(r.Context(), h.pool, uid, id); err != nil {
		if isNotFound(err) {
			httpx.NotFound(w)
			return
		}
		httpx.InternalError(w)
		return
	}
	httpx.OK(w, map[string]bool{"deleted": true})
}

// ---- dashboard ----

type DashboardResp struct {
	Month         string            `json:"month"`  // YYYY-MM
	TotalSpend    float64           `json:"total_spend"`
	TotalIncome   float64           `json:"total_income"`
	ByCategory    map[string]float64 `json:"by_category"`
	TopMerchants  []MerchantTotal   `json:"top_merchants"`
	ToReviewCount int               `json:"to_review_count"`
	RecentTxns    []Transaction     `json:"recent_transactions"`
}

type MerchantTotal struct {
	Merchant string  `json:"merchant"`
	Total    float64 `json:"total"`
}

func (h *Handler) dashboard(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	month := r.URL.Query().Get("month") // YYYY-MM; defaults to current
	if month == "" {
		month = time.Now().Format("2006-01")
	}

	resp, err := buildDashboard(r.Context(), h.pool, uid, month)
	if err != nil {
		httpx.InternalError(w)
		return
	}
	httpx.OK(w, resp)
}

// ---- categories ----

func (h *Handler) categories(w http.ResponseWriter, r *http.Request) {
	httpx.OK(w, model.Categories)
}

// ---- DB helpers ----
// These wrap raw pgx calls. Kept here (not in a separate repo layer) for now
// since we don't yet have sqlc — they'll migrate once Task 3 DB layer is complete.

type listParams struct {
	Reviewed, Source, Category, From, To, Q, Cursor string
	Limit                                             int
}

func listTransactions(ctx context.Context, pool *db.Pool, userID string, p listParams) ([]Transaction, error) {
	args := []any{userID}
	sql := `SELECT id, amount, currency, direction, merchant_raw, merchant_normalized,
	               category, payment_method, txn_date, reference_id, source,
	               gmail_message_id, raw_snippet, reviewed, created_at
	        FROM transactions WHERE user_id = $1`

	idx := 2
	if p.Reviewed != "" {
		rev := p.Reviewed == "true"
		sql += ` AND reviewed = $` + strconv.Itoa(idx)
		args = append(args, rev)
		idx++
	}
	if p.Source != "" {
		sql += ` AND source = $` + strconv.Itoa(idx)
		args = append(args, p.Source)
		idx++
	}
	if p.Category != "" {
		sql += ` AND category = $` + strconv.Itoa(idx)
		args = append(args, p.Category)
		idx++
	}
	if p.From != "" {
		sql += ` AND txn_date >= $` + strconv.Itoa(idx)
		args = append(args, p.From)
		idx++
	}
	if p.To != "" {
		sql += ` AND txn_date <= $` + strconv.Itoa(idx)
		args = append(args, p.To)
		idx++
	}
	if p.Q != "" {
		sql += ` AND (merchant_raw ILIKE $` + strconv.Itoa(idx) + ` OR merchant_normalized ILIKE $` + strconv.Itoa(idx) + `)`
		args = append(args, "%"+p.Q+"%")
		idx++
	}
	if p.Cursor != "" {
		sql += ` AND created_at < $` + strconv.Itoa(idx)
		args = append(args, p.Cursor)
		idx++
	}
	sql += ` ORDER BY created_at DESC LIMIT $` + strconv.Itoa(idx)
	args = append(args, p.Limit)

	rows, err := pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	txns := make([]Transaction, 0) // never nil — marshals as [] not null
	for rows.Next() {
		var t Transaction
		var txnDate time.Time
		var createdAt time.Time
		if err := rows.Scan(
			&t.ID, &t.Amount, &t.Currency, &t.Direction,
			&t.MerchantRaw, &t.MerchantNormalized,
			&t.Category, &t.PaymentMethod,
			&txnDate, &t.ReferenceID,
			&t.Source, &t.GmailMessageID, &t.RawSnippet,
			&t.Reviewed, &createdAt,
		); err != nil {
			return nil, err
		}
		t.TxnDate = txnDate.Format("2006-01-02")
		t.CreatedAt = createdAt.Format(time.RFC3339)
		txns = append(txns, t)
	}
	return txns, rows.Err()
}

type insertParams struct {
	UserID, Direction, MerchantRaw, MerchantNormalized string
	Category, PaymentMethod, TxnDate, Source, DedupeHash string
	ReferenceID *string
	Amount      float64
	Reviewed    bool
}

func insertTransaction(ctx context.Context, pool *db.Pool, p insertParams) (*Transaction, error) {
	row := pool.QueryRow(ctx, `
		INSERT INTO transactions
		  (user_id, amount, currency, direction, merchant_raw, merchant_normalized,
		   category, payment_method, txn_date, reference_id, source, dedupe_hash, reviewed)
		VALUES ($1,$2,'INR',$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		RETURNING id, amount, currency, direction, merchant_raw, merchant_normalized,
		          category, payment_method, txn_date, reference_id, source,
		          gmail_message_id, raw_snippet, reviewed, created_at`,
		p.UserID, p.Amount, p.Direction, p.MerchantRaw, p.MerchantNormalized,
		p.Category, p.PaymentMethod, p.TxnDate, p.ReferenceID, p.Source, p.DedupeHash, p.Reviewed,
	)
	var t Transaction
	var txnDate, createdAt time.Time
	if err := row.Scan(
		&t.ID, &t.Amount, &t.Currency, &t.Direction,
		&t.MerchantRaw, &t.MerchantNormalized,
		&t.Category, &t.PaymentMethod,
		&txnDate, &t.ReferenceID,
		&t.Source, &t.GmailMessageID, &t.RawSnippet,
		&t.Reviewed, &createdAt,
	); err != nil {
		return nil, err
	}
	t.TxnDate = txnDate.Format("2006-01-02")
	t.CreatedAt = createdAt.Format(time.RFC3339)
	return &t, nil
}

func patchTransaction(ctx context.Context, pool *db.Pool, userID, id string, category *string, reviewed *bool, merchantRaw *string) (*Transaction, error) {
	args := []any{userID, id}
	sets := []string{}
	idx := 3
	if category != nil {
		sets = append(sets, "category = $"+strconv.Itoa(idx))
		args = append(args, *category)
		idx++
	}
	if reviewed != nil {
		sets = append(sets, "reviewed = $"+strconv.Itoa(idx))
		args = append(args, *reviewed)
		idx++
	}
	if merchantRaw != nil {
		sets = append(sets, "merchant_raw = $"+strconv.Itoa(idx))
		args = append(args, *merchantRaw)
		idx++
		normVal := categorize.NormalizeMerchant(*merchantRaw)
		sets = append(sets, "merchant_normalized = $"+strconv.Itoa(idx))
		args = append(args, normVal)
		idx++
	}
	if len(sets) == 0 {
		// nothing to patch — fetch and return current
	}

	sql := `UPDATE transactions SET `
	for i, s := range sets {
		if i > 0 {
			sql += ", "
		}
		sql += s
	}
	sql += ` WHERE user_id = $1 AND id = $2
		RETURNING id, amount, currency, direction, merchant_raw, merchant_normalized,
		          category, payment_method, txn_date, reference_id, source,
		          gmail_message_id, raw_snippet, reviewed, created_at`

	row := pool.QueryRow(ctx, sql, args...)
	var t Transaction
	var txnDate, createdAt time.Time
	if err := row.Scan(
		&t.ID, &t.Amount, &t.Currency, &t.Direction,
		&t.MerchantRaw, &t.MerchantNormalized,
		&t.Category, &t.PaymentMethod,
		&txnDate, &t.ReferenceID,
		&t.Source, &t.GmailMessageID, &t.RawSnippet,
		&t.Reviewed, &createdAt,
	); err != nil {
		return nil, err
	}
	t.TxnDate = txnDate.Format("2006-01-02")
	t.CreatedAt = createdAt.Format(time.RFC3339)
	return &t, nil
}

func deleteTransaction(ctx context.Context, pool *db.Pool, userID, id string) error {
	tag, err := pool.Exec(ctx, `DELETE FROM transactions WHERE user_id = $1 AND id = $2`, userID, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errNotFound
	}
	return nil
}

func upsertMerchantRule(ctx context.Context, pool *db.Pool, userID, merchantNorm, category string) error {
	_, err := pool.Exec(ctx, `
		INSERT INTO merchant_rules (user_id, merchant_normalized, category)
		VALUES ($1, $2, $3)
		ON CONFLICT (user_id, merchant_normalized) DO UPDATE SET category = EXCLUDED.category`,
		userID, merchantNorm, category,
	)
	return err
}

func buildDashboard(ctx context.Context, pool *db.Pool, userID, month string) (*DashboardResp, error) {
	resp := &DashboardResp{Month: month, ByCategory: map[string]float64{}}

	// totals
	row := pool.QueryRow(ctx, `
		SELECT
		  COALESCE(SUM(CASE WHEN direction='debit'  THEN amount ELSE 0 END), 0),
		  COALESCE(SUM(CASE WHEN direction='credit' THEN amount ELSE 0 END), 0)
		FROM transactions
		WHERE user_id = $1
		  AND to_char(txn_date, 'YYYY-MM') = $2
		  AND reviewed = true`, userID, month)
	if err := row.Scan(&resp.TotalSpend, &resp.TotalIncome); err != nil {
		return nil, err
	}

	// by category (debit only)
	catRows, err := pool.Query(ctx, `
		SELECT category, SUM(amount)
		FROM transactions
		WHERE user_id = $1
		  AND to_char(txn_date, 'YYYY-MM') = $2
		  AND direction = 'debit'
		  AND reviewed = true
		GROUP BY category`, userID, month)
	if err != nil {
		return nil, err
	}
	defer catRows.Close()
	for catRows.Next() {
		var cat string
		var sum float64
		if err := catRows.Scan(&cat, &sum); err != nil {
			return nil, err
		}
		resp.ByCategory[cat] = sum
	}

	// top 5 merchants
	mRows, err := pool.Query(ctx, `
		SELECT COALESCE(merchant_normalized, merchant_raw, 'Unknown'), SUM(amount)
		FROM transactions
		WHERE user_id = $1
		  AND to_char(txn_date, 'YYYY-MM') = $2
		  AND direction = 'debit'
		  AND reviewed = true
		GROUP BY 1 ORDER BY 2 DESC LIMIT 5`, userID, month)
	if err != nil {
		return nil, err
	}
	defer mRows.Close()
	for mRows.Next() {
		var m MerchantTotal
		if err := mRows.Scan(&m.Merchant, &m.Total); err != nil {
			return nil, err
		}
		resp.TopMerchants = append(resp.TopMerchants, m)
	}

	// to-review count
	row2 := pool.QueryRow(ctx, `SELECT COUNT(*) FROM transactions WHERE user_id = $1 AND reviewed = false`, userID)
	if err := row2.Scan(&resp.ToReviewCount); err != nil {
		return nil, err
	}

	// recent 5 transactions
	recent, err := listTransactions(ctx, pool, userID, listParams{Limit: 5})
	if err != nil {
		return nil, err
	}
	resp.RecentTxns = recent

	return resp, nil
}

// ---- sentinel errors ----

type sentinelErr string

func (e sentinelErr) Error() string { return string(e) }

const errNotFound sentinelErr = "not found"

func isDuplicate(err error) bool {
	if err == nil {
		return false
	}
	// pgx wraps the Postgres error code in the message
	return containsCode(err, "23505")
}

func isNotFound(err error) bool { return err == errNotFound }

func containsCode(err error, code string) bool {
	if err == nil {
		return false
	}
	return len(err.Error()) > 0 && (func() bool {
		s := err.Error()
		for i := 0; i <= len(s)-len(code); i++ {
			if s[i:i+len(code)] == code {
				return true
			}
		}
		return false
	})()
}
