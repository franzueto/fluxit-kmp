# FB-701 development parity procedure

This is developer execution evidence for independent review. Only the orchestrator
changes canonical task status. No production deployment or manual PASS is implied.
Pre-P7 rollback point: `462bac331698f0a10beaa253919a510ce30c54ab` on `epic/firebase`.
Keep the reviewed FB-701 commit as the immediate FB-702 source rollback point too.

## Safety and compilation

Use only local Auth/Firestore/Storage emulators with the checked-in hardened Rules.
The native default project is read from ignored mobile configuration to match the
bundled clients; no actual identifier or credential belongs in saved evidence.
Legacy independent Android SDK apps use the fixed `demo-fluxit` namespace. Emulator
multi-project warnings do not alter Rules. Do not deploy or connect this matrix to
cloud. The native runner refuses absent/foreign emulator endpoint environment values.

`fluxit.firebase.repositories.enabled=true` selects the real Firebase repository
bindings. It remains `false` in tracked defaults. The independent
`fluxit.parity.enabled=true` flag includes shared fixtures in `iosMain` and the
Android instrumentation fixture. Without it, no parity fixture enters iOS production
sources. Android shared fixture sources belong only to `androidInstrumentedTest`.
Swift also requires `-D FLUXIT_PARITY` and the explicit launch argument, and Kotlin
checks both generated emulator/repository flags. Ordinary builds have no new shipped
parity harness. Existing older iOS selfchecks have their own emulator/launch gates;
FB-103-NB1 removal/migration is due before FB-702 source cutover.

Run from repository root. Node 22, Firebase CLI, Java, Android SDK, Xcode and one
booted stock Android AVD/iOS simulator must be available. This workstation's local
Node runtime is `/tmp/fluxit-node22/node_modules/node/bin`.

```bash
set -euo pipefail
export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
fluxit_fb701_device=$(xcrun simctl list devices booted -j | node -e 'let s="";process.stdin.on("data",d=>s+=d);process.stdin.on("end",()=>{const d=Object.values(JSON.parse(s).devices).flat().filter(x=>x.state==="Booted");if(d.length!==1)throw Error("Exactly one booted iOS simulator required");process.stdout.write(d[0].udid)})')
fluxit_fb701_adb="$ANDROID_HOME/platform-tools/adb"
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest   -Pfluxit.parity.enabled=true -Pfluxit.firebase.emulator.enabled=true   -Pfluxit.firebase.repositories.enabled=true

env 'ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true'   'ORG_GRADLE_PROJECT_fluxit.firebase.repositories.enabled=true'   'ORG_GRADLE_PROJECT_fluxit.parity.enabled=true'   xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug   -derivedDataPath /tmp/fluxit-fb701-ios   -destination "platform=iOS Simulator,id=$fluxit_fb701_device"   'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY'   CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=   PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb701-xcode.log 2>&1
```

Start only local emulators in a separate terminal. Stop them after the matrix;
never import/export a live dataset. Native fixtures contain synthetic accounts/data.

```bash
set -euo pipefail
export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
export XDG_CONFIG_HOME=/tmp/fluxit-fb701-emulator-config
fluxit_fb701_project=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync("composeApp/google-services.json")).project_info.project_id)')
firebase/node_modules/.bin/firebase --config firebase.json   --project "$fluxit_fb701_project" emulators:start --only auth,firestore,storage
```

With the emulator processes ready and the variables above set:

```bash
set -euo pipefail
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
export FIREBASE_STORAGE_EMULATOR_HOST=127.0.0.1:9199
python3 firebase/parity/native.py   --app /tmp/fluxit-fb701-ios/Build/Products/Debug-iphonesimulator/FluxIt.app   --device "$fluxit_fb701_device" --adb "$fluxit_fb701_adb"   > /tmp/fluxit-fb701-native.log 2>&1
python3 firebase/parity/ios-checks.py   --app /tmp/fluxit-fb701-ios/Build/Products/Debug-iphonesimulator/FluxIt.app   --device "$fluxit_fb701_device" > /tmp/fluxit-fb701-ios-checks.log 2>&1
"$fluxit_fb701_adb" -s emulator-5554 shell am instrument -w -r   -e notClass com.fluxit.parity.FirebaseRoomParityInstrumentedTest   com.fluxit.test/androidx.test.runner.AndroidJUnitRunner   > /tmp/fluxit-fb701-android-full.log 2>&1
python3 firebase/parity/report.py --android /tmp/fluxit-fb701-android-full.log   --apple /tmp/fluxit-fb701-ios-checks.log --native /tmp/fluxit-fb701-native.log
node --test --test-concurrency=1 firebase/test/config.test.js   firebase/test/firestore.rules.test.js firebase/test/storage.rules.test.js   firebase/test/firestore.queries.test.js > /tmp/fluxit-fb701-rules.log 2>&1
```

`adb am instrument` can return shell success even when tests fail. The report
parser requires the exact complete 88-test report, all 11 Apple reports/counts,
both 16-checkpoint comparisons, bidirectional realtime/photo results, offline
server acknowledgement and verified zero-fixture summary. A missing/failed report
is an error. Raw console logs stay in temporary directories and must not be committed.

The runner creates one random local Auth account and signs both native SDKs into it.
Both use the real application's Koin graph, not an alternate binding module. The
shared scenario runs against fresh real Room databases and actual Firebase adapters.
Android waits on a live listener for Apple's list edit and completion; Apple waits
for Android's list edit and item creation. Both upload/download real Storage bytes,
replace the shared reference, and verify deletion. Apple explicitly disables the
real Firestore SDK network, proves cached data and queued local pending writes,
reenables it and requires a server-acknowledged snapshot. Android's full suite
separately proves offline/pending/reconnect with independent SDK connections.

Teardown stops both app processes, lists only this run's exact synthetic owner's
lists/items/photos, deletes descendants before parents and deletes that Auth owner.
It verifies no remaining lists/photos and looks up the exact Auth UID to prove absence.
The emulator-only admin literal is not a credential. No global cloud deletion,
bucket scan, backend cleanup invocation or deployment is performed. If teardown fails,
the run fails; inspect local diagnostics before discarding the emulator process.
Legacy selfcheck fixtures are in-memory emulator-only and discarded when it stops.

## Defaults, rollback and comparison boundaries

After native runs, rebuild ordinary binaries and shared tests without all three
command-local flags or the Swift symbol. This restores generated configuration and
proves new iOS parity types absent from the ordinary framework's exported header.
First run default Android Room graph test before the flagged builds as well:

```bash
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
"$fluxit_fb701_adb" -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
"$fluxit_fb701_adb" -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
"$fluxit_fb701_adb" -s emulator-5554 shell am instrument -w -r   -e class com.fluxit.di.RepositoryDiInstrumentedTest   com.fluxit.test/androidx.test.runner.AndroidJUnitRunner
./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest   :composeApp:iosSimulatorArm64Test :composeApp:assembleRelease
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug   -derivedDataPath /tmp/fluxit-fb701-default-ios   -destination "platform=iOS Simulator,id=$fluxit_fb701_device"   CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=   PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb701-default-xcode.log 2>&1
```

Parity compares observable content/order/counts and undo/retry behavior. UUIDs,
server timestamps and internal order ranks intentionally differ. UID scoping,
authentication, cached/pending remote state, clean-cut data adoption and backend
30-day retention are approved migration changes; they are not asserted equivalent
to Room's local store/one-minute client purge. Search/error/retry/5-second undo UI
are covered by shared ViewModel and real Android Compose suites; the backend cleanup
suite supplies fresh synthetic expiry/cascade proof separately.

Do not interpret practical normal-client counter consistency as adversarial exact
aggregate integrity. FB-601-NB1 remains OPEN for the production architecture decision
before production-environment cutover. Native cloud, production, physical-device,
manual photo-picker/lifecycle UI gestures and aged-photo cloud tiers are not claimed.
MAN-003 is permanently WAIVED for simulator-only scope. Before FB-702, remove/move
the older shipped iOS Auth harness and silence/remove release SessionTrace raw-UID
logging. Android actual default/Firebase app graph assertions supply FB-207-NB1
runtime evidence; the reviewer/orchestrator owns disposition.

Official platform transaction guarantees and Rules evaluation context:
[Transactions and contention](https://firebase.google.com/docs/firestore/transaction-data-contention),
[transaction Rules](https://firebase.google.com/docs/firestore/manage-data/transactions#security_rules_limits).
The bounded retry is based on fresh observed behavior in this matrix, not a claim
that every PERMISSION_DENIED is contention: only a changed server item existence,
completion or tombstone can trigger at most three additional full transactions.
Stable schema/session/authorization denials remain terminal.

Offline snapshot translation uses the official SDK's local server-timestamp estimates
on both platforms. This affects provisional local timestamps only; committed writes
still use server timestamps. Actual stored nulls remain invalid required timestamps.
The native Android offline tests now require visible list/item content and individual
document observations before reconnect, as well as real pending metadata. Official
references: [Android estimates](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/DocumentSnapshot.ServerTimestampBehavior),
[Apple estimates](https://firebase.google.com/docs/reference/swift/firebasefirestore/api/reference/Enums/ServerTimestampBehavior).
