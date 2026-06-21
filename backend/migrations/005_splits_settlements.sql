-- Run once in Supabase SQL editor.
--
-- Phase 3: Expense splitting (Splitwise model) + settlements.
--
-- A transaction can be split among participants. Each participant is either YOU
-- (person_id IS NULL = the account owner) or a person. Exactly one participant per
-- transaction is the payer (is_payer = true). `share_amount` is the resolved amount that
-- participant owes of the bill (computed server-side from share_type/share_value), so
-- balances are a simple sum. Settlements record money that clears those balances.

CREATE TABLE IF NOT EXISTS splits (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    transaction_id UUID        NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    person_id      UUID        REFERENCES people (id) ON DELETE CASCADE,  -- NULL = you
    is_payer       BOOLEAN     NOT NULL DEFAULT false,
    share_type     TEXT        NOT NULL DEFAULT 'equal',   -- equal|percent|exact|shares
    share_value    NUMERIC,                                 -- pct / exact amount / share units
    share_amount   NUMERIC     NOT NULL DEFAULT 0,          -- resolved amount owed of the bill
    settled        BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS splits_txn_idx    ON splits (transaction_id);
CREATE INDEX IF NOT EXISTS splits_user_idx   ON splits (user_id);
CREATE INDEX IF NOT EXISTS splits_person_idx ON splits (person_id);

CREATE TABLE IF NOT EXISTS settlements (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    from_person_id UUID        REFERENCES people (id) ON DELETE SET NULL,  -- NULL = you
    to_person_id   UUID        REFERENCES people (id) ON DELETE SET NULL,  -- NULL = you
    amount         NUMERIC     NOT NULL,
    transaction_id UUID        REFERENCES transactions (id) ON DELETE SET NULL,
    group_id       UUID,       -- phase 5
    status         TEXT        NOT NULL DEFAULT 'completed', -- pending|completed
    upi_ref        TEXT,
    note           TEXT,
    settled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS settlements_user_idx ON settlements (user_id, created_at DESC);

-- Convenience flag so the transactions list can show a "split" badge without a join.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS is_split BOOLEAN NOT NULL DEFAULT false;
