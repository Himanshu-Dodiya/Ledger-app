package transactions

import (
	"context"
	"net/http"
	"strconv"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
)

// AnalyticsResp is the mobile-first dashboard payload. Spend breakdowns cover debits only;
// income is reported separately.
type AnalyticsResp struct {
	TotalSpend      float64            `json:"total_spend"`
	TotalIncome     float64            `json:"total_income"`
	Savings         float64            `json:"savings"`        // income - spend
	ByCategory      []Bucket           `json:"by_category"`
	ByPaymentMethod []Bucket           `json:"by_payment_method"`
	BySource        []Bucket           `json:"by_source"`
	TopMerchants    []Bucket           `json:"top_merchants"`
	MonthlyTrends   []MonthPoint       `json:"monthly_trends"`
	TopPeople       []Bucket           `json:"top_people"`     // shared spend by person (splits)
}

type Bucket struct {
	Label string  `json:"label"`
	Total float64 `json:"total"`
}

type MonthPoint struct {
	Month  string  `json:"month"` // YYYY-MM
	Spend  float64 `json:"spend"`
	Income float64 `json:"income"`
}

// analytics handles GET /v1/analytics?from=YYYY-MM-DD&to=YYYY-MM-DD (both optional).
func (h *Handler) analytics(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	resp, err := buildAnalytics(r.Context(), h.pool, uid, from, to)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, resp)
}

func buildAnalytics(ctx context.Context, pool *db.Pool, uid, from, to string) (*AnalyticsResp, error) {
	// Build a reusable WHERE for the date window.
	where := "user_id=$1"
	args := []any{uid}
	if from != "" {
		where += " AND txn_date >= $" + strconv.Itoa(len(args)+1)
		args = append(args, from)
	}
	if to != "" {
		where += " AND txn_date <= $" + strconv.Itoa(len(args)+1)
		args = append(args, to)
	}

	resp := &AnalyticsResp{
		ByCategory: []Bucket{}, ByPaymentMethod: []Bucket{}, BySource: []Bucket{},
		TopMerchants: []Bucket{}, MonthlyTrends: []MonthPoint{}, TopPeople: []Bucket{},
	}

	// Totals.
	if err := pool.QueryRow(ctx,
		`SELECT
		   COALESCE(SUM(amount) FILTER (WHERE direction='debit'),0),
		   COALESCE(SUM(amount) FILTER (WHERE direction='credit'),0)
		 FROM transactions WHERE `+where, args...).
		Scan(&resp.TotalSpend, &resp.TotalIncome); err != nil {
		return nil, err
	}
	resp.Savings = round2(resp.TotalIncome - resp.TotalSpend)
	resp.TotalSpend = round2(resp.TotalSpend)
	resp.TotalIncome = round2(resp.TotalIncome)

	var err error
	if resp.ByCategory, err = buckets(ctx, pool,
		`SELECT category, SUM(amount) FROM transactions WHERE `+where+
			` AND direction='debit' GROUP BY category ORDER BY 2 DESC`, args); err != nil {
		return nil, err
	}
	if resp.ByPaymentMethod, err = buckets(ctx, pool,
		`SELECT COALESCE(NULLIF(payment_method,''),'Other'), SUM(amount) FROM transactions WHERE `+where+
			` AND direction='debit' GROUP BY 1 ORDER BY 2 DESC`, args); err != nil {
		return nil, err
	}
	if resp.BySource, err = buckets(ctx, pool,
		`SELECT source, SUM(amount) FROM transactions WHERE `+where+
			` AND direction='debit' GROUP BY source ORDER BY 2 DESC`, args); err != nil {
		return nil, err
	}
	if resp.TopMerchants, err = buckets(ctx, pool,
		`SELECT COALESCE(NULLIF(merchant_normalized,''),'unknown'), SUM(amount) FROM transactions WHERE `+where+
			` AND direction='debit' GROUP BY 1 ORDER BY 2 DESC LIMIT 8`, args); err != nil {
		return nil, err
	}
	// Shared spend by person (from splits) — top people you spend with.
	if resp.TopPeople, err = buckets(ctx, pool,
		`SELECT p.name, SUM(s.share_amount) FROM splits s JOIN people p ON p.id=s.person_id
		 WHERE s.user_id=$1 GROUP BY p.name ORDER BY 2 DESC LIMIT 5`, []any{uid}); err != nil {
		return nil, err
	}

	// Monthly trends — last 6 calendar months.
	rows, err := pool.Query(ctx, `
		SELECT to_char(txn_date,'YYYY-MM') AS m,
		       COALESCE(SUM(amount) FILTER (WHERE direction='debit'),0),
		       COALESCE(SUM(amount) FILTER (WHERE direction='credit'),0)
		FROM transactions
		WHERE user_id=$1 AND txn_date >= (CURRENT_DATE - INTERVAL '6 months')
		GROUP BY m ORDER BY m`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var mp MonthPoint
		if err := rows.Scan(&mp.Month, &mp.Spend, &mp.Income); err != nil {
			return nil, err
		}
		mp.Spend = round2(mp.Spend)
		mp.Income = round2(mp.Income)
		resp.MonthlyTrends = append(resp.MonthlyTrends, mp)
	}
	return resp, rows.Err()
}

func buckets(ctx context.Context, pool *db.Pool, sql string, args []any) ([]Bucket, error) {
	rows, err := pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Bucket{}
	for rows.Next() {
		var b Bucket
		if err := rows.Scan(&b.Label, &b.Total); err != nil {
			return nil, err
		}
		b.Total = round2(b.Total)
		out = append(out, b)
	}
	return out, rows.Err()
}

func round2(v float64) float64 { return float64(int64(v*100+0.5)) / 100 }
