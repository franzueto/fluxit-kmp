-- dev/franzueto/fluxit/shared/data/db/Items.sq

-- Items.sq — Phase 03 §2 Items table.
--
-- FK notes:
--   list_id  REFERENCES list(id) ON DELETE CASCADE — engine-level cascade is
--            dead code in v1 (ADR-006b: lists are forever-tombstoned; we never
--            hard-delete a list). Kept for safety + v2-sync compaction.
--   photo_id will gain `REFERENCES photo(id) ON DELETE SET NULL` in §2D when
--            the photo table lands. Plain TEXT column for now keeps §2B
--            green on its own.

CREATE TABLE item (
    id              TEXT NOT NULL PRIMARY KEY,
    list_id         TEXT NOT NULL REFERENCES list(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    subtitle        TEXT,
    description     TEXT,
    is_completed    INTEGER NOT NULL DEFAULT 0,
    is_starred      INTEGER NOT NULL DEFAULT 0,
    photo_id        TEXT,
    sort_order      REAL NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX item_list_idx ON item (list_id, is_completed, sort_order) WHERE deleted_at IS NULL;
CREATE INDEX item_photo_idx ON item (photo_id);

-- Single result set ordered (is_completed ASC, sort_order ASC) so the UI
-- mapper (Phase 04) can partition into TO BUY / COMPLETED sections without
-- a second query. Spec's "returns two logical groups" refers to the
-- mapper's output shape, not the SQL.

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
