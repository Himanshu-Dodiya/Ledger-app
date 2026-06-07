-- Run once in Supabase SQL editor (or via psql against the direct connection URL).
-- Creates the devices table used for FCM token registration and push notifications.

CREATE TABLE IF NOT EXISTS devices (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL,   -- auth.users.id — no FK so service-role writes work without RLS
    fcm_token    TEXT        NOT NULL,
    platform     TEXT        NOT NULL DEFAULT 'android',
    model        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, fcm_token)
);

CREATE INDEX IF NOT EXISTS devices_user_id_idx ON devices (user_id);

-- Tidy up stale tokens (tokens older than 90 days with no check-in).
-- Run periodically or add to a cron job.
-- DELETE FROM devices WHERE last_seen_at < now() - INTERVAL '90 days';
