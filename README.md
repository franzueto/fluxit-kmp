# FluxIt

A list-making app with Email/Password accounts, cloud persistence and multi-device
sync, built with **Kotlin Multiplatform** and **Compose Multiplatform** for Android
and iOS simulator. Shared UI uses system-adaptive light and dark themes.

The ordinary app binds to Firebase Authentication, Cloud Firestore and Cloud
Storage through the official Android and Apple SDKs. Room and its bundled SQLite
dependencies have been removed; Firebase's own persistence and system libraries
remain. This is a development setup, not a production release. How and why the app
moved to Firebase is summarized in [the migration summary](docs/firebase-migration/SUMMARY.md).

## Fresh developer setup

1. Install Android Studio, Android SDK 36 and a compatible JDK (17 or newer for
   this Gradle 8.13/AGP 8.9.2 build). Android runs on API 26+. Use the committed
   Gradle wrapper; its first run needs network access for dependencies.
2. For iOS, use macOS, Xcode with an installed iOS 15+ simulator runtime, and an
   Apple Silicon simulator (`iosSimulatorArm64`). Open `iosApp/iosApp.xcodeproj`;
   the project resolves its pinned Firebase Apple SDK through Swift Package
   Manager on first build. CocoaPods is not required. The supported iOS target is
   the simulator; physical-device signing/testing is out of scope.
3. Obtain access to the approved **development** Firebase project. In Firebase
   Console → Project settings → General → Your apps, download configs for these
   exact registered apps into the paths below. If the apps do not exist, the
   project owner must register them first. Both files must describe the same
   project and Storage bucket.

   | Platform | Registered identifier | Local file |
   |---|---|---|
   | Android | Package `com.fluxit` | `composeApp/google-services.json` |
   | iOS | Bundle ID `com.fluxit.FluxIt` (case-sensitive) | `iosApp/GoogleService-Info.plist` |

   Keep the exact filenames, with no download suffix such as `(2)`. The Android
   Google Services plugin reads the module-root JSON; Xcode already references
   the plist in Copy Bundle Resources. A fresh clone cannot complete those build
   steps until its configs are present. These mobile configs contain project/app
   identifiers, not Admin credentials, but this repository intentionally ignores
   them in every environment. Never commit or paste them. See official
   [Android setup](https://firebase.google.com/docs/android/setup),
   [Apple setup](https://firebase.google.com/docs/ios/setup) and
   [Console app settings](https://support.google.com/firebase/answer/7000104?hl=en).
4. For cloud use, the project owner enables Authentication → Sign-in method →
   **Email/Password**, provisions Firestore and Storage, and deploys the reviewed
   owner-only Rules/indexes and scheduled cleanup backend. Use the approved
   development environment; a new project requires that
   setup before cloud CRUD works. Do not choose permissive test-mode Rules.
   Email-link, anonymous and federated sign-in are not app login flows. See
   [provider setup](https://firebase.google.com/docs/auth/android/password-auth)
   and [Firebase setup and operations](firebase/README.md).

Verify that the two downloaded files remain ignored:

```sh
git check-ignore composeApp/google-services.json iosApp/GoogleService-Info.plist
```

The committed `.firebaserc` points only to `demo-fluxit`, an emulator placeholder.
**Every Console-affecting CLI command must pass `--project <id>` explicitly**;
never rely on that default for deployment or cloud administration. Development
and production config/deployment are separate concerns. Only a development
environment exists; production provisioning was deliberately left out. Exact aggregate
counter integrity (see [known limits](docs/firebase-migration/SUMMARY.md#known-limits))
must be resolved before any production scope. See [deployment targeting](firebase/README.md#cloud-targeting-and-operations).

## Build and run

From the repository root, after installing the configs:

```sh
./gradlew :composeApp:installDebug
```

Launch **FluxIt** on a connected Android emulator, or run `composeApp` in Android
Studio. Emulator routing is disabled by default, so this ordinary build connects
to the configured cloud project. To work locally, follow the
[emulator instructions](firebase/README.md#local-mobile-builds) before launching.

For iOS, run the `iosApp` scheme in Xcode on an Apple Silicon simulator. The
`Compile Kotlin Framework` build phase invokes
`:composeApp:embedAndSignAppleFrameworkForXcode` before compiling Swift. Simulator
CLI builds use ad-hoc signing for Firebase Auth Keychain access:

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= \
  PROVISIONING_PROFILE_SPECIFIER= build
```

Replace `<SIMULATOR_UDID>` with a booted simulator from `xcrun simctl list devices`.
Use `Release` for the corresponding configuration build. Never carry emulator or
self-check flags into ordinary builds.

## Accounts, offline use and photos

Sign up once with Email/Password, then sign in with the same account on Android
and iOS to access the same UID-scoped lists/items/photos. Password recovery sends
a reset email; the Auth emulator exposes its reset link locally instead of sending
mail. The dashboard account menu provides sign-out. The sample-data button writes
sample lists/items into the signed-in user's current backend; it is not a private
local Room seed.

Firestore caches previously read data and queues supported offline writes for
reconnect. Screens distinguish initial loading, cached data and pending writes;
a pending write is not proof that the server accepted it. An empty cache does not
prove the account has no cloud data. Conflicting writes can be reconciled by the
server; operations requiring a transaction or server read can fail offline.
Android and Apple persistence is enabled by default by their SDKs.
[Firestore offline semantics](https://firebase.google.com/docs/firestore/manage-data/enable-offline)

Offline access applies after a session has resolved. A cold start revalidates the
stored account with the server before displaying user data; no network can produce
a session error, and a hung restoration falls back to the auth screen after ten
seconds with a retry action. This is not guaranteed offline cold-start access.
Retry transient failures; use **Sign out and retry** for an invalid stored session.

Photo selection uses the system library picker. Upload preparation validates image
bytes, rejects unsupported/corrupt input, and resizes/recompresses when needed
(25 MiB source ceiling, 2048 px long edge, 5 MiB upload ceiling; JPEG/PNG/WebP).
Storage is remote: reopening a photo can require downloading it again. There is no
app-managed durable offline photo cache or background upload queue. Upload failure
keeps the old attachment; replacement uploads the new object, saves its reference,
then removes the old object. Retry visible failures after reconnecting.

Sign-out closes user-scoped work, clears navigation/screen memory, cancels active
photo transfers, and removes locally cached documents and remaining queued writes
before reporting success. **Changes that have not synced can be discarded.** Auth
credentials are removed, and the next sign-in starts with an empty document cache;
its first load needs the network. A cleanup failure keeps user data closed and offers
retry before another sign-in. Offline persistence remains enabled for normal use.

This is logical SDK deletion, not secure disk overwriting or a forensic erasure
guarantee. It does not delete server documents or already-uploaded photos; orphan
photos retain the backend grace period. See the
[migration summary](docs/firebase-migration/SUMMARY.md) for the policy.

## Deletes, operations and privacy

Deleting a list/item marks a tombstone with a five-second undo action. Shared
clients do not purge expired tombstones on dashboard load. The backend's daily
03:00 UTC cleanup makes tombstones eligible after 30 days, cascades expired lists
with resumable private jobs, and removes orphan photos only after their upload
age reaches 30 days and no owning active or tombstoned item references them.
Eligibility is not a guarantee of deletion at exactly 30 days; failed invocations
or younger photo journals require a later run. See [cleanup details](firebase/README.md#scheduled-cleanup-target).

User data lives in authenticated `users/{uid}` paths. Photos use Storage references,
not public download URLs. The app adds no Analytics or Crashlytics integration.
Keep email addresses, document contents, raw logs, reset links, tokens, passwords,
service-account keys and fixture manifests out of Git and shared reports. Cloud
deployment logs should use sanitized error categories and cleanup counts. Before
production, resolve budget alerts, backup/export policy and the outstanding exact
aggregate counter architecture decision; current Rules bound counter
types/ranges/direction but do not prove exact totals for a malicious owner.

## Clean-cut reinstall

Legacy development Room databases and local photo paths are **not imported or
uploaded**. After deciding those old local fixtures can be discarded, clear
Android app storage or uninstall/reinstall FluxIt; delete and reinstall the app
in the iOS simulator. This destroys old local development data and any unsynced
local changes. Do not use Android `install -r` as proof of a clean cut, because it
preserves app data. iOS Auth Keychain credentials may survive app deletion; sign
out explicitly and verify the account gate when checking a fresh session.

A reinstall does not delete Firebase cloud accounts/data. Sign back in to fetch
server-acknowledged data from the same backend. Emulator data is separate from
cloud data and is ephemeral unless explicitly exported. Clean-cut instructions
are not evidence that a literal reinstall/manual picker gesture was tested.

## Tests and code layout

```sh
./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest \
  :composeApp:iosSimulatorArm64Test
```

These are shared/platform unit tests, not live Firebase integration checks.
[Firebase verification tiers](firebase/README.md#verification-tiers) cover Rules,
backend, Android instrumentation and opt-in iOS runners, including prerequisites
and evidence limits. Pull requests to `main` run the Android unit tests, the Rules tests
and the Cloud Functions tests in `.github/workflows/ci.yml`.

- `commonMain`: backend-neutral models/contracts, mapping/validation, auth gate,
  ViewModels, Navigation 3 and Compose UI; no Firebase SDK types.
- `androidMain`: official Firebase Android adapters, Koin bindings, photo picker
  and byte decoding/preparation; `src/debug` contains emulator cleartext overlay.
- `iosMain`: Kotlin adapters and bridge protocols; `iosApp/iosApp` implements the
  official Apple SDK calls in Swift and hosts Compose.
- `commonTest`, `androidUnitTest`, `iosTest`: unit tests; `androidInstrumentedTest`
  and opt-in `firebaseParity*` source directories: native Firebase diagnostics.
- `firebase/`: local Rules/security/verification tooling; `functions/`: scheduled
  cleanup. See [Firebase README](firebase/README.md) for dependencies and commands.

Stack: Compose Multiplatform Material 3, MVVM/StateFlow, Koin, Navigation 3,
kotlinx-datetime, Kotlin UUID, Firebase Auth/Firestore/Storage. Camera capture,
reminders/notifications, calendar/starred tabs and Fastlane are outside this scope.
