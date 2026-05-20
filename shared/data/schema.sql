-- dev/franzueto/fluxit/shared/data/db/Lists.sq

-- Lists.sq — Phase 03 §2 Lists table.
--
-- Column conventions (uniform across all FluxIt tables; see ADR-006b):
--   id          TEXT NOT NULL PRIMARY KEY — UUID v4 lowercase (ADR-006a).
--   icon        TEXT NOT NULL             — FluxItIconRef enum name (ADR-006c).
--   color       TEXT NOT NULL             — FluxItColorToken enum name (ADR-006c).
--   sort_order  REAL NOT NULL             — fractional indexing; lower = higher on screen.
--                                           Newest list at top: new row gets currentMin - 1.0
--                                           (resolved §12 row 5).
--   *_at        INTEGER (epoch ms UTC)    — adapter converts to kotlinx.datetime.Instant in §3.
--   deleted_at  INTEGER (nullable)        — soft-delete tombstone (ADR-006b); every read
--                                           query filters WHERE deleted_at IS NULL.

CREATE TABLE list (
    id              TEXT NOT NULL PRIMARY KEY,
    name            TEXT NOT NULL,
    icon            TEXT NOT NULL,
    color           TEXT NOT NULL,
    is_starred      INTEGER NOT NULL DEFAULT 0,
    sort_order      REAL NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX list_sort_idx ON list (deleted_at, sort_order);
CREATE INDEX list_starred_idx ON list (is_starred) WHERE deleted_at IS NULL;
