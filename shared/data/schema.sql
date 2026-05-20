-- dev/franzueto/fluxit/shared/data/db/Items.sq

-- Items.sq — Phase 03 §2 Items table.
--
-- FK notes:
--   list_id  REFERENCES list(id) ON DELETE CASCADE — engine-level cascade is
--            dead code in v1 (ADR-006b: lists are forever-tombstoned; we never
--            hard-delete a list). Kept for safety + v2-sync compaction.
--   photo_id REFERENCES photo(id) ON DELETE SET NULL — fires when the photo
--            janitor (§7) hard-deletes an orphan; engine-level safety net so
--            no item ever points at a deleted photo row.

CREATE TABLE item (
    id              TEXT NOT NULL PRIMARY KEY,
    list_id         TEXT NOT NULL REFERENCES list(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    subtitle        TEXT,
    description     TEXT,
    is_completed    INTEGER NOT NULL DEFAULT 0,
    is_starred      INTEGER NOT NULL DEFAULT 0,
    photo_id        TEXT REFERENCES photo(id) ON DELETE SET NULL,
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

-- dev/franzueto/fluxit/shared/data/db/Photos.sq

-- Photos.sq — Phase 03 §2 Photos table.
--
-- Photos have an asymmetric lifecycle vs. lists / items / reminders
-- (ADR-006b): soft-delete → 24h grace → hard-delete by the photo janitor
-- (§7, scheduled by Phase 04's PhotoJanitor use case). Soft-delete does
-- NOT cascade from item.softDelete — a photo may be referenced by items
-- in other live lists; the janitor is the only place that checks live
-- references and reaps.
--
-- No updated_at column: photos are immutable once ingested. The
-- platform-photo layer (Phase 06) re-encodes captures to JPEG q=0.85
-- max-dim 2048 (resolved §12 row 4) before write; the data layer just
-- records the resulting file's metadata.
--
-- relative_path is relative to the app sandbox photo root, never an
-- absolute path (resolved by PhotoStorage.resolveAbsolute() at the
-- Compose / SwiftUI image-loading site).

CREATE TABLE photo (
    id              TEXT NOT NULL PRIMARY KEY,
    relative_path   TEXT NOT NULL,
    mime_type       TEXT NOT NULL,
    width_px        INTEGER NOT NULL,
    height_px       INTEGER NOT NULL,
    byte_size       INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX photo_orphan_idx ON photo (deleted_at);

-- dev/franzueto/fluxit/shared/data/db/Reminders.sq

-- Reminders.sq — Phase 03 §2 Reminders table.
--
-- Owner FK is polymorphic (owner_type discriminates between LIST and ITEM
-- targets). SQLite can't express a discriminated FK; reminder.owner_id has
-- no engine-level FK by design. Integrity is application-enforced — the
-- use-case layer only calls RemindersRepository.schedule(spec) holding a
-- typed ListId or ItemId from a freshly-observed live row. The cascade
-- side is also application-level (ADR-006b §Decision: ListsRepository
-- .softDelete cascades to LIST-owned reminders; ItemsRepository.softDelete
-- cascades to ITEM-owned reminders).
--
-- owner_type is stored as 'LIST' | 'ITEM' (uppercase, matching the enum
-- name); §3's OwnerTypeAdapter wraps it as a typed enum.
--
-- recurrence is nullable TEXT containing the RecurrenceRule sealed-class
-- JSON (resolved §12 row 1: full v1 set — None / Daily / Weekly / Monthly).
-- §3's RecurrenceRuleAdapter handles the JSON round-trip.
--
-- platform_handle is nullable until the platform layer (Phase 06) actually
-- schedules the notification with WorkManager / UNUserNotificationCenter
-- and writes back the request id via setPlatformHandle.

CREATE TABLE reminder (
    id              TEXT NOT NULL PRIMARY KEY,
    owner_type      TEXT NOT NULL,
    owner_id        TEXT NOT NULL,
    fires_at        INTEGER NOT NULL,
    recurrence      TEXT,
    platform_handle TEXT,
    is_active       INTEGER NOT NULL DEFAULT 1,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX reminder_owner_idx ON reminder (owner_type, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX reminder_fires_at_idx ON reminder (fires_at, is_active) WHERE deleted_at IS NULL;
