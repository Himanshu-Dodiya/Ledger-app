package insights

import (
	"context"
	"fmt"

	"github.com/himanshu/ledger-api/internal/db"
)

// recurringProvider flags merchants charged in 3+ distinct months — likely subscriptions or
// recurring bills. This is exactly the shape an AI "subscription detector" would later refine.
type recurringProvider struct{}

func (recurringProvider) Name() string { return "rules" }

func (recurringProvider) Generate(ctx context.Context, pool *db.Pool, userID string) ([]Insight, error) {
	rows, err := pool.Query(ctx, `
		SELECT COALESCE(NULLIF(merchant_normalized,''),'unknown') AS m,
		       COUNT(DISTINCT to_char(txn_date,'YYYY-MM')) AS months,
		       AVG(amount) AS avg_amt
		FROM transactions
		WHERE user_id=$1 AND direction='debit'
		  AND txn_date >= (CURRENT_DATE - INTERVAL '6 months')
		GROUP BY m
		HAVING COUNT(DISTINCT to_char(txn_date,'YYYY-MM')) >= 3 AND m <> 'unknown'
		ORDER BY months DESC, avg_amt DESC
		LIMIT 5`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Insight
	for rows.Next() {
		var m string
		var months int
		var avg float64
		if err := rows.Scan(&m, &months, &avg); err != nil {
			return nil, err
		}
		out = append(out, Insight{
			Type:     "recurring",
			Title:    fmt.Sprintf("Recurring: %s", m),
			Body:     fmt.Sprintf("Charged in %d of the last 6 months, ~₹%.0f each time.", months, avg),
			Severity: "info",
			Amount:   f64(round2(avg)),
			Source:   "rules",
		})
	}
	return out, rows.Err()
}

// largeExpenseProvider surfaces the biggest single debit in the last 30 days.
type largeExpenseProvider struct{}

func (largeExpenseProvider) Name() string { return "rules" }

func (largeExpenseProvider) Generate(ctx context.Context, pool *db.Pool, userID string) ([]Insight, error) {
	var merchant string
	var amount float64
	err := pool.QueryRow(ctx, `
		SELECT COALESCE(NULLIF(merchant_raw,''),'a merchant'), amount
		FROM transactions
		WHERE user_id=$1 AND direction='debit' AND txn_date >= (CURRENT_DATE - INTERVAL '30 days')
		ORDER BY amount DESC LIMIT 1`, userID).Scan(&merchant, &amount)
	if err != nil {
		return nil, nil // no rows → no insight
	}
	return []Insight{{
		Type:     "large_expense",
		Title:    "Largest expense this month",
		Body:     fmt.Sprintf("₹%.0f to %s.", amount, merchant),
		Severity: "info",
		Amount:   f64(round2(amount)),
		Source:   "rules",
	}}, nil
}

// trendProvider compares this month's spend to last month's.
type trendProvider struct{}

func (trendProvider) Name() string { return "rules" }

func (trendProvider) Generate(ctx context.Context, pool *db.Pool, userID string) ([]Insight, error) {
	var thisMonth, lastMonth float64
	err := pool.QueryRow(ctx, `
		SELECT
		  COALESCE(SUM(amount) FILTER (WHERE to_char(txn_date,'YYYY-MM')=to_char(CURRENT_DATE,'YYYY-MM')),0),
		  COALESCE(SUM(amount) FILTER (WHERE to_char(txn_date,'YYYY-MM')=to_char(CURRENT_DATE - INTERVAL '1 month','YYYY-MM')),0)
		FROM transactions WHERE user_id=$1 AND direction='debit'`, userID).Scan(&thisMonth, &lastMonth)
	if err != nil {
		return nil, err
	}
	if lastMonth == 0 {
		return nil, nil
	}
	change := (thisMonth - lastMonth) / lastMonth * 100
	severity := "info"
	dir := "down"
	if change > 0 {
		dir = "up"
	}
	if change >= 25 {
		severity = "warn"
	}
	return []Insight{{
		Type:     "trend",
		Title:    fmt.Sprintf("Spending is %s %.0f%% vs last month", dir, abs(change)),
		Body:     fmt.Sprintf("₹%.0f so far this month vs ₹%.0f last month.", thisMonth, lastMonth),
		Severity: severity,
		Source:   "rules",
	}}, nil
}

func round2(v float64) float64 { return float64(int64(v*100+0.5)) / 100 }
func abs(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}
