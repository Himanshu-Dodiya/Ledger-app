// cmd/worker runs the Gmail polling scheduler as a standalone binary.
// For Phase 1 the scheduler can run in-process inside cmd/api; this binary
// exists so it can be deployed separately in Phase 2.
package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	slog.Info("worker starting — Gmail scheduler not yet implemented (Task 5)")
	<-ctx.Done()
	slog.Info("worker stopped")
}
