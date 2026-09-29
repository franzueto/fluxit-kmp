# Firebase emulator, Security Rules tests, and cleanup backend

Self-contained tooling for the Firebase emulator suite and the baseline
Security Rules tests. It is **not** wired into the Gradle/KMP build and does not
affect any Android/iOS build command.

## Files

| Path | Purpose |
|---|---|
| `../firebase.json` | CLI + emulator configuration (pinned ports) |
| `../firestore.rules` | Baseline authenticated owner-only Firestore Rules |
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

## Scheduled cleanup target (FB-501)

`../functions/index.js` exports `cleanupExpiredData`, a second-generation Cloud
Functions scheduled target for 03:00 UTC daily. The target is pinned to one
instance with one concurrent invocation. Its 30-day retention constant records
`DEC-003b` and `DEC-003e-2`; `FB-502` and `FB-503` will add the tombstone,
Storage, and list-cascade passes. **The FB-501 target only logs a scaffold
message and performs no reads or deletions.** `FB-507` owns development
deployment after those passes have been reviewed. The mobile clients still use
their existing purge path until `FB-504`.

Run the build/metadata check and real local Functions emulator harness from a
clean clone without Firebase login or service-account credentials:

```sh
cd firebase && npm ci
cd ../functions && npm ci
npm run check
npm run test:emulator
```

`test:emulator` starts Functions, Pub/Sub, Firestore, and Storage emulators using
the reserved `demo-fluxit` project, invokes the scheduled target through the
local Functions emulator, asserts an HTTP success, and shuts all emulators down.
Pub/Sub is required for the CLI to initialize scheduled triggers. The Functions
runtime is configured as Node.js 22, which Firebase supports; using a different
local Node.js version may produce an emulator mismatch warning. Port 5001 and
8085 are pinned in `../firebase.json` alongside the existing emulator ports.
This harness checks target registration/invocation only; deletion and restore
race behavior belong to `FB-502`/`FB-503` tests.

## `.firebaserc` is a placeholder

`default` is set to `demo-fluxit`. This is **not** a real Firebase project — the
`demo-` prefix is a Firebase-reserved, emulator-only convention. Per `DEC-002a`
no project has been provisioned yet. `FB-004`/`MAN-001` must add the real
development project alias once the user creates it. Until then any `firebase
deploy` will fail loudly rather than write somewhere unintended.

## Rules scope

These are the Phase 0 **baseline** Rules per `PLAN-002`: deny-by-default,
authenticated, owner-only. Field-level validation, type/range checks,
counter-integrity rules and immutable-ownership enforcement are Phase 6
(`FB-601`/`FB-602`) and are deliberately out of scope here.
