// Package db manages the pgx connection pool to Supabase Postgres.
// All queries are explicitly scoped to the caller's user_id — the pool uses
// a service-role connection that bypasses RLS, so column-level scoping is mandatory.
package db

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Pool is the application-level pgx connection pool.
type Pool = pgxpool.Pool

// New opens and validates a pgx pool from the given connection URL.
func New(ctx context.Context, dsn string) (*Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("db config: %w", err)
	}
	cfg.MaxConns = 10
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("db open: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		return nil, fmt.Errorf("db ping: %w", err)
	}
	return pool, nil
}
