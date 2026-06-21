package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/joho/godotenv"

	"github.com/himanshu/ledger-api/internal/auth"
	"github.com/himanshu/ledger-api/internal/config"
	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/devices"
	"github.com/himanshu/ledger-api/internal/gmail"
	"github.com/himanshu/ledger-api/internal/groups"
	"github.com/himanshu/ledger-api/internal/httpx"
	"github.com/himanshu/ledger-api/internal/ingest"
	"github.com/himanshu/ledger-api/internal/insights"
	"github.com/himanshu/ledger-api/internal/notify"
	"github.com/himanshu/ledger-api/internal/people"
	"github.com/himanshu/ledger-api/internal/splits"
	"github.com/himanshu/ledger-api/internal/transactions"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	// Load .env if present (development convenience). Silently ignored in production
	// where real environment variables are injected by the platform.
	if err := godotenv.Load(); err == nil {
		slog.Info("loaded .env")
	}

	cfg, err := config.Load()
	if err != nil {
		slog.Error("config", "err", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := db.New(ctx, cfg.DatabaseURL)
	if err != nil {
		slog.Error("db", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	// FCM notifier — nil when FIREBASE_SERVICE_ACCOUNT_JSON is absent (no-op mode).
	notifier, err := notify.New()
	if err != nil {
		slog.Warn("notify: FCM init failed — push notifications disabled", "err", err)
		notifier = nil
	}

	mux := http.NewServeMux()
	authMW := auth.Middleware(cfg.SupabaseJWTSecret, cfg.SupabaseURL)

	// ---- public ----
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		httpx.OK(w, map[string]string{"status": "ok", "version": "1"})
	})

	// ---- authenticated handlers ----
	transactions.NewHandler(pool).Register(mux, authMW)
	ingest.NewHandler(pool, notifier).Register(mux, authMW)
	gmail.NewHandler(pool).Register(mux, authMW)
	devices.NewHandler(pool).Register(mux, authMW)
	people.NewHandler(pool).Register(mux, authMW)
	splits.NewHandler(pool).Register(mux, authMW)
	groups.NewHandler(pool).Register(mux, authMW)
	insights.NewHandler(pool).Register(mux, authMW)

	handler := httpx.CORS(httpx.Logging(mux))

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      handler,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start Gmail scheduler in the background.
	go gmail.StartScheduler(ctx, pool, time.Duration(cfg.GmailSyncIntervalM)*time.Minute, notifier)

	go func() {
		slog.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("serve", "err", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	slog.Info("shutting down")

	shutCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutCtx); err != nil {
		slog.Error("shutdown", "err", err)
	}
	slog.Info("stopped")
}
