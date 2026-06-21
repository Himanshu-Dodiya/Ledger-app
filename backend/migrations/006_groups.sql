-- Run once in Supabase SQL editor.
--
-- Phase 5: Groups (Goa Trip, Flat Expenses, …) — member containers used to scope shared
-- expenses and settlements. settlements.group_id already exists (migration 005); we add an
-- optional transactions.group_id so an expense can be attributed to a group.

CREATE TABLE IF NOT EXISTS groups (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    name       TEXT        NOT NULL,
    type       TEXT,        -- trip | flat | office | family | other
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS groups_user_idx ON groups (user_id, name);

CREATE TABLE IF NOT EXISTS group_members (
    group_id  UUID NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    person_id UUID NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, person_id)
);
CREATE INDEX IF NOT EXISTS group_members_person_idx ON group_members (person_id);

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS group_id UUID;
CREATE INDEX IF NOT EXISTS transactions_group_idx ON transactions (group_id);
