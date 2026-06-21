-- Run once in Supabase SQL editor.
--
-- Phase 2: People + Tags as first-class entities. People can carry many tags (Family, Room,
-- Office, …) and tags drive fast selection during splitting. No FKs to auth.users so the
-- service role writes without RLS, matching the existing tables.

CREATE TABLE IF NOT EXISTS people (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    name       TEXT        NOT NULL,
    phone      TEXT,
    upi_id     TEXT,
    image_url  TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS people_user_id_idx ON people (user_id, name);

CREATE TABLE IF NOT EXISTS tags (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    name       TEXT        NOT NULL,
    color      TEXT        NOT NULL DEFAULT '#6366F1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);
CREATE INDEX IF NOT EXISTS tags_user_id_idx ON tags (user_id);

-- Many-to-many: a person can belong to several tags.
CREATE TABLE IF NOT EXISTS people_tags (
    person_id UUID NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    tag_id    UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (person_id, tag_id)
);
CREATE INDEX IF NOT EXISTS people_tags_tag_idx ON people_tags (tag_id);
