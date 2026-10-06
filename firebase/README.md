# Firebase setup, emulators and operations

FluxIt uses official Firebase Android and Apple SDKs for Email/Password Auth,
Firestore and Storage in ordinary app builds. The Node tooling here runs separate
Rules/security/backend checks; Gradle does not start emulators for native tests.
Start with [fresh developer setup](../README.md#fresh-developer-setup) to obtain the
gitignored configs for Android `com.fluxit` and iOS `com.fluxit.FluxIt`. Both must
identify the same approved development project and bucket. Auth Email/Password,
Firestore, Storage, reviewed Rules/indexes and scheduled cleanup must be configured
by the project owner for cloud use. Mobile config downloads are not Admin keys;
repository policy still prohibits committing them.

The source cutover uses development configuration (DEC-011). DEC-012 explicitly
limits closure to development and waives production provisioning (MAN-005). Exact
aggregate integrity (FB-601-NB1) and production readiness remain prerequisites if
production scope is reopened. iOS is simulator-only under the permanent DEC-004 waiver.
[Canonical migration status](../FIREBASE_MIGRATION_STATUS.md) owns gates and evidence.

## Files

| Path | Purpose |
|---|---|
| `../firebase.json` | CLI + emulator configuration (pinned ports) |
| `../firestore.indexes.json` | FB-603 query index contract; Phase 5 group indexes retained |
| `../firestore.rules` | FB-601 owner-only Firestore schema, tombstone, and counter checks |
| `../storage.rules` | Baseline authenticated owner-only Storage Rules |
| `../.firebaserc` | Project aliases — **placeholder only**, see below |
| `test/` | Rules, client query, and config/index contract tests |
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

`test/helpers.js` reads ports from `../firebase.json`. `npm run check` verifies
that the Auth, Firestore, and Storage defaults in `../gradle.properties` still
match that CLI configuration (FB-006-NB2). If a default moves, update both files.
On a stock Android emulator, `127.0.0.1`/`localhost` is translated to `10.0.2.2`.
The debug cleartext allowlist only covers `10.0.2.2`, `127.0.0.1` and `localhost`;
changing the host to a LAN IP alone does not grant cleartext access to that IP.
Prefer the supported local AVD route for these checks.

### iOS reads the same endpoints, with no host translation (FB-007)

`composeApp/src/iosMain/kotlin/com/fluxit/firebase/IosFirebaseEmulatorSettings.kt`
re-exposes the same generated `FirebaseEmulatorConfig` constants to Swift through
the `ComposeApp` framework, and `iosApp/iosApp/FirebaseBootstrap.swift` feeds them
to `Auth`/`Firestore`/`Storage` `useEmulator(withHost:port:)`. There is no second
configuration mechanism and no duplicated defaults.

Unlike Android, the host is used **verbatim**. The Android emulator is a separate
virtual machine and needs the `10.0.2.2` loopback alias; the iOS simulator shares
the host's network stack, so `127.0.0.1` already means the machine running the
emulator suite. This project verifies iOS on the simulator; physical-device networking and signing
are outside the permanent DEC-004 scope.

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

The integration spike selected SPM plus Swift bridges as the practical path for
this project. Kotlin/Native cinterop imports C/Objective-C surfaces, and Swift APIs
need an Objective-C-exported surface to be imported; pure Swift modules cannot be
consumed directly through that mechanism. See [Kotlin interop documentation](https://kotlinlang.org/docs/native-objc-interop.html).

That is not a proof that all possible CocoaPods wiring is impossible: mixed-language
pods can expose public Objective-C headers/shims, including some Auth symbols. The
spike did not mechanically establish the completeness of every hypothetical pod
surface (FB-007-NB2). SPM is the integration actually built and verified; switching
would add toolchain and build integration work without a demonstrated benefit.
The existing Xcode project/package lockfile resolves the three required products.

Firebase calls on iOS live in `iosApp/iosApp/Firebase{Auth,List,Item,Storage}Bridge.swift`.
Kotlin `iosMain` implements repository/session/error mapping around bridge protocols;
`commonMain` contracts/models contain no Firebase SDK types. Swift implementations
are registered by `FirebaseBootstrap.start()` before Compose/Koin starts.

### Xcode build-phase, linking and embedding requirements

- **Build phase order matters.** `Compile Kotlin Framework` (the Gradle run script)
  must stay *first*, before `Sources`, because `FirebaseBootstrap.swift` imports
  `ComposeApp` and would not compile if the framework had not been rebuilt yet.
- **Linking is by package product, not `OTHER_LDFLAGS`.** The three Firebase
  products are `packageProductDependencies` on the `iosApp` target and appear in the
  `Frameworks` build phase. The pre-existing `OTHER_LDFLAGS = -framework ComposeApp`
  is unchanged and unrelated.
- **No separate Firebase framework embed/sign phase is needed.** The SPM products build as static libraries and
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
  committed under this repository policy. Consequence: a fresh clone without the plist fails the build at the
  copy step. Fetch it from the Firebase Console before building iOS.
- **`FirebaseApp.configure()` runs in `AppDelegate.application(_:didFinishLaunching...)`**
  (`iosApp/iosApp/FirebaseBootstrap.swift`), attached to the SwiftUI `@main` via
  `@UIApplicationDelegateAdaptor` in `iOSApp.swift`. `iOSApp.swift` had no delegate
  before. That callback runs strictly before any SwiftUI scene or view body, and
  therefore before `ContentView` creates the Compose view controller that starts
  Koin — which is what guarantees `configure()` and the `useEmulator` calls precede
  the first use of any Firebase service instance.

## Prerequisites

- Node.js **22** for the Functions runtime and deployed-development security runner;
  use that version for a consistent local setup. The pinned CLI is a local
  `firebase-tools` devDependency in `package-lock.json`.
- A compatible JDK (17+ for the mobile build); Firestore/Storage emulators also run
  Java processes. Android SDK/AVD and Xcode simulator prerequisites are in the root
  README. Python 3 is needed only for native/report runners.
- Run `npm ci` in `firebase/` and `functions/` to use committed lockfiles. No global
  Firebase CLI is required. Standalone `demo-fluxit` emulator tests need no Firebase
  login or service-account credentials. Dependency/package downloads can need network
  access. Native app builds still require the gitignored mobile configs.
- Start only one emulator set at a time: the suites below share fixed ports. Do not
  run `npm test` while a separately started suite occupies them.

## Commands

```sh
cd firebase
npm ci
npm run check   # config/index contract and port drift checks; no emulators
npm test        # contract checks, then auth+firestore+storage Rules/query tests
npm run emulators   # long-running emulator suite incl. UI at http://127.0.0.1:4000
```

## Local mobile builds

Standalone JS Rules/security/backend runs use **`demo-fluxit`**. Native checks that
use the default app or photos need the project ID/bucket from the ignored mobile
configs, even though Auth/Firestore/Storage traffic goes to local emulators. Some
Android Auth/list/item tests use separate `demo-fluxit` secondary FirebaseApps;
a full native run can therefore produce a multiple-project warning. Both are local
namespaces; a demo-only JS pass is not a pass for the default native app namespace.
The dedicated Auth-only command below uses only the demo test client.

For interactive native app use, install `firebase/` dependencies first, then start
all three mobile emulators in a separate terminal from the repository root:

```sh
FLUXIT_PROJECT_ID=$(node -e 'process.stdout.write(JSON.parse(require("node:fs").readFileSync("composeApp/google-services.json")).project_info.project_id)')
firebase/node_modules/.bin/firebase --config firebase.json --project "$FLUXIT_PROJECT_ID" \
  emulators:start --only auth,firestore,storage
```

This starts local services, not a deployment. Verify the downloaded JSON and plist
match the same development project/bucket. Never launch a native integration app
against the cloud by omitting the emulator flag or starting only one of its three
services. Use an isolated, task-owned emulator process without production data
imports. Stop it with Ctrl-C when finished; without export/import its local data
is discarded. Do not reset a shared emulator or use global deletion/admin shortcuts
on a real project. Native app routing comes from its compiled config; CLI `--project` targeting alone
does not redirect an app.

After the terminal reports Auth/Firestore/Storage ready:

```sh
./gradlew :composeApp:installDebug -Pfluxit.firebase.emulator.enabled=true
```

For iOS, command-local Gradle environment flags propagate through Xcode's framework
build phase. Set `FLUXIT_SIMULATOR_UDID` to a booted Apple Silicon simulator from
`xcrun simctl list devices`:

```sh
FLUXIT_SIMULATOR_UDID='replace-with-booted-simulator-udid'
env 'ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true' \
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -derivedDataPath /tmp/fluxit-local-ios \
  -destination "platform=iOS Simulator,id=$FLUXIT_SIMULATOR_UDID" \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= \
  PROVISIONING_PROFILE_SPECIFIER= build
xcrun simctl install "$FLUXIT_SIMULATOR_UDID" /tmp/fluxit-local-ios/Build/Products/Debug-iphonesimulator/FluxIt.app
xcrun simctl launch "$FLUXIT_SIMULATOR_UDID" com.fluxit.FluxIt
```

Emulator endpoints are compiled into the shared framework. Rebuild without the
flags and reinstall ordinary builds after local testing; removing a launch argument
alone does not disable emulator routing. Global `~/.gradle/gradle.properties`
overrides also affect Xcode builds, so prefer command-local flags and remove any
local override before ordinary use. The retired
`fluxit.firebase.repositories.enabled=false` cannot select Room.

The Android emulator speaks HTTP. The debug manifest and
`composeApp/src/debug/res/xml/network_security_config.xml` permit only the three
loopback names listed above. **Keep the overlay in `src/debug/`, not
`src/androidDebug/`**: that is the directory merged by this KMP/AGP application.
Release builds omit it and retain the platform's cleartext restriction. Do not
weaken release networking or Rules to make an emulator test pass (FB-102-NB3).

If 8080 is occupied, change the CLI Firestore port in a gitignored sibling config
(e.g. `firebase.local.json`) and pass `--config firebase.local.json`; also build
both clients with `fluxit.firebase.emulator.firestore.port=<matching-port>`.
Ad hoc default changes must keep `firebase.json`, `gradle.properties` and test helper
expectations aligned. The Python native runners below require the exact default
loopback ports and reject alternative routing; use defaults for those runners.

## Verification tiers

| Tier | Command/entry point | Evidence and prerequisite |
|---|---|---|
| Unit tests | Root README Gradle commands | Common/platform fake-adapter logic; no live Firebase |
| Config/index contract | `cd firebase`, `npm run check` | Source/config/port checks; no emulator or deployment |
| Rules + JS queries | `cd firebase`, `npm test` | Starts **auth,firestore,storage**, project `demo-fluxit`; no native SDK runtime |
| Backend unit/build | `cd functions`, `npm run check` | Pure cleanup/cascade checks; no Firebase login |
| Backend integration | `cd functions`, `npm run test:emulator` | Starts **functions,pubsub,firestore,storage**, project `demo-fluxit`; synthetic scheduled invocation |
| Android native | Commands below | Manually started mobile emulators and connected AVD; formal instrumentation/JUnit |
| iOS native | Commands below | Built/installed opt-in simulator app; console report parser, not XCTest |
| Cross-platform regression/photo/package | [FB-704 procedure](FB-704-PROCEDURE.md) | Native actual-DI, 16 checkpoints/platform, realtime/photo/offline runners; historical exact paths/UDIDs must be adapted |
| Development cloud security | [FB-604 security procedure](FB-604-SECURITY.md) | Explicit development execution, reviewed assets/IAM, local login and exact fixture cleanup; JS client tier |

These commands do not constitute new execution evidence. FB-706 owns the final
matrix after FB-709 privacy cleanup passes independent review.
No real radio gesture, literal reinstall, manual picker gesture, production smoke,
aged-cloud-photo deletion or physical iOS testing is inferred from scripted checks.

### Android instrumented checks

The Auth suite is **not self-provisioning** (FB-102-NB3). Start its Auth emulator
manually in terminal A from the repository root:

```sh
firebase/node_modules/.bin/firebase --config firebase.json --project demo-fluxit \
  emulators:start --only auth
```

With one connected AVD and configs installed, in terminal B:

```sh
./gradlew :composeApp:connectedDebugAndroidTest \
  -Pfluxit.firebase.emulator.enabled=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fluxit.firebase.auth.FirebaseAuthEmulatorIntegrationTest
```

The class redirects its own secondary app to Auth's local endpoint. Sign-up/sign-in,
restoration/recovery/sign-out use emulator accounts; recovery links stay local.
[Auth emulator behavior](https://firebase.google.com/docs/emulator-suite/connect_auth)

Stop terminal A before a full native run. Start **auth,firestore,storage** using the
mobile-config project command in Local mobile builds, then run:

```sh
./gradlew :composeApp:connectedDebugAndroidTest -Pfluxit.firebase.emulator.enabled=true
```

This ordinary instrumentation build excludes the opt-in regression class requiring
runner-supplied credentials. The command does not start Firebase or an AVD. Review
`composeApp/build/outputs/androidTest-results/connected/` and the Gradle exit status;
never turn missing emulators/network errors into a pass. A full run uses both the
mobile-config and secondary demo namespaces described above.

### iOS native self-checks and provenance

There is no XCTest target. The original FB-103 Auth integration/restoration proof
was developer console output; its reviewer corroborated logs and fixtures without
independently rerunning those emulator-enabled launches (FB-103-NB2). That history
is not reviewer-reproduced evidence. Auth scaffolding later moved to opt-in Kotlin
source sets/Swift hooks in FB-702; ordinary binaries exclude it.

The current Python runner `parity/ios-checks.py` installs the app, terminates/relaunches
between checks, captures PTY stdout, enforces a 120-second report timeout, rejects
failure/throw/missing-report conditions and returns nonzero on failure. It also runs
Auth's separate-process prepare/verify restoration pair. This is a machine-enforced
wrapper around timing-sensitive native console checks, **not XCTest/JUnit parity**
(FB-103-NB3). Gradle `iosSimulatorArm64Test` alone does not exercise the Swift bridge
against Firebase.

Use the manually started mobile-config **auth,firestore,storage** suite, default
ports, and a booted simulator. From the repository root, set the actual UDID;
keep logs locally:

```sh
FLUXIT_SIMULATOR_UDID='replace-with-booted-simulator-udid'
env 'ORG_GRADLE_PROJECT_fluxit.parity.enabled=true' \
  'ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true' \
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -derivedDataPath /tmp/fluxit-native-check-ios \
  -destination "platform=iOS Simulator,id=$FLUXIT_SIMULATOR_UDID" \
  'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= \
  PROVISIONING_PROFILE_SPECIFIER= build
env FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 \
  FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 \
  FIREBASE_STORAGE_EMULATOR_HOST=127.0.0.1:9199 \
  python3 firebase/parity/ios-checks.py \
  --app /tmp/fluxit-native-check-ios/Build/Products/Debug-iphonesimulator/FluxIt.app \
  --device "$FLUXIT_SIMULATOR_UDID"
```

The Auth Kotlin source inclusion requires `fluxit.parity.enabled=true`, its Swift
hook requires `FLUXIT_PARITY`, and runtime requires explicit launch arguments plus
the emulator build gate. The runner supplies the arguments. It reports checks but
does not prove whole-namespace teardown; stop the task-owned suite to discard
unexported fixtures. For exact scoped photo teardown and a fresh default installation,
follow FB-704's procedure, using fresh local state/log paths and current simulator IDs.
Never run its task-specific cleanup on arbitrary existing emulator data. Its trusted
owner/path state file belongs only locally; preserve it on failure for bounded recovery.

After any native run, stop only your emulator process, rebuild without emulator or
parity flags, and reinstall ordinary APKs/simulator apps. Reports record source
revision, date, platform, command/exit/assertions, teardown status and unrun tiers;
raw logs can contain fixture identities and must not be committed.

## Query and index inventory (FB-603)

This inventory comes from `AndroidFirebaseListRepository.kt`,
`AndroidFirebaseItemRepository.kt`, their `iosMain` counterparts,
`iosApp/iosApp/FirebaseListBridge.swift`, `FirebaseItemBridge.swift`, and
`../functions/cleanup.js`/`cascade.js`. Paths below are scoped to the current
authenticated UID on mobile; backend group queries use the Admin SDK.

| Caller | Actual server query | Required index |
|---|---|---|
| Android/iOS list summaries (including metadata listeners) | Full `users/{uid}/lists` collection; no filter or `orderBy` | Default document-name ordering |
| Android/iOS item lists (including metadata listeners) | Full `users/{uid}/lists/{listId}/items` collection; no filter or `orderBy` | Default document-name ordering |
| Android/iOS single-list/item listeners and mutation reads | Exact document path | No field/composite index |
| Android/iOS clear-completed | Scoped items: `isCompleted == true`, `deletedAt == null`, `limit(chunkSize)`; default chunk 400, repeat after tombstoning each page | Merge default collection single-field equality indexes |
| Backend expired-list/item scan | Group `lists`/`items`: `deletedAt <= cutoff`, `orderBy(deletedAt ASC)`, `limit(100)`, then `startAfter(lastSnapshot)` | Ascending group index on `deletedAt` for each group |
| Backend orphan-photo reference check | Group `items`: `photoRef == exactObjectPath`, `limit(100)`, then `startAfter(lastSnapshot)` | Ascending group index on `photoRef` |
| Backend interrupted-cascade discovery | Group `listCleanupJobs`: `orderBy(claimedAt ASC)`, `limit(100)`, then `startAfter(lastSnapshot)` | Ascending group index on `claimedAt` |
| Backend cascade children/journals and empty-job probes | Scoped `items`/`photos`: `limit(100)` (or `limit(1)`); photo-journal pages use `startAfter(lastSnapshot)` | Default document-name ordering |

Mobile active/deleted filtering and `(createdAt, documentId)` ordering happen
in `FirebaseDocumentMapper`, after the raw snapshot. Adding a server-side
ordering would change the handling of pending server timestamps. None is added.

The checked-in `../firestore.indexes.json` already contains all four required
group field overrides from Phase 5, retaining ascending/descending collection
indexes for list/item `deletedAt` and item `photoRef`. It stays unchanged;
`indexes: []` is deliberate. [Firestore supports merging simple equality
indexes](https://firebase.google.com/docs/firestore/query-data/index-overview#use_index_merging),
so clear-completed needs no composite index. Group field indexes must be
explicitly enabled; default collection indexes do not cover them.

`test/config.test.js` checks the declared group scopes, collection indexes used
by equality merging, CLI config, and endpoint defaults. `test/firestore.queries.test.js`
executes equivalent mobile query shapes with the JavaScript client SDK and the
real owner Rules: server snapshots include tombstones for client mapping, foreign
collections are denied, and a 401-match clear-completed fixture selects 400 then
one without selecting incomplete, deleted, or missing-field items. These tests
do not execute the Android or Apple SDKs.
`../functions/test/indexes.emulator.test.js` checks the backend group shapes,
cutoff exclusion, tied timestamps across owners and 100-document page cursors,
and executes production `isReferenced` past a full page of foreign matches.

Passing local queries is **not proof of deployed index readiness**. The
[Firestore emulator does not track compound indexes](https://firebase.google.com/docs/emulator-suite/connect_firestore#indexes)
and accepts valid queries even when production would require a missing index.
The contract checks and documented query analysis provide local evidence only;
FB-608 recorded user-attested reviewed development index readiness, and FB-604
recorded allowed-owner development queries. Those historical checks do not establish
readiness in a newly created project or a later deployment. Re-audit this inventory and
the contract whenever a server filter or ordering changes.

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
finishes. The reviewed development deployment procedure records this order; preserve it for
any future authorized deployment.

After the claim, each transaction deletes at most 100 item documents and
writes a private `listCleanupJobs/{listId}/photos/{itemId}` record for each
referenced photo in the same commit. The journal survives a Storage failure or
process interruption. Each photo must pass the same 30-day creation age and
fresh-reference checks, and is deleted with a generation precondition. A photo
younger than 30 days keeps its journal and job for a later run. The job is
removed only when both item documents and photo journals are empty. Every run
first discovers and resumes existing jobs, including those whose parent is
already absent. No Storage prefix based on `listId` is used. Development deployment/evidence is
recorded in FB-507/FB-504; the mobile tombstone purge path was removed in FB-504.
Clients no longer hard-delete expired tombstones on dashboard load.

The cleanup uses collection-group queries on job `claimedAt`, list/item
`deletedAt`, and item `photoRef`. `../firestore.indexes.json` declares the
required group indexes while retaining collection-scope indexes. These indexes
must be present before the scheduled backend is deployed; the local Firestore emulator does not
prove that deployment state. The function's success log contains counts only.

Run the build/unit checks and real local Functions emulator harness from a
clean clone without Firebase login or service-account credentials:

```sh
cd firebase
npm ci
cd ../functions
npm ci
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

## Cloud targeting and operations

`default` remains `demo-fluxit`, a Firebase-reserved emulator-only project ID.
Per `DEC-002d`, obtain the real development project ID from the gitignored
`google-services.json` and pass it explicitly through `--project "$FLUXIT_PROJECT_ID"`
for every Console-affecting command. Keep that value out of tracked files and
never use the default for deployment. See [FB-507 deployment procedure](FB-507-DEPLOYMENT.md).

Set `FLUXIT_PROJECT_ID` locally using the JSON-reading command above; do not commit
it or change `.firebaserc` to a real project. Cloud project reads, deploys, exports,
and administration require explicit targeting even when a CLI alias is available.
There is no production deployment authorized by these setup instructions. Use the
reviewed development [FB-608 Rules/index deployment](FB-608-DEPLOYMENT.md) and
FB-507 backend procedure only in their approved environment/scope; both include
explicit project flags. Do not run `firebase init` to overwrite repository Rules.
See [CLI project targeting](https://firebase.google.com/docs/cli#project_aliases).

Operations must monitor function errors/retries, cleanup counts, Rules denials,
index readiness and Firestore/Storage usage. Scheduled cleanup is in `us-central1`
and needs deployed group indexes plus Cloud Scheduler/billing setup. Source/unit
checks are not proof of a live schedule. The current live development evidence is
bounded: [FB-504 probe](FB-504-LIVE-PROBE.md) demonstrated Firestore cleanup, with
no aged-photo cloud deletion claimed; [FB-604 results](FB-604-RESULTS.md) documented
client security/query tests. Production requires a separate readiness/architecture
choice, budget alerts and backup/export approval if its scope is reopened; DEC-012
waives MAN-005 for the current development-only closure.

Keep Admin SDK credentials out of mobile builds. Prefer existing approved local
login/IAM workflows over downloading service-account keys. Raw CLI/native logs,
reset links, fixture identities and manifests remain local; report sanitized status,
error codes and counts. Firestore/Storage Rules enforce server ownership, not local
cache erasure. FB-709 now sequences session job/listener teardown, Storage transfer
cancellation, Firestore termination and `clearPersistence()`, client recreation with
preserved settings, and Auth credential removal. Ordinary repository singletons
resolve fresh clients after cleanup. First sign-in refetches from the network;
normal offline persistence stays enabled.

`clearPersistence()` logically removes cached documents and remaining pending writes;
it does not securely overwrite disk bytes or guarantee forensic erasure. Sign-out
can discard unsynced work, and the account dialog explains this. Cleanup failure
keeps the gate closed with a retry affordance. Pending privacy cleanup has its own
15-second budget and can hold the gate beyond ordinary network restoration's
10-second timeout; its failure takes priority over the signed-out timeout fallback.
A local, identity-free pending marker
recovers interrupted cleanup before restoration; a marker write failure is reported
as a cleanup failure rather than success. No durability is claimed when that write
fails. Recovery clears an unstarted client's persistence before termination because
both shipped SDKs can initialize a client from `terminate()` itself.

Auth sign-out removes the SDK credential; Storage has no Firestore-style persistence
clearing API. This app downloads photos into memory, retains no app-owned disk photo
cache, waits for cancellation of active uploads/downloads and releases session/picker memory.
Shipped Android Storage disables HTTP caches for requests; shipped Apple byte
downloads use ephemeral fetcher sessions without a destination file URL. These
paths do not own a persistent HTTP/file photo cache. SDK/OS transient memory
internals are outside a forensic-erasure guarantee. It does
not delete uploaded cloud objects on sign-out. OS-managed data and secure overwrite
remain outside this logical cleanup guarantee. See official
[Android cache API](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore#clearPersistence())
and [Apple cache API](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Classes/Firestore),
[FB-709 results](FB-709-RESULTS.md) and [account behavior](../README.md#accounts-offline-use-and-photos).



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
the client photo preparation policy performs that check. Reviewed development deployment is recorded in FB-608/MAN-007. These source Rules
are not evidence that a different/new project has been deployed or is production-ready.

The deployed-development client security runner, bounded fixture recovery, native
iOS emulator runner and evidence bounds are documented in
[FB-604 security verification](FB-604-SECURITY.md).
