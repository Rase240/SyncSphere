-- V1: the only persistent table. Presence and cursor positions are deliberately NOT stored here;
-- they are temporary and live in Redis / in memory instead.

CREATE TABLE documents (
    id          UUID         PRIMARY KEY,
    title       VARCHAR(120) NOT NULL,
    content     TEXT         NOT NULL DEFAULT '',
    -- Incremented by Hibernate (@Version) on every accepted edit. Used for optimistic concurrency.
    revision    BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    -- Defence in depth: the application validates these too, but the database is the last line.
    CONSTRAINT documents_title_not_blank   CHECK (length(trim(title)) > 0),
    CONSTRAINT documents_content_max_chars CHECK (char_length(content) <= 50000),
    CONSTRAINT documents_revision_positive CHECK (revision >= 0)
);

-- The document list is ordered by most recently edited.
CREATE INDEX idx_documents_updated_at ON documents (updated_at DESC);
