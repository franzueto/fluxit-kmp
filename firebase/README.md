# Firebase emulator, Security Rules tests, and cleanup backend

Self-contained tooling for the Firebase emulator suite and Security Rules
tests. It is **not** wired into the Gradle/KMP build and does not
affect any Android/iOS build command.

## Files

| Path | Purpose |
|---|---|
| `../firebase.json` | CLI + emulator configuration (pinned ports) |
| `../firestore.rules` | FB-601 owner-only Firestore schema, tombstone, and counter checks |
| `../storage.rules` | Baseline authenticated owner-only Storage Rules |
| `../.firebaserc` | Project aliases — **placeholder only**, see below |
| `test/` | `@firebase/rules-unit-testing` Rules tests |
| `../functions/` | FB-501 scheduled cleanup target and local invocation harness |

## Pinned emulator ports

| Emulator | Port |
|---|---|
| Authentication | 9099 |
| Cloud Firestore | 8080 |
| Cloud Storage | 9199 |
| Cloud Functions | 5001 |
| Pub/Sub (scheduled trigger emulation) | 8085 |
| Emulator UI | 4000 |
| Emulator hub | 4400 |

The Firestore emulator also opens a UI websocket on 9150 (assigned by the
emulator, not configurable in `firebase.json`). Ports are pinned here so the
Rules tests have a stable target.

### Client-side endpoints are overridable, not hard-coded (FB-006)

`8080` is a collision-prone default (Tomcat, Spring Boot, many local dev
servers). The Android and iOS clients therefore do **not** hard-code it. They
read a generated Kotlin constants object produced by the
`:composeApp:generateFirebaseEmulatorConfig` Gradle task from these
`gradle.properties` values:

| Gradle property | Default |
|---|---|
| `fluxit.firebase.emulator.enabled` | `false` |
| `fluxit.firebase.emulator.host` | `127.0.0.1` |
| `fluxit.firebase.emulator.auth.port` | `9099` |
| `fluxit.firebase.emulator.firestore.port` | `8080` |
| `fluxit.firebase.emulator.storage.port` | `9199` |

Override them in `~/.gradle/gradle.properties`, via an `ORG_GRADLE_PROJECT_*`
environment variable, or on the command line:

```sh
./gradlew :composeApp:assembleDebug \
  -Pfluxit.firebase.emulator.enabled=true \
  -Pfluxit.firebase.emulator.firestore.port=8580
```

The defaults above still match `../firebase.json` and `test/helpers.js`. If the
default Firestore port is ever moved off `8080`, all three must move together.
On the Android emulator, a configured host of `127.0.0.1`/`localhost` is
translated to `10.0.2.2` automatically; a physical device needs the host set to
the development machine's LAN address explicitly.

### iOS reads the same endpoints, with no host translation (FB-007)

`composeApp/src/iosMain/kotlin/com/fluxit/firebase/IosFirebaseEmulatorSettings.kt`
re-exposes the same generated `FirebaseEmulatorConfig` constants to Swift through
the `ComposeApp` framework, and `iosApp/iosApp/FirebaseBootstrap.swift` feeds them
to `Auth`/`Firestore`/`Storage` `useEmulator(withHost:port:)`. There is no second
configuration mechanism and no duplicated defaults.

Unlike Android, the host is used **verbatim**. The Android emulator is a separate
virtual machine and needs the `10.0.2.2` loopback alias; the iOS simulator shares
the host's network stack, so `127.0.0.1` already means the machine running the
emulator suite. A physical iOS device does not, so a device developer must set
`fluxit.firebase.emulator.host` to the machine's LAN address explicitly.

## Firebase Apple SDK integration (FB-007)

**Integration method: Swift Package Manager, declared in the Xcode project.**

| Item | Value |
|---|---|
| Package | `https://github.com/firebase/firebase-ios-sdk.git` |
| Version rule | `exactVersion` **12.19.2** |
| Products linked | `FirebaseAuth`, `FirebaseFirestore`, `FirebaseStorage` |
| Transitive pins | see `iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` (committed lockfile) |
| Minimum iOS | 15.0 — the SDK's own `Package.swift` declares `.iOS(.v15)`, so the project's existing `IPHONEOS_DEPLOYMENT_TARGET = 15.0` was **not** raised |

### Why SPM and not the Kotlin CocoaPods plugin

The plan prefers Kotlin CocoaPods *if it gives the cleanest supported interop*. It
does not here:

- `FirebaseStorage` has no public Objective-C headers at all (pure Swift since the
  11.x line), and `FirebaseAuth` is Swift-implemented behind a thin ObjC shim.
  Kotlin/Native cinterop consumes Objective-C/C headers only and cannot import a
  Swift module, so two of the three required modules are not reliably reachable
  from `iosMain` regardless of how the pods are wired.
- `pod` is not installed on this machine and the system Ruby is 2.6, so the plugin
  would add a Ruby/gem toolchain prerequisite to every developer machine and to CI.
- The plugin replaces the existing `Compile Kotlin Framework` run-script phase with
  its own pod-based integration and forces an `.xcworkspace`, which would invalidate
  the `xcodebuild -project ...` command recorded as baseline evidence in `FB-000`
  and `FB-012`.

Direct manual linking of the `.xcframework` bundles was also rejected: it requires
hand-managing ~14 transitive dependencies with no lockfile.

**Consequence to carry into Phase 2:** Firebase types are reachable from Swift, not
from `iosMain` Kotlin. iOS Firebase repository adapters must therefore be written in
Swift and injected back across the framework boundary, or reached through a Kotlin
`expect`/`actual` whose iOS `actual` delegates to a Swift implementation.

### Xcode build-phase, linking and embedding requirements

- **Build phase order matters.** `Compile Kotlin Framework` (the Gradle run script)
  must stay *first*, before `Sources`, because `FirebaseBootstrap.swift` imports
  `ComposeApp` and would not compile if the framework had not been rebuilt yet.
- **Linking is by package product, not `OTHER_LDFLAGS`.** The three Firebase
  products are `packageProductDependencies` on the `iosApp` target and appear in the
  `Frameworks` build phase. The pre-existing `OTHER_LDFLAGS = -framework ComposeApp`
  is unchanged and unrelated.
- **No embed/sign step is needed.** The SPM products build as static libraries and
  link into the app binary; only their resource bundles (`Firebase_*.bundle`,
  `GoogleUtilities_*.bundle`, `gRPC_*.bundle`, `leveldb_*.bundle`, `nanopb_*.bundle`,
  `abseil_*.bundle`) are copied into the `.app`, automatically.
- **First build needs network access** to resolve the package graph. Afterwards the
  clone lives in the derived-data path. CI should either allow that fetch or pass
  `-clonedSourcePackagesDirPath` at a cached location.
- **`GoogleService-Info.plist` must be in Copy Bundle Resources.** It is referenced
  from the project as `iosApp/GoogleService-Info.plist` and is a member of the
  `Resources` build phase. The **path reference** is tracked in `project.pbxproj`;
  the **file itself stays gitignored** per `DEC-002b`, so nothing secret is
  committed. Consequence: a fresh clone without the plist fails the build at the
  copy step. Fetch it from the Firebase Console before building iOS.
- **`FirebaseApp.configure()` runs in `AppDelegate.application(_:didFinishLaunching...)`**
  (`iosApp/iosApp/FirebaseBootstrap.swift`), attached to the SwiftUI `@main` via
  `@UIApplicationDelegateAdaptor` in `iOSApp.swift`. `iOSApp.swift` had no delegate
  before. That callback runs strictly before any SwiftUI scene or view body, and
  therefore before `ContentView` creates the Compose view controller that starts
  Koin — which is what guarantees `configure()` and the `useEmulator` calls precede
  the first use of any Firebase service instance.

## Prerequisites

- Node.js (developed against v24) and a JDK (the Firestore and Storage
  emulators are Java processes).
- `npm install` in this directory. `firebase-tools` is a local devDependency;
  **no global install and no `firebase login` is required**, because all
  commands below use the reserved `demo-` project id `demo-fluxit`, which the
  CLI treats as emulator-only and never contacts Google for.

## Commands

```sh
cd firebase
npm install
npm test        # starts auth+firestore+storage emulators, runs Rules tests, shuts down
npm run emulators   # long-running emulator suite incl. UI at http://127.0.0.1:4000
```

## Scheduled cleanup target (FB-501–FB-503)

`../functions/index.js` exports `cleanupExpiredData`, a second-generation Cloud
Functions scheduled target for 03:00 UTC daily. The target is pinned to one
instance with one concurrent invocation and a 540-second timeout. `FB-502`
adds item tombstone and orphan-photo cleanup in `../functions/cleanup.js`.
The two passes share one 30-day retention constant (`DEC-003b` and
`DEC-003e-2`). Eligible item documents are deleted only after a transactional
re-read of `deletedAt`, so a restore before the transaction wins. Photos are
reclaimed only after their creation age reaches 30 days and a fresh Firestore
query finds no owning item document referencing the exact `photoRef`, including
soft-deleted items. Deletion has a Storage generation precondition, so a newer
upload at the same path is preserved. Only the exact
`users/{uid}/items/{itemId}/{photoId}` path is in scope; Storage has no list ID
segment (`PLAN-006`/`PLAN-007`). A failed pass aborts the invocation and can be
retried; repeated runs skip already-deleted resources.

`FB-503` adds expired-list cascade in `../functions/cascade.js`. The schedule
runs it before standalone item cleanup, so a list's item `photoRef`s are not
lost. A claim transaction re-reads the list tombstone, creates a durable
`users/{uid}/listCleanupJobs/{listId}` job, and deletes the parent list in one
atomic commit. Both mobile restore implementations use a field-scoped Firestore
`update`: a restore committed before the claim wins and preserves all children;
a restore attempted after the claim fails because the parent is absent. The
job collection has no client match in the owner-only Firestore Rules, so only
the Admin SDK can create, change, or remove a claim.

The owner-only Rules also require an item write's list parent to exist after
the write batch, deny list creation/updates while a cleanup claim exists, and
deny direct client hard deletion of a list. Clients use `deletedAt` updates for
soft deletion; only the Admin cleanup job removes the parent. These guards
reject delayed offline item writes after the claim, batches that delete a list
and create a child together, and re-creation of a claimed list ID. Backend
Admin SDK writes bypass these Rules. Deploy the tightened Firestore Rules and
the indexes **before** deploying or enabling the scheduled cleanup function;
otherwise an old client write could create an untracked orphan after a job
finishes. `FB-507` must verify this order in the development project.

After the claim, each transaction deletes at most 100 item documents and
writes a private `listCleanupJobs/{listId}/photos/{itemId}` record for each
referenced photo in the same commit. The journal survives a Storage failure or
process interruption. Each photo must pass the same 30-day creation age and
fresh-reference checks, and is deleted with a generation precondition. A photo
younger than 30 days keeps its journal and job for a later run. The job is
removed only when both item documents and photo journals are empty. Every run
first discovers and resumes existing jobs, including those whose parent is
already absent. No Storage prefix based on `listId` is used. `FB-507` owns
development deployment after review. The mobile clients retain their current
purge path until `FB-504`.

The cleanup uses collection-group queries on job `claimedAt`, list/item
`deletedAt`, and item `photoRef`. `../firestore.indexes.json` declares the
required group indexes while retaining collection-scope indexes. These indexes
must be present before the scheduled backend is deployed; the local Firestore emulator does not
prove that deployment state. The function's success log contains counts only.

Run the build/unit checks and real local Functions emulator harness from a
clean clone without Firebase login or service-account credentials:

```sh
cd firebase && npm ci
cd ../functions && npm ci
npm run check
npm run test:emulator
```

`test:emulator` starts Functions, Pub/Sub, Firestore, and Storage emulators using
the reserved `demo-fluxit` project, invokes the scheduled target through the
local Functions emulator, exercises synthetic item/photo fixtures, including a
620-item cascade and a partial Storage failure/retry, and shuts all emulators
down. Unit tests cover the exact age boundary, retry after failure, photo path
validation, and replacement-generation safety. Emulator tests prove a restore
before claim preserves all children and attempts after claim fail across pages
and during photo-journal processing.
Pub/Sub is required for the CLI to initialize scheduled triggers. The Functions
runtime is configured as Node.js 22, which Firebase supports; using a different
local Node.js version may produce an emulator mismatch warning. Port 5001 and
8085 are pinned in `../firebase.json` alongside the existing emulator ports.
These tests do not exercise live development deployment.

## `.firebaserc` defaults to the emulator project

`default` remains `demo-fluxit`, a Firebase-reserved emulator-only project ID.
The real development project is `fluxit-dev` (`MAN-001` is complete). Every
development deployment must pass `--project fluxit-dev` explicitly; the
default must never be used for deployment. See [FB-507 deployment procedure](FB-507-DEPLOYMENT.md).

## Rules scope

Firestore Rules now validate the complete list/item field sets and types,
immutable creation/schema/path ownership fields, exact owner/item photo paths,
server-time tombstones, nonnegative `completedItems <= totalItems` bounds, and
the expected direction of parent counter movement for each item write.
Owner-only paths, private cleanup jobs, and parent-existence/claim guards remain.
The emulator matrix includes valid Android/iOS-shaped atomic item/counter writes
and denied malformed, cross-user, and out-of-range writes. Firestore Rules
cannot enumerate an arbitrary batch's item writes to prove that its aggregate
counter delta is exact; a bounded, internally consistent but dishonest counter
increment remains possible and requires a server-authoritative counter design
if that threat must be eliminated. Storage Rules allow an authenticated owner
to read/delete photos only at `users/{uid}/items/{itemId}/{photoId}` and to
create nonempty JPEG, PNG, or WebP objects no larger than 5 MiB. Existing
objects cannot be overwritten. Android and iOS upload adapters derive MIME
metadata from the already-supported image byte signatures; extensionless
photo IDs otherwise upload as `application/octet-stream`. Storage Rules can
validate declared MIME metadata and size, but cannot decode the image bytes;
the client photo preparation policy performs that check. These checked-in
changes are local; `FB-608` owns reviewed development deployment.
