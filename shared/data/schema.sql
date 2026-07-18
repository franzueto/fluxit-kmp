-- dev/franzueto/fluxit/shared/data/db/Items.sq

--
-- FK notes:
--   list_id  REFERENCES list(id) ON DELETE CASCADE — engine-level cascade is
--            dead code in v1 (ADR-006b: lists are forever-tombstoned; we never
--            hard-delete a list). Kept for safety + v2-sync compaction.
--   photo_id REFERENCES photo(id) ON DELETE SET NULL — fires when the photo
--            no item ever points at a deleted photo row.

import kotlin.Boolean;
import kotlinx.datetime.Instant;

CREATE TABLE item (
    id              TEXT NOT NULL PRIMARY KEY,
    list_id         TEXT NOT NULL REFERENCES list(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    subtitle        TEXT,
    description     TEXT,
    is_completed    INTEGER AS Boolean NOT NULL DEFAULT 0,
    is_starred      INTEGER AS Boolean NOT NULL DEFAULT 0,
    photo_id        TEXT REFERENCES photo(id) ON DELETE SET NULL,
    sort_order      REAL NOT NULL,
    created_at      INTEGER AS Instant NOT NULL,
    updated_at      INTEGER AS Instant NOT NULL,
    deleted_at      INTEGER AS Instant
);

CREATE INDEX item_list_idx ON item (list_id, is_completed, sort_order) WHERE deleted_at IS NULL;
CREATE INDEX item_photo_idx ON item (photo_id);

-- Single result set ordered (is_completed ASC, sort_order ASC) so the UI
-- a second query. Spec's "returns two logical groups" refers to the
-- mapper's output shape, not the SQL.

-- dev/franzueto/fluxit/shared/data/db/Lists.sq

--
-- Column conventions (uniform across all FluxIt tables; see ADR-006b):
--   id          TEXT NOT NULL PRIMARY KEY — UUID v4 lowercase (ADR-006a).
--   icon        TEXT NOT NULL             — FluxItIconRef enum name (ADR-006c).
--   color       TEXT NOT NULL             — ColorToken enum name (ADR-006c).
--   sort_order  REAL NOT NULL             — fractional indexing; lower = higher on screen.
--                                           Newest list at top: new row gets currentMin - 1.0
--   deleted_at  INTEGER (nullable)        — soft-delete tombstone (ADR-006b); every read
--                                           query filters WHERE deleted_at IS NULL.

import dev.franzueto.fluxit.shared.domain.model.ColorToken;
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef;
import kotlin.Boolean;
import kotlinx.datetime.Instant;

CREATE TABLE list (
    id              TEXT NOT NULL PRIMARY KEY,
    name            TEXT NOT NULL,
    icon            TEXT AS FluxItIconRef NOT NULL,
    color           TEXT AS ColorToken NOT NULL,
    is_starred      INTEGER AS Boolean NOT NULL DEFAULT 0,
    sort_order      REAL NOT NULL,
    created_at      INTEGER AS Instant NOT NULL,
    updated_at      INTEGER AS Instant NOT NULL,
    deleted_at      INTEGER AS Instant
);

CREATE INDEX list_sort_idx ON list (deleted_at, sort_order);
CREATE INDEX list_starred_idx ON list (is_starred) WHERE deleted_at IS NULL;

-- dev/franzueto/fluxit/shared/data/db/Photos.sq

--
-- Photos have an asymmetric lifecycle vs. lists / items / reminders
-- (ADR-006b): soft-delete → 24h grace → hard-delete by the photo janitor
-- NOT cascade from item.softDelete — a photo may be referenced by items
-- in other live lists; the janitor is the only place that checks live
-- references and reaps.
--
-- No updated_at column: photos are immutable once ingested. The
-- records the resulting file's metadata.
--
-- relative_path is relative to the app sandbox photo root, never an
-- absolute path (resolved by PhotoStorage.resolveAbsolute() at the
-- Compose / SwiftUI image-loading site).

import kotlinx.datetime.Instant;

CREATE TABLE photo (
    id              TEXT NOT NULL PRIMARY KEY,
    relative_path   TEXT NOT NULL,
    mime_type       TEXT NOT NULL,
    width_px        INTEGER NOT NULL,
    height_px       INTEGER NOT NULL,
    byte_size       INTEGER NOT NULL,
    created_at      INTEGER AS Instant NOT NULL,
    deleted_at      INTEGER AS Instant
);

CREATE INDEX photo_orphan_idx ON photo (deleted_at);

-- dev/franzueto/fluxit/shared/data/db/Reminders.sq

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
--
-- recurrence is nullable TEXT containing the RecurrenceRule sealed-class
--
-- schedules the notification with WorkManager / UNUserNotificationCenter
-- and writes back the request id via setPlatformHandle.

import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule;
import dev.franzueto.fluxit.shared.domain.model.ReminderOwnerType;
import kotlin.Boolean;
import kotlinx.datetime.Instant;

CREATE TABLE reminder (
    id              TEXT NOT NULL PRIMARY KEY,
    owner_type      TEXT AS ReminderOwnerType NOT NULL,
    owner_id        TEXT NOT NULL,
    fires_at        INTEGER AS Instant NOT NULL,
    recurrence      TEXT AS RecurrenceRule,
    platform_handle TEXT,
    is_active       INTEGER AS Boolean NOT NULL DEFAULT 1,
    created_at      INTEGER AS Instant NOT NULL,
    updated_at      INTEGER AS Instant NOT NULL,
    deleted_at      INTEGER AS Instant
);

CREATE INDEX reminder_owner_idx ON reminder (owner_type, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX reminder_fires_at_idx ON reminder (fires_at, is_active) WHERE deleted_at IS NULL;
