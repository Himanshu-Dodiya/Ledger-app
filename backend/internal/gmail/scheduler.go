package gmail

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/himanshu/ledger-api/internal/db"
	"github.com/himanshu/ledger-api/internal/devices"
	"github.com/himanshu/ledger-api/internal/notify"
)

// StartScheduler runs the Gmail polling loop in-process. It syncs all users whose
// last sync is older than interval, then waits interval before repeating.
// Call in a goroutine; returns when ctx is cancelled.
func StartScheduler(ctx context.Context, pool *db.Pool, interval time.Duration, notifier *notify.Sender) {
	slog.Info("gmail scheduler started", "interval", interval)
	tick := time.NewTicker(interval)
	defer tick.Stop()

	// Run once immediately on startup.
	runAll(ctx, pool, interval, notifier)

	for {
		select {
		case <-ctx.Done():
			slog.Info("gmail scheduler stopped")
			return
		case <-tick.C:
			runAll(ctx, pool, interval, notifier)
		}
	}
}

func runAll(ctx context.Context, pool *db.Pool, olderThan time.Duration, notifier *notify.Sender) {
	ids, err := GetStaleUserIDs(ctx, pool, olderThan)
	if err != nil {
		slog.Warn("gmail scheduler: list users failed", "err", err)
		return
	}
	if len(ids) == 0 {
		return
	}
	slog.Info("gmail scheduler: syncing users", "count", len(ids))
	for _, uid := range ids {
		if ctx.Err() != nil {
			return
		}
		result, err := SyncUser(ctx, pool, uid)
		if err != nil {
			slog.Warn("gmail scheduler: sync failed", "user", uid, "err", err)
			continue
		}
		slog.Info("gmail scheduler: sync done",
			"user", uid,
			"fetched", result.Fetched,
			"inserted", result.Inserted,
			"skipped", result.Skipped,
			"llm", result.LLM,
		)
		if result.Inserted > 0 {
			pushNewTransactions(ctx, pool, uid, result.Inserted, notifier)
		}
	}
}

func pushNewTransactions(ctx context.Context, pool *db.Pool, userID string, count int, notifier *notify.Sender) {
	if notifier == nil {
		return
	}
	tokens, err := devices.GetTokens(ctx, pool, userID)
	if err != nil || len(tokens) == 0 {
		return
	}
	noun := "transaction"
	if count > 1 {
		noun = "transactions"
	}
	notifier.Send(ctx, tokens,
		"New transactions",
		fmt.Sprintf("%d new %s ready to review in Ledger.", count, noun),
	)
}
