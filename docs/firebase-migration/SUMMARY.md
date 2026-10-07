# Firebase migration summary

FluxIt moved from a Room-backed, offline-only data layer to Firebase (Authentication,
Cloud Firestore, Cloud Storage) on Android and iOS, with the shared Compose UI and
ViewModels unchanged. The migration closed on 2026-10-06 for the development environment
only; no production project was ever created.

The full working record (plan, task ledger, review evidence, decision log) was removed
from the tree once the follow-up work finished. It is preserved in git under the tag
`firebase-migration-record`:

```sh
git show firebase-migration-record:FIREBASE_MIGRATION_STATUS.md
git show firebase-migration-record:FIREBASE_MIGRATION_PLAN.md
git ls-tree -r --name-only firebase-migration-record | grep -E '^(firebase|docs)/'
```

Setup, emulators, tests and deployment are documented in the [README](../../README.md) and
[`firebase/README.md`](../../firebase/README.md).

## Architecture

```text
Compose UI + shared ViewModels
            |
    shared repository contracts   (commonMain)
            |
 Firebase-backed adapters          (androidMain / iosMain)
            |
 Auth + Firestore + Cloud Storage
```

- Only the official Firebase Android and Apple SDKs are used; there is no third-party KMP
  wrapper. Firebase types never appear in shared code. Adapters in `androidMain` and
  `iosMain` implement the contracts declared in `commonMain`.
- **On iOS, code that touches Firebase lives in Swift** (`iosApp/iosApp`), bridged into
  Kotlin. The Apple SDK is linked with Swift Package Manager at an exact pinned version,
  not through Kotlin CocoaPods interop.
- Repository failures cross the platform boundary as a `commonMain` `RepositoryException`
  carrying an `ApplicationError`; photo failures use `PhotoStorageException`. The ViewModels
  show the mapped code and offer retry only when retrying can succeed.
- The app root is a session gate. It shows nothing user-scoped until the initial session
  restoration has finished, and falls back to the signed-out screen if restoration takes
  longer than 10 seconds.

## Data model

```text
users/{uid}/lists/{listId}
users/{uid}/lists/{listId}/items/{itemId}
Storage: users/{uid}/items/{itemId}/{photoId}
```

- Lists carry `totalItems` and `completedItems` so the dashboard needs no item listeners.
  They are updated in the same batch or transaction as the item write.
- Deletes are soft (`deletedAt`). The 5-second Undo window is a UI concern; tombstones are
  kept 30 days so another device can recover an accidental delete.
- `photoRef` stores the Storage object path, never a download URL or a device path. The
  Storage Rules match that path at exact depth, so a `photoId` containing `/` is denied.
- Items live under their list in Firestore, but photos have no `listId` segment, so a list
  cascade must read the item documents to find the photos.
- Timestamps are server timestamps; snapshots with a pending timestamp fall back to
  client time.

## Decisions that still shape the code

| Topic | Decision |
|---|---|
| Sign-in | Email and password only, with Firebase's password-reset email. No anonymous identity. Adding a provider later is a new decision. |
| Existing Room data | Clean cut. No importer. Room and bundled SQLite were removed after parity was verified. |
| Edit conflicts | Field-level last-write-wins. Writes patch only changed fields; new documents with fresh auto IDs are exempt. |
| Sign-out | Clears the Firestore persistent cache and cancels transfers, so user A's data is not readable by user B on a shared device. The first load after sign-in is shown as loading, not as an error. |
| Cleanup | A scheduled Cloud Function purges tombstones older than 30 days and cascades list, items and photos on the server. It needs the Blaze plan. |
| Orphan photos | The same job deletes Storage objects that no item references and that are older than 30 days. The grace period is long because an offline client can queue the referencing write for days. |
| Mobile config | `google-services.json` and `GoogleService-Info.plist` are gitignored everywhere. The committed `.firebaserc` names only the reserved `demo-fluxit` project, and every Console-affecting CLI command passes `--project` explicitly. |
| iOS bundle id | `com.fluxit.FluxIt`, with no team suffix, so a signing-team change cannot invalidate the registered plist. |
| iOS target | Simulator only. Physical-device signing was waived because the app is not meant for distribution. |
| Test code in binaries | Integration checks and self-checks compile only with `-Pfluxit.parity.enabled=true` (and `FLUXIT_PARITY` in Swift). Ordinary builds contain none. |

## Known limits

- **Counters are not server-authoritative.** A malicious owner can inflate their own
  `totalItems`/`completedItems` within the per-write bound, and decrements are not clamped
  at zero. This never exposes another user's data. Decide on server-side counters or a
  reconciliation job before any production environment exists.
- **No production environment.** Production provisioning, deployed revision IDs, the
  native-cloud and backend-cloud query tiers and live aged-photo deletion were not
  exercised. Treat them as production verification steps.
- Five `AuthError` mappings (`UserDisabled`, `TooManyRequests`, `NetworkUnavailable`,
  `SessionExpired`, `Unknown`) come from SDK documentation and unit tests, not from codes
  observed on a real SDK. The other five were confirmed against the emulator.
- Firestore queues writes while offline instead of failing them, so a delete failure
  cannot be forced by turning the network off. Failed-delete behavior is covered by
  automated tests only.
- The `restoreSession()` contract returns `Unit`; the session gate waits for the resolved
  session through the session flow. Both adapters publish before returning, so there is no
  bug today. Return the resolved session if a third implementation is ever added.

## Rollback points

Room was removed in two commits, so the old data layer can be recovered from history:

- `5e33b5b~1` (`a3b90bc`): last commit where the app is bound to Room.
- `5e33b5b`: bindings switched to Firebase, Room code still present.
- `d85f68a`: Room and bundled SQLite removed.

The Room data was never migrated, so rolling back means returning to an empty local
database. The Firebase development project is unaffected by any rollback.
