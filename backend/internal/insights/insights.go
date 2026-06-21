// Package insights is the extensibility seam for spending intelligence. Each Provider turns
// a user's data into zero or more Insights; the handler runs them all and returns the union.
//
// This is deliberately AI-free today: the providers below are deterministic SQL. An AI
// provider (LLM summaries, anomaly detection, budget advice) is added later by implementing
// the same Provider interface and appending it to the registry — no handler/API changes.
package insights

import (
	"context"
	"net/http"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
)

// Insight is one surfaced observation. Type lets the client group/icon them; Severity drives
// styling (info | warn). Kind names the producing provider (useful once AI providers exist).
type Insight struct {
	Type     string   `json:"type"`     // recurring | large_expense | trend | subscription | summary
	Title    string   `json:"title"`
	Body     string   `json:"body"`
	Severity string   `json:"severity"` // info | warn
	Amount   *float64 `json:"amount,omitempty"`
	Source   string   `json:"source"`   // provider name (e.g. "rules", later "ai")
}

// Provider produces insights from a user's data. Implementations must be read-only and fast.
type Provider interface {
	Name() string
	Generate(ctx context.Context, pool *db.Pool, userID string) ([]Insight, error)
}

// registry is the ordered set of active providers. Append an AI-backed provider here later.
var registry = []Provider{
	recurringProvider{},
	largeExpenseProvider{},
	trendProvider{},
}

type Handler struct{ pool *db.Pool }

func NewHandler(pool *db.Pool) *Handler { return &Handler{pool: pool} }

func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/insights", authMW(http.HandlerFunc(h.list)))
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	out := make([]Insight, 0)
	for _, p := range registry {
		ins, err := p.Generate(r.Context(), h.pool, uid)
		if err != nil {
			// One failing provider shouldn't sink the whole response.
			continue
		}
		out = append(out, ins...)
	}
	httpx.OK(w, out)
}

func f64(v float64) *float64 { return &v }
