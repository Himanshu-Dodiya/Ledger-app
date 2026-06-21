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
	"github.com/himanshu/ledger-api/internal/model"
)

// POST /v1/ingest/batch — bulk import of already-parsed transactions (PDF statements,
// CSV, etc). The client does the source-specific parsing on-device and posts a normalized
// list of rows; this endpoint runs each row through the SAME categorize + dedupe + insert
// path as SMS ingest, so a PDF row and the matching SMS/Gmail row collapse to one
// transaction via reference_id.
//
// Imported rows are stored reviewed=true: they are confirmed historical transactions, not
// candidates that need triage, so they must not flood the Review inbox.

type batchRow struct {
	Amount          float64 `json:"amount"`
	Direction       string  `json:"direction"` // "debit" | "credit"
	Merchant        string  `json:"merchant"`
	CounterpartyUPI string  `json:"counterparty_upi"`
	BankAccount     string  `json:"bank_account"`
	PaymentMethod   string  `json:"payment_method"`
	ReferenceID     string  `json:"reference_id"`
	TxnDate         string  `json:"txn_date"` // YYYY-MM-DD
	TxnTime         string  `json:"txn_time"` // RFC3339, optional
	Note            string  `json:"note"`
	// Per-row source override; falls back to the batch-level source when empty.
	Source string `json:"source"`
}

type batchReq struct {
	Source   string     `json:"source"`    // gpay_pdf | paytm_pdf | bank_pdf | csv | ...
	FileName string     `json:"file_name"` // shown in import history
	Rows     []batchRow `json:"rows"`
}

type batchResp struct {
	BatchID    string `json:"batch_id"`
	Inserted   int    `json:"inserted"`
	Duplicates int    `json:"duplicates"`
	Errors     int    `json:"errors"`
}

func (h *Handler) batch(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())

	var req batchReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	if !model.IsValidSource(req.Source) {
		httpx.BadRequest(w, "unknown source")
		return
	}
	if len(req.Rows) == 0 {
		httpx.BadRequest(w, "no rows")
		return
	}

	resp, err := ingestBatch(r.Context(), h.pool, uid, req)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}

	// Notify only if we actually added something new from this import.
	if resp.Inserted > 0 && h.notifier != nil {
		go func() {
			tokens, err := devices.GetTokens(r.Context(), h.pool, uid)
			if err == nil && len(tokens) > 0 {
				h.notifier.Send(r.Context(), tokens,
					"Statement imported",
					fmt.Sprintf("%d new transactions added from %s.", resp.Inserted, req.Source),
				)
			}
		}()
	}

	httpx.OK(w, resp)
}

func ingestBatch(ctx context.Context, pool *db.Pool, userID string, req batchReq) (batchResp, error) {
	batchID, err := createImportBatch(ctx, pool, userID, req.Source, req.FileName)
	if err != nil {
		return batchResp{}, fmt.Errorf("create batch: %w", err)
	}

	rules, err := loadUserRules(ctx, pool, userID)
	if err != nil {
		return batchResp{}, fmt.Errorf("load rules: %w", err)
	}

	var out batchResp
	out.BatchID = batchID
	for _, row := range req.Rows {
		status, err := insertBatchRow(ctx, pool, userID, batchID, req.Source, row, rules)
		switch {
		case err != nil:
			out.Errors++
		case status == "duplicate":
			out.Duplicates++
		default:
			out.Inserted++
		}
	}

	_ = updateImportBatch(ctx, pool, batchID, out.Inserted, out.Duplicates, out.Errors)
	return out, nil
}

func insertBatchRow(ctx context.Context, pool *db.Pool, userID, batchID, batchSource string, row batchRow, rules map[string]string) (string, error) {
	if row.Amount <= 0 {
		return "", fmt.Errorf("non-positive amount")
	}

	source := row.Source
	if source == "" {
		source = batchSource
	}

	dir := model.Debit
	if row.Direction == "credit" {
		dir = model.Credit
	}

	txnDate := row.TxnDate
	if txnDate == "" {
		txnDate = time.Now().Format("2006-01-02")
	}

	merchantNorm := categorize.NormalizeMerchant(row.Merchant)

	// Resolve category through the same engine as SMS: user merchant rules first,
	// then the built-in heuristics (e.g. credit → Income).
	merchantPtr := &row.Merchant
	parsed := model.ParsedTransaction{
		IsTransaction: true,
		Direction:     dir,
		Merchant:      merchantPtr,
		Category:      "Uncategorized",
	}
	category := categorize.ResolveCategory(parsed, merchantNorm, rules)

	var refPtr *string
	if row.ReferenceID != "" {
		refPtr = &row.ReferenceID
	}
	// Dedupe key: prefers the UPI reference, so a statement row dedupes against the
	// SMS/Gmail row for the same transaction. Falls back to amount|date|merchant.
	hash := categorize.DedupeHash(row.Amount, txnDate, merchantNorm, refPtr, string(dir), "")

	var txnTimePtr *time.Time
	if row.TxnTime != "" {
		if t, err := time.Parse(time.RFC3339, row.TxnTime); err == nil {
			txnTimePtr = &t
		}
	}

	var id string
	err := pool.QueryRow(ctx, `
		INSERT INTO transactions
		  (user_id, amount, currency, direction, merchant_raw, merchant_normalized,
		   category, payment_method, txn_date, txn_time, reference_id, counterparty_upi,
		   bank_account, note, source, dedupe_hash, import_batch_id, reviewed)
		VALUES ($1,$2,'INR',$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,true)
		RETURNING id`,
		userID, row.Amount, string(dir), row.Merchant, merchantNorm,
		category, nullable(row.PaymentMethod), txnDate, txnTimePtr, refPtr,
		nullable(row.CounterpartyUPI), nullable(row.BankAccount), nullable(row.Note),
		source, hash, batchID,
	).Scan(&id)
	if err != nil {
		if isDuplicate(err) {
			return "duplicate", nil
		}
		return "", err
	}
	return id, nil
}

// nullable returns nil for empty strings so they store as SQL NULL, not "".
func nullable(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func createImportBatch(ctx context.Context, pool *db.Pool, userID, source, fileName string) (string, error) {
	var id string
	err := pool.QueryRow(ctx, `
		INSERT INTO import_batches (user_id, source, file_name)
		VALUES ($1, $2, $3) RETURNING id`,
		userID, source, nullable(fileName),
	).Scan(&id)
	return id, err
}

func updateImportBatch(ctx context.Context, pool *db.Pool, batchID string, inserted, duplicates, errors int) error {
	_, err := pool.Exec(ctx, `
		UPDATE import_batches SET inserted=$2, duplicates=$3, errors=$4 WHERE id=$1`,
		batchID, inserted, duplicates, errors)
	return err
}