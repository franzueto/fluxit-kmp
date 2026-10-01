# FB-709 local sign-out privacy verification

Scope: DEC-003a logical local cleanup on Android and Apple, without disabling ordinary offline persistence. This procedure uses existing restrictive Rules and synthetic local emulators only. It does not deploy, erase cloud data, prove forensic erasure, or claim manual picker/radio gestures. DEC-012 limits closure to development and waives production provisioning; FB-706 owns the final wider matrix.

## Lifecycle and SDK contract

Production Koin binds SDK-neutral `SessionAuthRepository`, `SessionWork` and scoped repository wrappers. Cleanup closes the session gate, synchronously clears screen/navigation ViewModel memory, marks cleanup pending, cancels and joins scoped listeners/mutations/photo work, waits for terminal native Storage transfers, clears Firestore persistence while stopped, recreates the native handle with its previous settings, then removes Auth credentials and the pending marker. No success or credential change is permitted while cleanup fails. A canceled credential change re-arms recovery before allowing data work.

The pending marker is a boolean only, in Android private SharedPreferences / Apple UserDefaults. SDK/store failure is visible and retryable. A failed marker write does not guarantee durability across process death. Network restoration normally has a 10-second gate budget; pending privacy cleanup has its own 15-second budget and can hold that gate longer. A cleanup error takes priority over the normal signed-out timeout fallback.

Official Android Firestore 26.0.2 and Apple Firebase SDK 12.19.2 APIs require `clearPersistence()` on an unstarted or terminated instance. It deletes cached documents and queued writes; `terminate()` alone leaves queued writes and unresolved pending Tasks. Shipped source initializes a client from `terminate()` even when no client existed. Therefore recovery first tries `clearPersistence()` on the unstarted handle, and takes terminate+clear fallback only for the exact SDK failed-precondition error. Repositories/Swift bridges resolve fresh handles per call; settings, emulator routing and offline persistence are retained.

Android Storage 22.0.1 `cancel()` can remain asynchronous during a resumable-upload start retry. Cleanup waits for terminal completion within its existing budget; it reports failure while a task remains active. Stream downloads use official `getStream` with an explicitly bounded reader because `getBytes` hides its cancellable stream task and root `activeDownloadTasks` does not enumerate it. The reader allocates at most the existing 5 MiB output ceiling, reads at most ceiling+1 before rejection, and closes streams on success/failure. Swift retains upload/download tasks through their completion callbacks and waits for cancellation completion.

Shipped Storage networking uses Android `NetworkRequest.setUseCaches(false)` and an Apple `StorageFetcherService` with no custom session configuration; GTMSessionFetcher creates an ephemeral session, and the app's `getData` path has no destination file URL. This proves no app-managed persistent HTTP/file photo cache on these paths. It does not promise forensic removal or purging OS/SDK transient memory internals. Source/version hashes are recorded in evidence.

Sources: [Android Firestore API](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore), [Apple Firestore API](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Classes/Firestore), [Android Storage reference](https://firebase.google.com/docs/reference/android/com/google/firebase/storage/StorageReference), [Android Storage task](https://firebase.google.com/docs/reference/android/com/google/firebase/storage/StorageTask), [official Android SDK source](https://github.com/firebase/firebase-android-sdk), [official Apple SDK source](https://github.com/firebase/firebase-ios-sdk).

## Reproduction

Use the repository's normal supported JDK/Node paths and a connected Android AVD plus booted iOS simulator. The concrete paths/UDID used for this task are recorded in results; adapt device paths on another machine. Start only Auth/Firestore/Storage emulators for the ignored mobile configuration's development namespace, loading the checked-in restrictive Rules. Never start a second suite against the same ports or use a global reset.

```sh
export JAVA_HOME=/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home
export PATH=/tmp/fluxit-node22/node_modules/node/bin:$PATH
fluxit_fb709_project=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync("composeApp/google-services.json")).project_info.project_id)')
XDG_CONFIG_HOME=/tmp/fluxit-fb709-emulator-config firebase/node_modules/.bin/firebase --config firebase.json --project "$fluxit_fb709_project" emulators:start --only auth,firestore,storage
```

Build the opt-in test APKs and iOS simulator app. Both parity and emulator gates are required; ordinary builds omit the diagnostics.

```sh
./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -Pfluxit.parity.enabled=true -Pfluxit.firebase.emulator.enabled=true
ORG_GRADLE_PROJECT_fluxit.parity.enabled=true ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb709-emulator-ios -clonedSourcePackagesDirPath /Users/franzueto/Library/Developer/Xcode/DerivedData/iosApp-hbawgxixifennwfnlhinwgjuddqj/SourcePackages -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' 'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build
FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 FIREBASE_STORAGE_EMULATOR_HOST=127.0.0.1:9199 python3 firebase/session-cleanup/native.py --app /tmp/fluxit-fb709-emulator-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device 1B6EFEA8-AF39-4C81-A8AE-2132050AD13C --adb /Users/franzueto/Library/Android/sdk/platform-tools/adb
python3 -m unittest discover -s firebase/session-cleanup -p 'test_*.py'
```

The runner inventories actual roots plus collection-group lists/items in both the configured development namespace and `demo-fluxit`, plus actual accounts/photos, before writes. It requires zero initial state and persists an exact four-owner / twelve-document / eight-allowed-photo-path manifest before seeding. Local manifest/logs may contain synthetic credentials and identities: retain locally, publish hashes/counts only. Native checks use actual production Koin Auth/List/Item/Photo wrappers; only deterministic test cleanup-failure and photo-ID dependencies are substituted.

The normal phase primes complete cached documents, queues two offline writes, cancels listeners/jobs on injected cleanup failure, retries real SDK teardown, tests A→B and same-user cache absence, proves original server values survive, and reuses realtime/photo operations. A secondary Storage app pointed at an absent loopback endpoint exercises real terminal upload and download cancellation. Android registers both tasks explicitly: the SDK global root task map is keyed by path and can hide one simultaneous task at the same path. Android's synthetic retry budget deliberately outlasts cleanup: failure while active, then successful retry after terminal canceled. Production SDK retry settings are unchanged. Fixture observation has a separate bounded window and never converts an active task into cleanup success.

Prepare/recover phases use real force-stop/terminate and separate OS process launches while preserving SDK files/preferences. Prepare persists one pending offline write per platform and a pending marker; recover clears before credential/network resolution. Independent Admin reads confirm the prepared server baseline was never overwritten by replayed writes. Cached SDK handle identity/settings are also checked after recreation.

Cleanup validates exact UID plus normalized expected synthetic email, exact document/photo paths and namespace before any deletion; it refuses foreign owners, extra accounts and unlisted paths. It captures actual cleanup observations locally before deleting only manifest-owned paths/accounts/photos, and verifies roots/lists/items/accounts/photos all zero afterward in both relevant namespaces. If interrupted, retain the original manifest and run only the scoped recovery:

```sh
python3 firebase/session-cleanup/cleanup.py --manifest /absolute/local/diagnostic-directory/trusted-manifest.json
```

After tests, build ordinary Debug/Release without parity/emulator flags, inspect binaries for diagnostic/Room absence, reinstall ordinary apps, and stop only the task-owned emulator process. Replay the sanitized evidence inventory with `python3 firebase/session-cleanup/verify.py`.
