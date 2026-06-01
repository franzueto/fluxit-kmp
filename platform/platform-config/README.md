# :platform:platform-config

`ConfigProvider` + typed `ConfigKey<T>` (ADR-004 defaults) and the real
`Clock.System` / `IdGenerator.System` bindings. `configModule` binds them. No
BuildKonfig, no per-platform code in v1 — the provider serves compile-time
defaults (remote config is a v2 concern).

## Backup / data residency asymmetry (plan/06 §10, ADR-017)

v1 is **local-only with no sync** (ADR-003). The two platforms deliberately treat
device backup differently, and it's worth knowing why before it surprises anyone:

- **Android — DB + photos excluded from auto-backup.** `:android-app` keeps
  `android:allowBackup="true"` but ships `res/xml/backup_rules.xml` (API ≤ 30) and
  `res/xml/data_extraction_rules.xml` (API 31+) that exclude the SQLDelight
  database `fluxit.db` (plus its `-wal`/`-shm` sidecars — write-ahead logging is
  enabled in `DriverFactory`) and the photo store `files/photos/` from both cloud
  backup and device transfer. Restoring un-synced lists/photos onto a new device,
  with no server to reconcile against, would surface stale/confusing data — and
  `:platform:platform-photo`'s storage explicitly assumes `files/photos/` is not
  backed up. (Note the exclude `path` is the bare `fluxit.db`, not
  `databases/fluxit.db`: `domain="database"` already roots at the `databases/`
  dir.)

- **iOS — left on the default iCloud backup.** `IosPhotoStorage` writes under
  `applicationSupport/photos` and the SQLite DB lives in the app's default
  container, both included in iCloud backup. Here that's *desirable*: an iCloud
  restore bringing a user's lists back is the only "sync" v1 effectively offers,
  and it matches user expectation.

**v2 reversal:** once real sync ships, drop the Android excludes so a cloud/device
restore rehydrates lists, and revisit whether the iOS container needs any
exclusion. Tracked in ADR-017 + the two Android rules files.
