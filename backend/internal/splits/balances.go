package splits

import (
	"context"
	"net/http"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/httpx"
)

// Balance is a person's net position relative to you. Positive => they owe you.
type Balance struct {
	PersonID string  `json:"person_id"`
	Name     string  `json:"name"`
	Net      float64 `json:"net"`
}

type BalancesResp struct {
	Balances  []Balance `json:"balances"`
	TheyOweMe float64   `json:"they_owe_me"`
	IOwe      float64   `json:"i_owe"`
}

// balances derives pairwise net positions from splits + settlements (all done in Go since the
// per-user volume is small). Only positions involving you are surfaced.
func (h *Handler) balances(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	ctx := r.Context()

	// 1) Aggregate debts from splits, grouped by transaction.
	rows, err := h.pool.Query(ctx,
		`SELECT transaction_id, person_id, is_payer, share_amount
		 FROM splits WHERE user_id=$1`, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
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
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		byTxn[txn] = append(byTxn[txn], s)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}

	net := map[string]float64{} // personID -> amount they owe you
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
			switch {
			case payer.person == nil && p.person != nil:
				net[*p.person] += p.amount // they owe you
			case p.person == nil && payer.person != nil:
				net[*payer.person] -= p.amount // you owe them
			}
		}
	}

	// 2) Apply settlements involving you.
	sRows, err := h.pool.Query(ctx,
		`SELECT from_person_id, to_person_id, amount FROM settlements
		 WHERE user_id=$1 AND status='completed'`, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	for sRows.Next() {
		var from, to *string
		var amt float64
		if err := sRows.Scan(&from, &to, &amt); err != nil {
			sRows.Close()
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		switch {
		case from != nil && to == nil:
			net[*from] -= amt // they paid you → their debt shrinks
		case from == nil && to != nil:
			net[*to] += amt // you paid them → your debt shrinks
		}
	}
	sRows.Close()

	// 3) Attach names, build response.
	names, err := personNames(ctx, h)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	resp := BalancesResp{Balances: []Balance{}}
	for pid, v := range net {
		v = round2(v)
		if v == 0 {
			continue
		}
		resp.Balances = append(resp.Balances, Balance{PersonID: pid, Name: names[pid], Net: v})
		if v > 0 {
			resp.TheyOweMe += v
		} else {
			resp.IOwe += -v
		}
	}
	resp.TheyOweMe = round2(resp.TheyOweMe)
	resp.IOwe = round2(resp.IOwe)
	httpx.OK(w, resp)
}

func personNames(ctx context.Context, h *Handler) (map[string]string, error) {
	rows, err := h.pool.Query(ctx, `SELECT id, name FROM people`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	m := map[string]string{}
	for rows.Next() {
		var id, name string
		if err := rows.Scan(&id, &name); err != nil {
			return nil, err
		}
		m[id] = name
	}
	return m, rows.Err()
}

// ---------- settlements ----------

type Settlement struct {
	ID           string   `json:"id"`
	FromPersonID *string  `json:"from_person_id"`
	FromName     string   `json:"from_name"`
	ToPersonID   *string  `json:"to_person_id"`
	ToName       string   `json:"to_name"`
	Amount       float64  `json:"amount"`
	TxnID        *string  `json:"transaction_id"`
	Status       string   `json:"status"`
	UpiRef       *string  `json:"upi_ref"`
	Note         *string  `json:"note"`
	SettledAt    string   `json:"settled_at"`
}

func (h *Handler) listSettlements(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	rows, err := h.pool.Query(r.Context(), `
		SELECT s.id, s.from_person_id, COALESCE(pf.name,'You'), s.to_person_id, COALESCE(pt.name,'You'),
		       s.amount, s.transaction_id, s.status, s.upi_ref, s.note, s.settled_at
		FROM settlements s
		LEFT JOIN people pf ON pf.id = s.from_person_id
		LEFT JOIN people pt ON pt.id = s.to_person_id
		WHERE s.user_id=$1 ORDER BY s.settled_at DESC`, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	defer rows.Close()
	out := make([]Settlement, 0)
	for rows.Next() {
		var s Settlement
		var settledAt time.Time
		if err := rows.Scan(&s.ID, &s.FromPersonID, &s.FromName, &s.ToPersonID, &s.ToName,
			&s.Amount, &s.TxnID, &s.Status, &s.UpiRef, &s.Note, &settledAt); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		s.SettledAt = settledAt.Format(time.RFC3339)
		out = append(out, s)
	}
	httpx.OK(w, out)
}

type settlementReq struct {
	FromPersonID *string  `json:"from_person_id"`
	ToPersonID   *string  `json:"to_person_id"`
	Amount       float64  `json:"amount"`
	TxnID        *string  `json:"transaction_id"`
	Status       string   `json:"status"`
	UpiRef       *string  `json:"upi_ref"`
	Note         *string  `json:"note"`
}

func (h *Handler) createSettlement(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	var req settlementReq
	if err := httpx.DecodeJSON(r, &req); err != nil || req.Amount <= 0 {
		httpx.BadRequest(w, "amount must be positive")
		return
	}
	status := req.Status
	if status == "" {
		status = "completed"
	}
	var id string
	err := h.pool.QueryRow(r.Context(), `
		INSERT INTO settlements (user_id, from_person_id, to_person_id, amount, transaction_id, status, upi_ref, note)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING id`,
		uid, req.FromPersonID, req.ToPersonID, req.Amount, req.TxnID, status, req.UpiRef, req.Note,
	).Scan(&id)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.Created(w, map[string]string{"id": id})
}

func (h *Handler) patchSettlement(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	var req settlementReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	status := req.Status
	if status == "" {
		status = "completed"
	}
	ct, err := h.pool.Exec(r.Context(),
		`UPDATE settlements SET status=$3, upi_ref=COALESCE($4, upi_ref) WHERE id=$1 AND user_id=$2`,
		id, uid, status, req.UpiRef)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if ct.RowsAffected() == 0 {
		httpx.NotFound(w)
		return
	}
	httpx.OK(w, map[string]bool{"updated": true})
}

func (h *Handler) deleteSettlement(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	ct, err := h.pool.Exec(r.Context(), `DELETE FROM settlements WHERE id=$1 AND user_id=$2`, id, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if ct.RowsAffected() == 0 {
		httpx.NotFound(w)
		return
	}
	httpx.OK(w, map[string]bool{"deleted": true})
}
