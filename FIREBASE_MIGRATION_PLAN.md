# Firebase Migration Plan

> Plan version: 2.0 (execution-ready)
>
> This file defines architecture and scope. Live task state, evidence, blockers, and the exact resume point are maintained in [`FIREBASE_MIGRATION_STATUS.md`](FIREBASE_MIGRATION_STATUS.md). The four-agent operating procedure is defined in [`FIREBASE_MIGRATION_WORKFLOW.md`](FIREBASE_MIGRATION_WORKFLOW.md). Do not infer progress from this document or from chat history.

## Goal

Replace the Room-backed mobile data layer with Firebase while keeping the existing Android and iOS Compose Multiplatform UI, ViewModels, domain behavior, and repository contracts wherever practical.

The intended end state is:

```text
Compose UI + shared ViewModels
            |
    shared repository contracts
            |
 Firebase-backed repositories
            |
 Auth + Firestore + Cloud Storage
            |
       Android and iOS
```

## Current-state summary

- Android and iOS share their UI and ViewModels in `commonMain`.
- `ListRepository` and `ItemRepository` already isolate consumers from Room.
- `RoomListRepository` and `RoomItemRepository` are bound directly in the common Koin module.
- Lists and items are observed through `Flow`, which maps naturally to Firestore snapshot listeners.
- Photos are copied into app-local storage, and `FluxItem.photoPath` contains an absolute local path.
- Deletes are soft deletes with a five-second UI undo window; old tombstones are purged when the dashboard loads.
- Dashboard list summaries depend on Room queries that calculate total and completed item counts.
- There is currently no account/session model, error presentation, or remote synchronization.

## Confirmed architecture and implementation decisions

### 1. Firebase SDK integration

Use only the official platform SDKs:

- Android uses the official Firebase Android SDK for Authentication, Cloud Firestore, and Cloud Storage.
- iOS uses the official Firebase Apple SDK for Authentication, Cloud Firestore, and Cloud Storage.
- Kotlin code in `androidMain` and `iosMain` adapts the platform SDKs to the repository and service interfaces declared in `commonMain`.
- The existing `expect fun platformModule(): Module` boundary supplies the correct implementations to shared ViewModels at runtime.

Do not add a third-party Firebase KMP wrapper. Firebase SDK types, callbacks, tasks, snapshots, and errors must remain behind the platform boundary and must not appear in shared domain models, ViewModels, or composables.

Expected implementation shape:

```text
commonMain
  AuthRepository, ListRepository, ItemRepository, PhotoStorage
  domain models, ViewModels, UI, shared mapping/validation helpers

androidMain
  AndroidFirebaseAuthRepository
  AndroidFirebaseListRepository
  AndroidFirebaseItemRepository
  AndroidFirebasePhotoStorage
  official Firebase Android SDK initialization and adapters

iosMain
  IosFirebaseAuthRepository
  IosFirebaseListRepository
  IosFirebaseItemRepository
  IosFirebasePhotoStorage
  official Firebase Apple SDK initialization and adapters
```

Some platform adapter code will intentionally be duplicated because the official Android and Apple APIs expose different asynchronous and serialization types. Share schema constants, domain validation, and pure mapping helpers when that remains clean; do not build a large custom Firebase abstraction merely to eliminate a small amount of duplication.

Begin with a short integration spike to verify:

- Android debug and release builds.
- iOS simulator and physical-device builds.
- Official Firebase Apple SDK linking and initialization in the existing Xcode project, including the chosen Kotlin/Native CocoaPods or framework interop configuration.
- Authentication, Firestore listeners, offline writes, and Storage upload/download/delete.
- Compatibility with the project's Kotlin and Compose versions.

### 2. User identity

Use persistent Firebase accounts suitable for multi-device access. Every user's data is keyed by their authenticated Firebase UID, and the same account must resolve to the same UID on Android and iOS.

- Add a real sign-up, sign-in, sign-out, session-restoration, and account-recovery flow.
- Select the exact sign-in providers before implementation and support the same account identity across both platforms.
- Do not use anonymous authentication as the primary identity model.
- Scope Firestore documents and Storage objects to `users/{uid}`.
- Require authentication and ownership checks in Firestore and Storage Security Rules.

Do not ship with permissive development Security Rules.

### 3. Existing Room data

Use a clean cut because the app has not been released and there is no production data to preserve.

- Do not implement a Room-to-Firebase importer.
- Do not upload existing development or test databases.
- Remove Room and its local database after Firebase reaches feature parity and passes verification.
- Existing development installs may be cleared or reinstalled during cutover.

The official platform SDK, persistent-account identity, and clean-cut decisions are final unless this plan is deliberately revised.

## Proposed Firebase model

Use user-scoped documents:

```text
users/{uid}
users/{uid}/lists/{listId}
users/{uid}/lists/{listId}/items/{itemId}
```

Suggested list document:

```text
name: String
icon: String
color: String
createdAt: Timestamp
updatedAt: Timestamp
deletedAt: Timestamp?
totalItems: Number
completedItems: Number
schemaVersion: Number
```

Suggested item document:

```text
listId: String
title: String
description: String?
isCompleted: Boolean
photoRef: String?
createdAt: Timestamp
updatedAt: Timestamp
deletedAt: Timestamp?
schemaVersion: Number
```

Store photos in Cloud Storage under a user-owned path such as:

```text
users/{uid}/items/{itemId}/{photoId}
```

Persist the Storage object path as `photoRef`; do not treat a temporary download URL or an absolute device path as the durable identifier.

### Ordering

The current `MAX(sortOrder) + 1` behavior is unsafe when multiple clients write concurrently. There is no reordering feature today, so initially order by `createdAt` and use document ID as a deterministic tie-breaker. Keep `sortOrder` only if a near-term manual reordering feature requires it; in that case adopt a deliberate ranking scheme rather than calculating a global maximum on the client.

### Summary counts

Keep `totalItems` and `completedItems` on each list document so the dashboard does not need to subscribe to every list's item collection. Update the item and its parent counters atomically with a Firestore batch or transaction for add, complete/uncomplete, delete, restore, and clear-completed operations.

Add tests for counter consistency. Consider a maintenance function or development-only consistency checker that can recalculate counters if a partial migration or old client ever causes drift.

### Timestamps

Use server timestamps for durable `createdAt`, `updatedAt`, and `deletedAt` values. Account for unresolved server timestamps in local snapshot events by retaining a client-time fallback or explicitly modeling a pending timestamp during mapping.

## Implementation phases

### Phase 0: Firebase project and SDK spike

- Inventory the current application IDs, source sets, repository interfaces, build commands, and tests, then record a passing or known-failing Android/iOS baseline before migration changes begin.
- Create separate Firebase projects for development and production, or use distinct registered apps and an explicit environment strategy.
- Register the Android application ID and iOS bundle ID.
- Add Android and iOS Firebase configuration without adding service-account credentials to the client repository.
- Enable the required Authentication provider, Cloud Firestore, and Cloud Storage.
- Add Firebase CLI/emulator configuration and deny-by-default, authenticated owner-only Firestore and Storage Rules before the first application write. Development must never depend on permissive Rules; Phase 6 will harden field validation and complete the denied-access matrix.
- Add the Google Services Gradle plugin and official Firebase Android dependencies to the Android target.
- Integrate the official Firebase Apple SDK with the Kotlin/Native and Xcode build. Prefer the Kotlin CocoaPods integration if it provides the cleanest supported interop for the required Firebase modules; document any Xcode build-phase or linking requirements.
- Initialize the default Firebase app in the Android application and iOS startup path using the platform SDKs.
- Add temporary platform adapters in `androidMain` and `iosMain`; do not put platform Firebase imports in `commonMain`.
- Prove the following on both platforms with a temporary development harness or focused integration test:
  - Obtain an authenticated UID.
  - Write and observe a Firestore document.
  - Perform an offline write and observe it after reconnecting.
  - Upload, download, and delete a small image.
- Document exact dependency versions and initialization requirements.

Exit criterion: the pre-migration baseline is recorded, baseline owner-only Rules are active, and Android and iOS can authenticate and complete the Firestore/Storage smoke test without changing production repositories.

### Phase 1: Authentication/session boundary

- Introduce an `AuthRepository` or equivalent domain-facing session abstraction.
- Model at least loading, signed-out, authenticated, and error states.
- Add an application root that does not create user-scoped Firestore listeners before authentication is resolved.
- Implement the interactive account sign-up, sign-in, recovery, sign-out, and session-restoration flow.
- Ensure sign-out disposes active listeners and clears user-scoped in-memory state.
- Decide whether sign-out should leave Firebase's local persistent cache intact; treat cached user data as sensitive on shared devices.

Exit criterion: the app consistently obtains a UID before displaying user data and handles session changes without leaking data between users.

### Phase 2: Firebase DTOs and repository implementations

- Define storage-neutral DTOs or field mappings separately from domain models so schema changes and server timestamp handling do not leak into the UI.
- Implement `AndroidFirebaseListRepository` and `AndroidFirebaseItemRepository` in `androidMain` using the official Android Firestore SDK.
- Implement `IosFirebaseListRepository` and `IosFirebaseItemRepository` in `iosMain` using the official Apple Firestore SDK.
- Convert each platform SDK's snapshot-listener callbacks to `Flow` with correct cancellation, listener removal, and error propagation. Keep this bridging code in the corresponding platform source set.
- Filter out documents with `deletedAt != null` and apply stable ordering.
- Perform related item/count mutations atomically.
- Define behavior for missing, malformed, or unknown enum values instead of allowing `valueOf` to crash the app.
- Decide whether `purgeExpired()` remains in the interface. Prefer removing client-owned cleanup once server-side cleanup is in place.
- Remove Room repository bindings from the common Koin module. Construct platform Firebase implementations from each `actual platformModule()`, but do not start user-scoped listeners or resolve UID-dependent paths until authentication is resolved.

Exit criterion: all list and item operations work against the Firebase Emulator Suite, and existing ViewModel unit tests still pass.

### Phase 3: Photo migration to Cloud Storage

- Rename the domain concept from `photoPath` to `photoRef` or a similarly storage-neutral name.
- Replace the current local `PhotoStorage` contract with operations appropriate for remote objects, for example upload, load/resolve, and delete.
- Keep the system photo pickers; upload the selected bytes to the authenticated user's Storage path.
- Enforce file-size and supported-image-type limits before upload.
- Resize or compress large images before uploading to control latency, memory, and Storage cost.
- Add loading, retry, and failure states to the item-detail UI.
- Upload the replacement image first, update Firestore second, and delete the old object last. This avoids losing the old image if upload or document update fails.
- On item deletion, arrange reliable Storage cleanup rather than relying solely on the client staying alive.

Exit criterion: photos remain available after reinstall/sign-in on both Android and iOS, and failed replacements do not orphan the item or destroy the previous photo.

### Phase 4: Error, loading, and offline UX

The current ViewModels assume local operations succeed. Remote calls require explicit behavior.

- Add repository/application error types that do not expose Firebase exceptions directly.
- Ensure every saving or picking flag is reset with `try/finally`.
- Add retryable error state or events for create, update, delete, clear-completed, and photo operations.
- Distinguish initial loading, empty data, cached/offline data, and fatal session errors where useful.
- Verify that an offline mutation appears promptly and eventually synchronizes.
- Decide how to communicate pending writes and permanent permission/validation failures.
- Prevent duplicate submissions while an operation is in progress.

Exit criterion: airplane-mode and interrupted-upload testing produces understandable, recoverable behavior rather than indefinite spinners or silent failure.

### Phase 5: Soft-delete cleanup and cascading deletion

- Preserve the five-second undo behavior by setting or clearing `deletedAt`.
- Replace dashboard-triggered local purging with backend-owned cleanup.
- Implement scheduled or event-driven cleanup for expired item tombstones.
- When deleting an expired list, recursively remove its item documents and referenced Storage objects; deleting a Firestore parent document does not delete its subcollections.
- Make cleanup idempotent so retries are safe.
- Decide and document the retention period. It may remain short for exact current behavior, or become longer to tolerate delayed clients.

Exit criterion: deleted documents and photos do not accumulate indefinitely, undo remains functional, and cleanup cannot delete restored records.

### Phase 6: Harden Security Rules, indexes, and emulator tests

- Harden the baseline Firestore Rules so they require authentication and restrict every document to its owning `{uid}` path.
- Validate allowed fields and basic types/ranges where practical.
- Prevent clients from moving data between users.
- Harden the baseline Storage Rules for `users/{uid}/...`, including image size and content-type constraints.
- Add the composite indexes required by active/deleted filters and ordering queries.
- Check rules and indexes into source control with Firebase CLI configuration.
- Test permitted and denied access with the Firebase Emulator Suite, including cross-user reads/writes and photo access.
- Keep privileged service-account keys out of mobile builds and source control.

Exit criterion: emulator tests demonstrate that one user cannot read, mutate, or delete another user's documents or files.

### Phase 7: Switch production bindings and remove Room

- Put the Firebase repositories behind a temporary development flag while comparing behavior with Room.
- Run all unit, integration, and manual platform tests.
- Switch the production Koin bindings to Firebase.
- Remove:
  - Room entities, DAOs, database, and repository implementations.
  - Room and bundled SQLite dependencies/plugins.
  - Room KSP configurations and schema output.
  - Android/iOS database builders.
  - Obsolete local photo storage and path-based image decoding.
- Update the README to describe authentication, cloud persistence, offline behavior, Firebase setup, and emulator usage.

Exit criterion: neither mobile target packages or initializes Room, and all supported functionality uses Firebase.

## Test plan

### Shared unit tests

- Preserve the current ViewModel tests using fake repositories.
- Add failure-path tests for every mutating ViewModel operation.
- Add session transition and sign-out tests.
- Add photo upload/replacement failure tests.
- Add DTO/domain mapping tests, including missing optional fields, pending timestamps, and unknown enum strings.

### Firebase emulator integration tests

- List create, edit, observe, soft delete, restore, and cleanup eligibility.
- Item create, edit, complete, observe, soft delete, restore, hard delete, and clear completed.
- Counter updates for every item mutation, including retries.
- Two clients changing the same list or item.
- Offline write followed by reconnect.
- Authentication and cross-user isolation.
- Storage upload/download/delete and rejected file validation.

### Manual Android and iOS verification

- Fresh install and returning session.
- Background/foreground and process restart while listeners are active.
- Airplane mode at launch and during each mutation.
- Sign-out/sign-in as a different user.
- Photo selection of large, unsupported, and corrupted files.
- Delete/undo and eventual backend cleanup.
- Simultaneous changes from Android and iOS using the same account.

## Operational checklist

- Use separate development and production Firebase environments.
- Enable budget alerts and review Firestore/Storage usage before release.
- Add Crashlytics only as a separate, explicit follow-up; it is not required for the data migration.
- Avoid logging document contents, download URLs, tokens, email addresses, or other user data.
- Define a backup/export policy before treating Firebase as the sole durable store.
- Monitor permission-denied, serialization, upload, and listener failures after rollout.
- Plan Firestore schema evolution with `schemaVersion`; Firebase does not provide Room-style migrations automatically.

## Expected files/areas affected

Implementation will likely touch:

- `gradle/libs.versions.toml`
- `composeApp/build.gradle.kts`
- Android application initialization and configuration
- iOS startup/project configuration
- `commonMain/domain` Firebase-neutral repository/service contracts, models, and mapping helpers
- `androidMain` official Firebase Android repository, auth, listener, and Storage adapters
- `iosMain` official Firebase Apple repository, auth, listener, and Storage adapters
- `commonMain/di/AppModule.kt`
- Android and iOS `actual platformModule()` bindings
- list/item ViewModels for remote error handling
- item-detail photo loading and rendering
- common tests and new emulator integration tests
- Firebase CLI configuration, Firestore Rules, Storage Rules, and indexes
- `README.md`

## Definition of done

- Android and iOS use the same authenticated Firebase dataset.
- All current list, item, search, completion, undo, and photo functionality works on both platforms.
- Firestore realtime changes made on one device appear on the other.
- Supported offline reads/writes recover after reconnecting.
- Security and Storage Rules prevent cross-user access.
- Summary counters remain consistent.
- Deleted documents and photos are eventually cleaned up safely.
- No Room-to-Firebase import or legacy local-data compatibility path remains.
- Room, bundled SQLite, and local absolute photo paths are absent from the final production data path.
- Unit and emulator integration tests pass, and the README documents local setup and release configuration.
