// Package groups implements /v1/groups — member containers (trips, flats, …) used to scope
// shared expenses and settlements. Group balances are derived from member pairwise balances
// on the client for now; this package owns CRUD + membership.
package groups

import (
	"context"
	"net/http"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
)

type Member struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type Group struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Type      *string  `json:"type"`
	Members   []Member `json:"members"`
	CreatedAt string   `json:"created_at"`
}

type Handler struct{ pool *db.Pool }

func NewHandler(pool *db.Pool) *Handler { return &Handler{pool: pool} }

func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/groups", authMW(http.HandlerFunc(h.list)))
	mux.Handle("POST /v1/groups", authMW(http.HandlerFunc(h.create)))
	mux.Handle("PATCH /v1/groups/{id}", authMW(http.HandlerFunc(h.patch)))
	mux.Handle("DELETE /v1/groups/{id}", authMW(http.HandlerFunc(h.delete)))
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	groups, err := loadGroups(r.Context(), h.pool, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, groups)
}

type groupReq struct {
	Name      string   `json:"name"`
	Type      string   `json:"type"`
	MemberIDs []string `json:"member_ids"`
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	var req groupReq
	if err := httpx.DecodeJSON(r, &req); err != nil || req.Name == "" {
		httpx.BadRequest(w, "name is required")
		return
	}
	var id string
	err := h.pool.QueryRow(r.Context(),
		`INSERT INTO groups (user_id, name, type) VALUES ($1,$2,$3) RETURNING id`,
		uid, req.Name, ns(req.Type)).Scan(&id)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if err := syncMembers(r.Context(), h.pool, uid, id, req.MemberIDs); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	h.returnGroup(w, r, uid, id, true)
}

func (h *Handler) patch(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	var req groupReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	ct, err := h.pool.Exec(r.Context(),
		`UPDATE groups SET name=$3, type=$4 WHERE id=$1 AND user_id=$2`,
		id, uid, req.Name, ns(req.Type))
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if ct.RowsAffected() == 0 {
		httpx.NotFound(w)
		return
	}
	if req.MemberIDs != nil {
		if err := syncMembers(r.Context(), h.pool, uid, id, req.MemberIDs); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
	}
	h.returnGroup(w, r, uid, id, false)
}

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	ct, err := h.pool.Exec(r.Context(), `DELETE FROM groups WHERE id=$1 AND user_id=$2`, id, uid)
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

func (h *Handler) returnGroup(w http.ResponseWriter, r *http.Request, uid, id string, created bool) {
	groups, err := loadGroups(r.Context(), h.pool, uid, id)
	if err != nil || len(groups) == 0 {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if created {
		httpx.Created(w, groups[0])
	} else {
		httpx.OK(w, groups[0])
	}
}

func loadGroups(ctx context.Context, pool *db.Pool, uid string, onlyID ...string) ([]Group, error) {
	sql := `SELECT id, name, type, created_at FROM groups WHERE user_id=$1`
	args := []any{uid}
	if len(onlyID) == 1 {
		sql += ` AND id=$2`
		args = append(args, onlyID[0])
	}
	sql += ` ORDER BY name`
	rows, err := pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	groups := make([]Group, 0)
	index := map[string]int{}
	for rows.Next() {
		var g Group
		var created time.Time
		if err := rows.Scan(&g.ID, &g.Name, &g.Type, &created); err != nil {
			return nil, err
		}
		g.CreatedAt = created.Format(time.RFC3339)
		g.Members = []Member{}
		index[g.ID] = len(groups)
		groups = append(groups, g)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(groups) == 0 {
		return groups, nil
	}

	mRows, err := pool.Query(ctx, `
		SELECT gm.group_id, p.id, p.name
		FROM group_members gm
		JOIN people p ON p.id = gm.person_id
		JOIN groups g ON g.id = gm.group_id
		WHERE g.user_id=$1`, uid)
	if err != nil {
		return nil, err
	}
	defer mRows.Close()
	for mRows.Next() {
		var gid string
		var m Member
		if err := mRows.Scan(&gid, &m.ID, &m.Name); err != nil {
			return nil, err
		}
		if i, ok := index[gid]; ok {
			groups[i].Members = append(groups[i].Members, m)
		}
	}
	return groups, mRows.Err()
}

func syncMembers(ctx context.Context, pool *db.Pool, uid, groupID string, memberIDs []string) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `DELETE FROM group_members WHERE group_id=$1`, groupID); err != nil {
		return err
	}
	for _, pid := range memberIDs {
		if _, err := tx.Exec(ctx, `
			INSERT INTO group_members (group_id, person_id)
			SELECT $1, p.id FROM people p WHERE p.id=$2 AND p.user_id=$3
			ON CONFLICT DO NOTHING`, groupID, pid, uid); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func ns(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
