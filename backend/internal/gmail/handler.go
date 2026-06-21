package gmail

import (
	"net/http"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
)

// GmailHandler wires /v1/gmail/* routes.
type GmailHandler struct {
	pool *db.Pool
}

func NewHandler(pool *db.Pool) *GmailHandler {
	return &GmailHandler{pool: pool}
}

func (h *GmailHandler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/gmail/connect", authMW(http.HandlerFunc(h.connect)))
	mux.Handle("POST /v1/gmail/sync", authMW(http.HandlerFunc(h.sync)))
	mux.Handle("GET /v1/gmail/status", authMW(http.HandlerFunc(h.status)))
	mux.Handle("DELETE /v1/gmail", authMW(http.HandlerFunc(h.disconnect)))
}

type connectReq struct {
	ServerAuthCode string `json:"serverAuthCode"`
}

// connect exchanges a serverAuthCode (from the Android Google Authorization API) for a
// refresh token, encrypts it, and stores it in gmail_connections.
func (h *GmailHandler) connect(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())

	var req connectReq
	if err := httpx.DecodeJSON(r, &req); err != nil || req.ServerAuthCode == "" {
		httpx.BadRequest(w, "serverAuthCode is required")
		return
	}

	refreshToken, err := ExchangeServerAuthCode(r.Context(), req.ServerAuthCode)
	if err != nil {
		httpx.Error(w, 502, "failed to exchange auth code: "+err.Error())
		return
	}

	enc, err := EncryptToken(refreshToken)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}

	if err := StoreConnection(r.Context(), h.pool, uid, enc); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}

	httpx.OK(w, map[string]bool{"connected": true})
}

// sync triggers an immediate Gmail sync for the authenticated user.
func (h *GmailHandler) sync(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())

	result, err := SyncUser(r.Context(), h.pool, uid)
	if err != nil {
		httpx.Error(w, 502, err.Error())
		return
	}
	httpx.OK(w, result)
}

// status returns connection + last-sync metadata.
func (h *GmailHandler) status(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	s, err := GetStatus(r.Context(), h.pool, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, s)
}

// disconnect removes the Gmail connection.
func (h *GmailHandler) disconnect(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	if err := Disconnect(r.Context(), h.pool, uid); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, map[string]bool{"disconnected": true})
}
