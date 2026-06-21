// Package devices implements /v1/devices — FCM token registration and management.
package devices

import (
	"context"
	"net/http"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
)

// Device is the JSON shape returned to the client for each registered device.
type Device struct {
	ID         string  `json:"id"`
	Platform   string  `json:"platform"`
	Model      *string `json:"model,omitempty"`
	LastSeenAt string  `json:"last_seen_at"`
	CreatedAt  string  `json:"created_at"`
}

// Handler holds the DB pool for the devices endpoints.
type Handler struct{ pool *db.Pool }

func NewHandler(pool *db.Pool) *Handler { return &Handler{pool: pool} }

// Register wires device endpoints into mux under authMW.
func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/devices", authMW(http.HandlerFunc(h.upsert)))
	mux.Handle("GET /v1/devices", authMW(http.HandlerFunc(h.list)))
	mux.Handle("DELETE /v1/devices/{id}", authMW(http.HandlerFunc(h.delete)))
}

type upsertReq struct {
	FCMToken string `json:"fcm_token"`
	Platform string `json:"platform"`
	Model    string `json:"model"`
}

func (h *Handler) upsert(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	var req upsertReq
	if err := httpx.DecodeJSON(r, &req); err != nil || req.FCMToken == "" {
		httpx.BadRequest(w, "fcm_token is required")
		return
	}
	if req.Platform == "" {
		req.Platform = "android"
	}
	var model *string
	if req.Model != "" {
		model = &req.Model
	}
	_, err := h.pool.Exec(r.Context(), `
		INSERT INTO devices (user_id, fcm_token, platform, model, last_seen_at)
		VALUES ($1, $2, $3, $4, now())
		ON CONFLICT (user_id, fcm_token) DO UPDATE
		  SET last_seen_at = now(),
		      platform = EXCLUDED.platform,
		      model = COALESCE(EXCLUDED.model, devices.model)`,
		uid, req.FCMToken, req.Platform, model,
	)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, map[string]bool{"registered": true})
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	rows, err := h.pool.Query(r.Context(), `
		SELECT id, platform, model, last_seen_at, created_at
		FROM devices WHERE user_id = $1 ORDER BY last_seen_at DESC`, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	defer rows.Close()

	devices := make([]Device, 0)
	for rows.Next() {
		var d Device
		var lastSeen, createdAt time.Time
		if err := rows.Scan(&d.ID, &d.Platform, &d.Model, &lastSeen, &createdAt); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		d.LastSeenAt = lastSeen.Format(time.RFC3339)
		d.CreatedAt = createdAt.Format(time.RFC3339)
		devices = append(devices, d)
	}
	httpx.OK(w, devices)
}

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	tag, err := h.pool.Exec(r.Context(),
		`DELETE FROM devices WHERE user_id = $1 AND id = $2`, uid, id)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if tag.RowsAffected() == 0 {
		httpx.NotFound(w)
		return
	}
	httpx.OK(w, map[string]bool{"deleted": true})
}

// GetTokens returns all FCM registration tokens for a user.
// Called by the notification system after Gmail sync or SMS ingest.
func GetTokens(ctx context.Context, pool *db.Pool, userID string) ([]string, error) {
	rows, err := pool.Query(ctx, `SELECT fcm_token FROM devices WHERE user_id = $1`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var tokens []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err != nil {
			return nil, err
		}
		tokens = append(tokens, t)
	}
	return tokens, rows.Err()
}
