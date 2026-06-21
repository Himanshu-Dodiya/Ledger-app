package splits

import (
	"context"
	"net/http"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/httpx"
)

// SharedExpense is one transaction this person took part in, with their resolved share.
type SharedExpense struct {
	TxnID     string  `json:"transaction_id"`
	Merchant  *string `json:"merchant"`
	Amount    float64 `json:"amount"`
	TxnDate   string  `json:"txn_date"`
	TheirShare float64 `json:"their_share"`
	TheyPaid  bool    `json:"they_paid"`
}

type TimelineResp struct {
	PersonID       string          `json:"person_id"`
	Net            float64         `json:"net"`             // + => they owe you
	TotalShared    float64         `json:"total_shared"`    // sum of their shares across expenses
	SharedExpenses []SharedExpense `json:"shared_expenses"`
	Settlements    []Settlement    `json:"settlements"`
}

// GET /v1/people/{id}/timeline — a person's shared expenses, settlements and net balance.
func (h *Handler) timeline(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	personID := r.PathValue("id")
	ctx := r.Context()

	// Shared expenses: transactions where this person has a split row.
	rows, err := h.pool.Query(ctx, `
		SELECT t.id, t.merchant_raw, t.amount, t.txn_date, s.share_amount, s.is_payer
		FROM splits s JOIN transactions t ON t.id = s.transaction_id
		WHERE s.user_id=$1 AND s.person_id=$2
		ORDER BY t.txn_date DESC`, uid, personID)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	resp := TimelineResp{PersonID: personID, SharedExpenses: []SharedExpense{}, Settlements: []Settlement{}}
	for rows.Next() {
		var e SharedExpense
		var date time.Time
		if err := rows.Scan(&e.TxnID, &e.Merchant, &e.Amount, &date, &e.TheirShare, &e.TheyPaid); err != nil {
			rows.Close()
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		e.TxnDate = date.Format("2006-01-02")
		resp.TotalShared += e.TheirShare
		resp.SharedExpenses = append(resp.SharedExpenses, e)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	resp.TotalShared = round2(resp.TotalShared)

	// Net balance for this person (reuse the same pairwise logic, scoped to txns they're in).
	net, err := personNet(ctx, h, uid, personID)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	resp.Net = round2(net)

	// Settlements involving this person.
	sRows, err := h.pool.Query(ctx, `
		SELECT s.id, s.from_person_id, COALESCE(pf.name,'You'), s.to_person_id, COALESCE(pt.name,'You'),
		       s.amount, s.transaction_id, s.status, s.upi_ref, s.note, s.settled_at
		FROM settlements s
		LEFT JOIN people pf ON pf.id = s.from_person_id
		LEFT JOIN people pt ON pt.id = s.to_person_id
		WHERE s.user_id=$1 AND ($2 IN (s.from_person_id::text, s.to_person_id::text))
		ORDER BY s.settled_at DESC`, uid, personID)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	defer sRows.Close()
	for sRows.Next() {
		var s Settlement
		var settledAt time.Time
		if err := sRows.Scan(&s.ID, &s.FromPersonID, &s.FromName, &s.ToPersonID, &s.ToName,
			&s.Amount, &s.TxnID, &s.Status, &s.UpiRef, &s.Note, &settledAt); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		s.SettledAt = settledAt.Format(time.RFC3339)
		resp.Settlements = append(resp.Settlements, s)
	}
	httpx.OK(w, resp)
}

// personNet computes how much `personID` owes you (positive) from splits + settlements.
func personNet(ctx context.Context, h *Handler, uid, personID string) (float64, error) {
	rows, err := h.pool.Query(ctx, `
		SELECT transaction_id, person_id, is_payer, share_amount
		FROM splits WHERE user_id=$1
		  AND transaction_id IN (SELECT transaction_id FROM splits WHERE user_id=$1 AND person_id=$2)`,
		uid, personID)
	if err != nil {
		return 0, err
	}
	type sp struct {
		person  *string
		isPayer bool
		amount  float64
	}
	byTxn := map[string][]sp{}
	for rows.Next() {
		var txn string
		var s sp
		if err := rows.Scan(&txn, &s.person, &s.isPayer, &s.amount); err != nil {
			rows.Close()
			return 0, err
		}
		byTxn[txn] = append(byTxn[txn], s)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	var net float64
	for _, parts := range byTxn {
		var payer *sp
		for i := range parts {
			if parts[i].isPayer {
				payer = &parts[i]
				break
			}
		}
		if payer == nil {
			continue
		}
		for i := range parts {
			p := parts[i]
			if p.isPayer {
				continue
			}
			// You paid, they owe you.
			if payer.person == nil && p.person != nil && *p.person == personID {
				net += p.amount
			}
			// They paid, you owe them.
			if p.person == nil && payer.person != nil && *payer.person == personID {
				net -= p.amount
			}
		}
	}

	sRows, err := h.pool.Query(ctx, `
		SELECT from_person_id, to_person_id, amount FROM settlements
		WHERE user_id=$1 AND status='completed' AND ($2 IN (from_person_id::text, to_person_id::text))`,
		uid, personID)
	if err != nil {
		return 0, err
	}
	defer sRows.Close()
	for sRows.Next() {
		var from, to *string
		var amt float64
		if err := sRows.Scan(&from, &to, &amt); err != nil {
			return 0, err
		}
		if from != nil && *from == personID && to == nil {
			net -= amt
		}
		if to != nil && *to == personID && from == nil {
			net += amt
		}
	}
	return net, sRows.Err()
}
