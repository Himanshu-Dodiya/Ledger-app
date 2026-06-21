-- Run once in Supabase SQL editor (or via psql against the direct connection URL).
--
-- Phase 1 of the "personal finance platform" evolution: PDF statement import.
-- Extends the unified `transactions` table with the few fields statement rows carry
-- that SMS/Gmail did not, and adds an `import_batches` table so the Import Center can
-- show history and so re-importing the same file is cheap & idempotent.
--
-- NOTE: nothing here is destructive. Existing rows keep working unchanged.

-- ---- transactions: new optional columns ----
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS counterparty_upi TEXT,           -- payee/payer VPA, e.g. paytm-citybus1@ptybl
    ADD COLUMN IF NOT EXISTS bank_account     TEXT,           -- e.g. "State Bank of India 9347", "RBL 9246"
    ADD COLUMN IF NOT EXISTS txn_time         TIMESTAMPTZ,    -- full timestamp when the source provides one
    ADD COLUMN IF NOT EXISTS note             TEXT,           -- remarks / tags from the statement
    ADD COLUMN IF NOT EXISTS import_batch_id  UUID;           -- FK-less link to import_batches.id

-- The `source` column is plain TEXT (no enum constraint), so the widened set
-- (gpay_pdf | paytm_pdf | phonepe_pdf | bank_pdf | csv | qr) needs no DDL change.

-- ---- import_batches: one row per file/sync the user imports ----
CREATE TABLE IF NOT EXISTS import_batches (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    source      TEXT        NOT NULL,   -- gpay_pdf | paytm_pdf | bank_pdf | csv | ...
    file_name   TEXT,
    inserted    INT         NOT NULL DEFAULT 0,
    duplicates  INT         NOT NULL DEFAULT 0,
    errors      INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS import_batches_user_id_idx ON import_batches (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS transactions_batch_idx     ON transactions (import_batch_id);
