// Package people implements the /v1/people and /v1/tags endpoints. People and tags are
// first-class entities (a person can carry many tags) and underpin splitting, settlements
// and group membership in later phases.
package people

import (
	"context"
	"net/http"
	"time"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/httpx"
	"github.com/jackc/pgx/v5"
)

type Tag struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Color     string `json:"color"`
	CreatedAt string `json:"created_at,omitempty"`
}

type Person struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Phone     *string `json:"phone"`
	UpiID     *string `json:"upi_id"`
	ImageURL  *string `json:"image_url"`
	Tags      []Tag   `json:"tags"`
	CreatedAt string  `json:"created_at"`
}

type Handler struct{ pool *db.Pool }

func NewHandler(pool *db.Pool) *Handler { return &Handler{pool: pool} }

func (h *Handler) Register(mux *http.ServeMux, authMW func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/people", authMW(http.HandlerFunc(h.listPeople)))
	mux.Handle("POST /v1/people", authMW(http.HandlerFunc(h.createPerson)))
	mux.Handle("PATCH /v1/people/{id}", authMW(http.HandlerFunc(h.patchPerson)))
	mux.Handle("DELETE /v1/people/{id}", authMW(http.HandlerFunc(h.deletePerson)))

	mux.Handle("GET /v1/tags", authMW(http.HandlerFunc(h.listTags)))
	mux.Handle("POST /v1/tags", authMW(http.HandlerFunc(h.createTag)))
	mux.Handle("PATCH /v1/tags/{id}", authMW(http.HandlerFunc(h.patchTag)))
	mux.Handle("DELETE /v1/tags/{id}", authMW(http.HandlerFunc(h.deleteTag)))
}

// ---------- people ----------

func (h *Handler) listPeople(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	people, err := loadPeople(r.Context(), h.pool, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	httpx.OK(w, people)
}

type personReq struct {
	Name     string   `json:"name"`
	Phone    string   `json:"phone"`
	UpiID    string   `json:"upi_id"`
	ImageURL string   `json:"image_url"`
	TagIDs   []string `json:"tag_ids"`
}

func (h *Handler) createPerson(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	var req personReq
	if err := httpx.DecodeJSON(r, &req); err != nil || req.Name == "" {
		httpx.BadRequest(w, "name is required")
		return
	}

	var id string
	err := h.pool.QueryRow(r.Context(), `
		INSERT INTO people (user_id, name, phone, upi_id, image_url)
		VALUES ($1,$2,$3,$4,$5) RETURNING id`,
		uid, req.Name, ns(req.Phone), ns(req.UpiID), ns(req.ImageURL),
	).Scan(&id)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if err := syncPersonTags(r.Context(), h.pool, uid, id, req.TagIDs); err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	h.returnPerson(w, r, uid, id, true)
}

func (h *Handler) patchPerson(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	var req personReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	ct, err := h.pool.Exec(r.Context(), `
		UPDATE people SET name=$3, phone=$4, upi_id=$5, image_url=$6
		WHERE id=$1 AND user_id=$2`,
		id, uid, req.Name, ns(req.Phone), ns(req.UpiID), ns(req.ImageURL))
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if ct.RowsAffected() == 0 {
		httpx.NotFound(w)
		return
	}
	if req.TagIDs != nil {
		if err := syncPersonTags(r.Context(), h.pool, uid, id, req.TagIDs); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
	}
	h.returnPerson(w, r, uid, id, false)
}

func (h *Handler) deletePerson(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	ct, err := h.pool.Exec(r.Context(), `DELETE FROM people WHERE id=$1 AND user_id=$2`, id, uid)
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

func (h *Handler) returnPerson(w http.ResponseWriter, r *http.Request, uid, id string, created bool) {
	people, err := loadPeople(r.Context(), h.pool, uid, id)
	if err != nil || len(people) == 0 {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	if created {
		httpx.Created(w, people[0])
		return
	}
	httpx.OK(w, people[0])
}

// loadPeople returns all of a user's people (optionally a single id) with tags attached.
func loadPeople(ctx context.Context, pool *db.Pool, uid string, onlyID ...string) ([]Person, error) {
	sql := `SELECT id, name, phone, upi_id, image_url, created_at FROM people WHERE user_id=$1`
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

	people := make([]Person, 0)
	index := map[string]int{}
	for rows.Next() {
		var p Person
		var created time.Time
		if err := rows.Scan(&p.ID, &p.Name, &p.Phone, &p.UpiID, &p.ImageURL, &created); err != nil {
			return nil, err
		}
		p.CreatedAt = created.Format(time.RFC3339)
		p.Tags = []Tag{}
		index[p.ID] = len(people)
		people = append(people, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(people) == 0 {
		return people, nil
	}

	// Attach tags in one pass.
	tagRows, err := pool.Query(ctx, `
		SELECT pt.person_id, t.id, t.name, t.color
		FROM people_tags pt JOIN tags t ON t.id = pt.tag_id
		JOIN people p ON p.id = pt.person_id
		WHERE p.user_id=$1`, uid)
	if err != nil {
		return nil, err
	}
	defer tagRows.Close()
	for tagRows.Next() {
		var personID string
		var t Tag
		if err := tagRows.Scan(&personID, &t.ID, &t.Name, &t.Color); err != nil {
			return nil, err
		}
		if i, ok := index[personID]; ok {
			people[i].Tags = append(people[i].Tags, t)
		}
	}
	return people, tagRows.Err()
}

// syncPersonTags replaces a person's tag set with tagIDs (scoped to the user's tags).
func syncPersonTags(ctx context.Context, pool *db.Pool, uid, personID string, tagIDs []string) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `DELETE FROM people_tags WHERE person_id=$1`, personID); err != nil {
		return err
	}
	for _, tagID := range tagIDs {
		// The join to tags enforces that the tag belongs to this user.
		if _, err := tx.Exec(ctx, `
			INSERT INTO people_tags (person_id, tag_id)
			SELECT $1, t.id FROM tags t WHERE t.id=$2 AND t.user_id=$3
			ON CONFLICT DO NOTHING`, personID, tagID, uid); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// ---------- tags ----------

func (h *Handler) listTags(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	rows, err := h.pool.Query(r.Context(),
		`SELECT id, name, color, created_at FROM tags WHERE user_id=$1 ORDER BY name`, uid)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	defer rows.Close()
	tags := make([]Tag, 0)
	for rows.Next() {
		var t Tag
		var created time.Time
		if err := rows.Scan(&t.ID, &t.Name, &t.Color, &created); err != nil {
			httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
			return
		}
		t.CreatedAt = created.Format(time.RFC3339)
		tags = append(tags, t)
	}
	httpx.OK(w, tags)
}

type tagReq struct {
	Name  string `json:"name"`
	Color string `json:"color"`
}

func (h *Handler) createTag(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	var req tagReq
	if err := httpx.DecodeJSON(r, &req); err != nil || req.Name == "" {
		httpx.BadRequest(w, "name is required")
		return
	}
	color := req.Color
	if color == "" {
		color = "#6366F1"
	}
	var t Tag
	var created time.Time
	err := h.pool.QueryRow(r.Context(), `
		INSERT INTO tags (user_id, name, color) VALUES ($1,$2,$3)
		ON CONFLICT (user_id, name) DO UPDATE SET color=EXCLUDED.color
		RETURNING id, name, color, created_at`,
		uid, req.Name, color,
	).Scan(&t.ID, &t.Name, &t.Color, &created)
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	t.CreatedAt = created.Format(time.RFC3339)
	httpx.Created(w, t)
}

func (h *Handler) patchTag(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	var req tagReq
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.BadRequest(w, "invalid JSON")
		return
	}
	var t Tag
	var created time.Time
	err := h.pool.QueryRow(r.Context(), `
		UPDATE tags SET name=COALESCE(NULLIF($3,''), name), color=COALESCE(NULLIF($4,''), color)
		WHERE id=$1 AND user_id=$2
		RETURNING id, name, color, created_at`,
		id, uid, req.Name, req.Color,
	).Scan(&t.ID, &t.Name, &t.Color, &created)
	if err == pgx.ErrNoRows {
		httpx.NotFound(w)
		return
	}
	if err != nil {
		httpx.InternalErrorErr(w, r.Method+" "+r.URL.Path, err)
		return
	}
	t.CreatedAt = created.Format(time.RFC3339)
	httpx.OK(w, t)
}

func (h *Handler) deleteTag(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserID(r.Context())
	id := r.PathValue("id")
	ct, err := h.pool.Exec(r.Context(), `DELETE FROM tags WHERE id=$1 AND user_id=$2`, id, uid)
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

// ns maps "" → SQL NULL.
func ns(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
