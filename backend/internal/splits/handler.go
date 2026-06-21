// Package splits implements expense splitting (Splitwise model), settlements and derived
// balances. A transaction can be split among participants (you = NULL person_id, or a
// person); one participant is the payer. Per-participant `share_amount` is resolved here from
// the chosen method so balances are a straightforward aggregation.
package splits

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"sort"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
	"github.com/jackc/pgx/v5"
)

type Handler struct{ pool *db.Pool }

func NewHandler(pool *db.Pool) *Handler { return &Handler{pool: pool} }

func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/transactions/{id}/split", authMW(http.HandlerFunc(h.getSplit)))
	mux.Handle("PUT /v1/transactions/{id}/split", authMW(http.HandlerFunc(h.putSplit)))
	mux.Handle("DELETE /v1/transactions/{id}/split", authMW(http.HandlerFunc(h.deleteSplit)))

	mux.Handle("GET /v1/balances", authMW(http.HandlerFunc(h.balances)))
	mux.Handle("GET /v1/people/{id}/timeline", authMW(http.HandlerFunc(h.timeline)))

	mux.Handle("GET /v1/settlements", authMW(http.HandlerFunc(h.listSettlements)))
	mux.Handle("POST /v1/settlements", authMW(http.HandlerFunc(h.createSettlement)))
	mux.Handle("PATCH /v1/settlements/{id}", authMW(http.HandlerFunc(h.patchSettlement)))
	mux.Handle("DELETE /v1/settlements/{id}", authMW(http.HandlerFunc(h.deleteSettlement)))
}

// ---------- split shapes ----------

type SplitRow struct {
	PersonID    *string `json:"person_id"` // null = you
	PersonName  string  `json:"person_name"`
	IsPayer     bool    `json:"is_payer"`
	ShareType   string  `json:"share_type"`
	ShareValue  *float64 `json:"share_value"`
	ShareAmount float64 `json:"share_amount"`
	Settled     bool    `json:"settled"`
}

type splitParticipant struct {
	PersonID *string  `json:"person_id"` // null = you
	Value    *float64 `json:"value"`     // pct / exact / share-units depending on method
}

type splitReq struct {
	Method        string             `json:"method"`          // equal|percent|exact|shares
	PayerPersonID *string            `json:"payer_person_id"` // null = you
	Participants  []splitParticipant `json:"participants"`
}

// ---------- get ----------

func (h *Handler) getSplit(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	txnID := r.PathValue("id")
	rows, err := loadSplit(r.Context(), h.pool, uid, txnID)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, rows)
}

// ---------- put (create/replace) ----------

func (h *Handler) putSplit(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	txnID := r.PathValue("id")

	var req splitReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}

	// Verify the transaction belongs to the user and read its amount.
	var total float64
	err := h.pool.QueryRow(r.Context(),
		`SELECT amount FROM transactions WHERE id=$1 AND user_id=$2`, txnID, uid).Scan(&total)
	if err == pgx.ErrNoRows {
		httpx.NotFound(w)
		return
	}
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}

	if len(req.Participants) == 0 {
		if err := clearSplit(r.Context(), h.pool, uid, txnID); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		httpx.OK(w, []SplitRow{})
		return
	}

	shares, err := resolveShares(req.Method, total, req.Participants)
	if err != nil {
		httpx.BadRequest(w, err.Error())
		return
	}

	if err := writeSplit(r.Context(), h.pool, uid, txnID, req, shares); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	rows, _ := loadSplit(r.Context(), h.pool, uid, txnID)
	httpx.OK(w, rows)
}

func (h *Handler) deleteSplit(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	txnID := r.PathValue("id")
	if err := clearSplit(r.Context(), h.pool, uid, txnID); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, map[string]bool{"deleted": true})
}

// resolveShares turns a method + participants into a per-participant owed amount that sums
// exactly to total. All arithmetic is done in integer paise (not float) so there are no
// floating-point precision errors, and leftover paise are allocated with the largest-remainder
// method (Google Pay style) rather than dumped onto the last participant. e.g. ₹100 split 3
// ways → 33.33, 33.33, 33.34; split 6 ways → 16.66, 16.66, 16.67, 16.67, 16.67, 16.67.
func resolveShares(method string, total float64, ps []splitParticipant) ([]float64, error) {
	n := len(ps)
	if n == 0 {
		return nil, errBad("at least one participant is required")
	}
	totalP := toPaise(total)
	paise := make([]int64, n)

	switch method {
	case "", "equal":
		distributeEqual(totalP, paise)
	case "percent":
		weights := make([]float64, n)
		for i, p := range ps {
			if p.Value == nil {
				return nil, errBad("percent requires a value per participant")
			}
			weights[i] = *p.Value
		}
		distributeWeighted(totalP, weights, paise)
	case "shares":
		weights := make([]float64, n)
		var sum float64
		for i, p := range ps {
			if p.Value == nil {
				return nil, errBad("shares requires a value per participant")
			}
			weights[i] = *p.Value
			sum += *p.Value
		}
		if sum == 0 {
			return nil, errBad("total shares must be > 0")
		}
		distributeWeighted(totalP, weights, paise)
	case "exact":
		var sum int64
		for i, p := range ps {
			if p.Value == nil {
				return nil, errBad("exact requires a value per participant")
			}
			paise[i] = toPaise(*p.Value)
			sum += paise[i]
		}
		if sum != totalP {
			return nil, errBad(fmt.Sprintf("exact amounts must add up to %.2f", total))
		}
	default:
		return nil, errBad("unknown split method")
	}

	out := make([]float64, n)
	for i, p := range paise {
		out[i] = float64(p) / 100
	}
	return out, nil
}

// toPaise converts rupees to integer paise, rounding to the nearest paisa.
func toPaise(rupees float64) int64 { return int64(math.Round(rupees * 100)) }

// distributeEqual splits totalP paise into len(out) near-equal parts; the leftover paise go to
// the last participants so the parts sum to exactly totalP (e.g. 10000 over 3 → 3333,3333,3334).
func distributeEqual(totalP int64, out []int64) {
	n := int64(len(out))
	base := totalP / n
	rem := totalP - base*n // 0..n-1 leftover paise
	for i := range out {
		out[i] = base
	}
	for i := int64(0); i < rem; i++ {
		out[len(out)-1-int(i)]++
	}
}

// distributeWeighted allocates totalP paise across participants proportional to weights, using
// the largest-remainder method so the parts always sum to exactly totalP.
func distributeWeighted(totalP int64, weights []float64, out []int64) {
	var wsum float64
	for _, w := range weights {
		wsum += w
	}
	if wsum == 0 {
		distributeEqual(totalP, out)
		return
	}
	type frac struct {
		idx int
		f   float64
	}
	fracs := make([]frac, len(weights))
	var allocated int64
	for i, w := range weights {
		exact := float64(totalP) * w / wsum
		floor := int64(math.Floor(exact))
		out[i] = floor
		allocated += floor
		fracs[i] = frac{idx: i, f: exact - float64(floor)}
	}
	rem := totalP - allocated // leftover paise to hand out (0..n-1)
	sort.SliceStable(fracs, func(a, b int) bool { return fracs[a].f > fracs[b].f })
	for i := int64(0); i < rem && int(i) < len(fracs); i++ {
		out[fracs[i].idx]++
	}
}

func writeSplit(ctx context.Context, pool *db.Pool, uid, txnID string, req splitReq, shares []float64) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `DELETE FROM splits WHERE transaction_id=$1 AND user_id=$2`, txnID, uid); err != nil {
		return err
	}
	shareType := req.Method
	if shareType == "" {
		shareType = "equal"
	}
	payerIncluded := false
	for i, p := range req.Participants {
		isPayer := samePerson(p.PersonID, req.PayerPersonID)
		if isPayer {
			payerIncluded = true
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO splits (user_id, transaction_id, person_id, is_payer, share_type, share_value, share_amount)
			VALUES ($1,$2,$3,$4,$5,$6,$7)`,
			uid, txnID, p.PersonID, isPayer, shareType, p.Value, shares[i]); err != nil {
			return err
		}
	}
	// The payer fronted the money but may not be sharing the bill (e.g. "you paid for someone
	// else" — you owe nothing yet are owed by the others). Record a zero-share payer row so
	// balances can still attribute who is owed.
	if !payerIncluded {
		if _, err := tx.Exec(ctx, `
			INSERT INTO splits (user_id, transaction_id, person_id, is_payer, share_type, share_value, share_amount)
			VALUES ($1,$2,$3,true,$4,NULL,0)`,
			uid, txnID, req.PayerPersonID, shareType); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE transactions SET is_split=true WHERE id=$1 AND user_id=$2`, txnID, uid); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func clearSplit(ctx context.Context, pool *db.Pool, uid, txnID string) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `DELETE FROM splits WHERE transaction_id=$1 AND user_id=$2`, txnID, uid); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE transactions SET is_split=false WHERE id=$1 AND user_id=$2`, txnID, uid); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func loadSplit(ctx context.Context, pool *db.Pool, uid, txnID string) ([]SplitRow, error) {
	rows, err := pool.Query(ctx, `
		SELECT s.person_id, COALESCE(p.name, 'You'), s.is_payer, s.share_type, s.share_value, s.share_amount, s.settled
		FROM splits s LEFT JOIN people p ON p.id = s.person_id
		WHERE s.transaction_id=$1 AND s.user_id=$2
		ORDER BY s.is_payer DESC, p.name NULLS FIRST`, txnID, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]SplitRow, 0)
	for rows.Next() {
		var s SplitRow
		if err := rows.Scan(&s.PersonID, &s.PersonName, &s.IsPayer, &s.ShareType, &s.ShareValue, &s.ShareAmount, &s.Settled); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func round2(v float64) float64 { return math.Round(v*100) / 100 }

func samePerson(a, b *string) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	return *a == *b
}

type badReq struct{ msg string }

func (e badReq) Error() string { return e.msg }
func errBad(m string) error    { return badReq{m} }
