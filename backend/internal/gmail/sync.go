package gmail

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/himanshu/ledger-api/internal/categorize"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/llm"
	"github.com/himanshu/ledger-api/internal/parser"
)

// SyncResult mirrors the web app's SyncResult from sync.ts.
type SyncResult struct {
	Fetched  int `json:"fetched"`
	Inserted int `json:"inserted"`
	Skipped  int `json:"skipped"`
	Errors   int `json:"errors"`
	LLM      int `json:"llm"` // transactions recovered by LLM fallback
}

const llmBudget = 25

// SyncUser runs a full sync cycle for one user: fetches new Gmail messages,
// parses them, and inserts transactions. Mirrors syncUser() from sync.ts.
func SyncUser(ctx context.Context, pool *db.Pool, userID string) (SyncResult, error) {
	result := SyncResult{}

	// Load the gmail_connections row.
	var encRefToken string
	var lastInternalDate int64
	err := pool.QueryRow(ctx,
		`SELECT refresh_token_enc, COALESCE(last_internal_date, 0)
		 FROM gmail_connections WHERE user_id = $1`, userID,
	).Scan(&encRefToken, &lastInternalDate)
	if err != nil {
		return result, fmt.Errorf("no gmail connection: %w", err)
	}

	refreshToken, err := DecryptToken(encRefToken)
	if err != nil {
		return result, fmt.Errorf("decrypt token: %w", err)
	}

	accessToken, err := getAccessToken(ctx, refreshToken)
	if err != nil {
		return result, fmt.Errorf("get access token: %w", err)
	}

	// Cursor: start 60 s before the last seen message to catch near-edge messages.
	// On first run, go back 90 days.
	var afterSec int64
	if lastInternalDate > 0 {
		afterSec = lastInternalDate/1000 - 60
	} else {
		afterSec = time.Now().Unix() - 90*24*60*60
	}

	ids, err := ListMessageIDs(ctx, accessToken, afterSec)
	if err != nil {
		return result, fmt.Errorf("list messages: %w", err)
	}

	// Load user merchant rules once for the whole run.
	userRules, err := loadRules(ctx, pool, userID)
	if err != nil {
		return result, fmt.Errorf("load rules: %w", err)
	}

	maxInternalDate := lastInternalDate
	llmCalls := 0

	for _, id := range ids {
		if err := ctx.Err(); err != nil {
			break
		}
		func() {
			// Check whether this gmail_message_id already exists.
			var exists bool
			_ = pool.QueryRow(ctx,
				`SELECT EXISTS(SELECT 1 FROM transactions WHERE user_id = $1 AND gmail_message_id = $2)`,
				userID, id,
			).Scan(&exists)
			if exists {
				result.Skipped++
				return
			}

			msg, err := GetMessage(ctx, accessToken, id)
			if err != nil {
				slog.Warn("gmail: getMessage failed", "id", id, "err", err)
				result.Errors++
				return
			}
			result.Fetched++
			if msg.InternalDate > maxInternalDate {
				maxInternalDate = msg.InternalDate
			}

			parsed := parser.ParseText(parser.Input{
				From:    msg.From,
				Subject: msg.Subject,
				Body:    msg.Body,
			})

			// Regex missed it but looks financial → try LLM (within budget).
			if (!parsed.IsTransaction || parsed.Amount == nil) &&
				llmCalls < llmBudget &&
				parser.MightBeFinancial(msg.Subject, msg.Body) {
				llmResult, err := llm.Parse(ctx, msg.Subject+"\n"+msg.Body)
				if err == nil && llmResult.IsTransaction && llmResult.Amount != nil {
					parsed = llmResult
					result.LLM++
					llmCalls++
				}
			}

			if !parsed.IsTransaction || parsed.Amount == nil {
				result.Skipped++
				return
			}

			txnDate := ""
			if parsed.Date != nil {
				txnDate = *parsed.Date
			} else {
				txnDate = time.UnixMilli(msg.InternalDate).Format("2006-01-02")
			}

			merchantNorm := categorize.NormalizeMerchant(func() string {
				if parsed.Merchant != nil {
					return *parsed.Merchant
				}
				return ""
			}())

			category := categorize.ResolveCategory(parsed, merchantNorm, userRules)
			hash := categorize.DedupeHash(
				*parsed.Amount, txnDate, merchantNorm,
				parsed.ReferenceID, string(parsed.Direction),
				id, // gmail message id as fallback discriminator
			)

			merchant := ""
			if parsed.Merchant != nil {
				merchant = *parsed.Merchant
			}
			pm := (*string)(nil)
			if parsed.PaymentMethod != nil {
				pm = parsed.PaymentMethod
			}
			snippet := msg.Body
			if len(snippet) > 280 {
				snippet = snippet[:280]
			}

			_, insertErr := pool.Exec(ctx, `
				INSERT INTO transactions
				  (user_id, amount, currency, direction, merchant_raw, merchant_normalized,
				   category, payment_method, txn_date, reference_id,
				   source, gmail_message_id, dedupe_hash, raw_snippet, reviewed)
				VALUES ($1,$2,'INR',$3,$4,$5,$6,$7,$8,$9,'gmail',$10,$11,$12,false)`,
				userID, *parsed.Amount, string(parsed.Direction),
				merchant, merchantNorm,
				category, pm, txnDate, parsed.ReferenceID,
				id, hash, snippet,
			)
			if insertErr != nil {
				if isDuplicateErr(insertErr) {
					result.Skipped++
				} else {
					slog.Warn("gmail: insert failed", "id", id, "err", insertErr)
					result.Errors++
				}
				return
			}
			result.Inserted++
		}()
	}

	// Update the cursor.
	_, _ = pool.Exec(ctx,
		`UPDATE gmail_connections SET last_internal_date = $1, last_synced_at = NOW() WHERE user_id = $2`,
		maxInternalDate, userID,
	)

	return result, nil
}

func loadRules(ctx context.Context, pool *db.Pool, userID string) (map[string]string, error) {
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

func isDuplicateErr(err error) bool {
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

// GmailStatus is returned by GET /v1/gmail/status.
type GmailStatus struct {
	Connected      bool   `json:"connected"`
	LastSyncedAt   string `json:"last_synced_at,omitempty"`
	LastInternalDate int64 `json:"last_internal_date,omitempty"`
}

// GetStatus returns the current Gmail connection status for a user.
func GetStatus(ctx context.Context, pool *db.Pool, userID string) (GmailStatus, error) {
	var lastSyncedAt *time.Time
	var lastInternalDate *int64
	err := pool.QueryRow(ctx,
		`SELECT last_synced_at, last_internal_date FROM gmail_connections WHERE user_id = $1`,
		userID,
	).Scan(&lastSyncedAt, &lastInternalDate)
	if err != nil {
		// no row = not connected
		return GmailStatus{Connected: false}, nil
	}
	s := GmailStatus{Connected: true}
	if lastSyncedAt != nil {
		s.LastSyncedAt = lastSyncedAt.Format(time.RFC3339)
	}
	if lastInternalDate != nil {
		s.LastInternalDate = *lastInternalDate
	}
	return s, nil
}

// Disconnect removes a user's Gmail connection.
func Disconnect(ctx context.Context, pool *db.Pool, userID string) error {
	_, err := pool.Exec(ctx, `DELETE FROM gmail_connections WHERE user_id = $1`, userID)
	return err
}

// StoreConnection upserts an encrypted refresh token for a user.
func StoreConnection(ctx context.Context, pool *db.Pool, userID, encRefreshToken string) error {
	_, err := pool.Exec(ctx, `
		INSERT INTO gmail_connections (user_id, refresh_token_enc)
		VALUES ($1, $2)
		ON CONFLICT (user_id) DO UPDATE SET refresh_token_enc = EXCLUDED.refresh_token_enc, last_internal_date = NULL`,
		userID, encRefreshToken,
	)
	return err
}

// GetAllConnectedUserIDs returns IDs of all users with an active Gmail connection.
// Used by the scheduler to iterate users.
func GetAllConnectedUserIDs(ctx context.Context, pool *db.Pool) ([]string, error) {
	rows, err := pool.Query(ctx, `SELECT user_id FROM gmail_connections`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// GetUserIDs returns IDs of users whose Gmail hasn't been synced in the last `olderThan` duration.
func GetStaleUserIDs(ctx context.Context, pool *db.Pool, olderThan time.Duration) ([]string, error) {
	cutoff := time.Now().Add(-olderThan)
	rows, err := pool.Query(ctx,
		`SELECT user_id FROM gmail_connections WHERE last_synced_at IS NULL OR last_synced_at < $1`,
		cutoff,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

