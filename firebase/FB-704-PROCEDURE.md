# FB-704 photo-path cleanup procedure

Development/source scope only (DEC-011), no deployment or production/manual PASS.
Initial clean rollback: `d85f68a87a70539df64104701b117039cbda3298` (reviewed FB-703).
Use an isolated checkout for rollback, preserving shared edits and ignored mobile config.
Historical FB-701/702/703 evidence and runners remain frozen against their own snapshots.

Executed 2026-10-01 UTC on the existing Android emulator and iPhone17/iOS26.5 simulator.
All Gradle/Xcode build invocations below used the existing supported JDK via
`env JAVA_HOME='/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home'`.
Commands ran from repository root; builds were sequential. Local cache/device/network
access used approved execution outside the filesystem sandbox.

## Fresh clean and ordinary builds

```bash
export JAVA_HOME=/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home
./gradlew clean :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:assembleDebugAndroidTest :composeApp:linkReleaseFrameworkIosSimulatorArm64 --rerun-tasks > /tmp/fluxit-fb704-clean-gradle.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb704-default-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb704-default-debug-xcode.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -derivedDataPath /tmp/fluxit-fb704-release-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb704-default-release-xcode.log 2>&1
```

149/149 clean tasks executed; shared Debug230/Release230/iOS295, no failures/errors/skips.
The two new iOS tests account for293→295; two Android decoder tests are instrumented.
Ordinary Apple builds used fresh derived-data directories and ad-hoc simulator signing.
Existing nonfatal release lint metadata diagnostics remain disclosed.

## Local emulator photo regressions

Start task-owned emulators in a separate terminal/session with the existing ignored
mobile project config. This command uses the configured identifier without printing or
committing it, and performs no deployment:

```bash
fluxit_fb704_project=$(/tmp/fluxit-node22/node_modules/node/bin/node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync("composeApp/google-services.json")).project_info.project_id)')
env PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH" JAVA_HOME="$JAVA_HOME" XDG_CONFIG_HOME=/tmp/fluxit-fb704-emulator-config firebase/node_modules/.bin/firebase --config firebase.json --project "$fluxit_fb704_project" emulators:start --only auth,firestore,storage > /tmp/fluxit-fb704-emulators.log 2>&1
```

After ready, an actual loopback preflight verified accounts/users/photos0:

```bash
python3 firebase/photo-removal/fixtures.py --mode preflight --state /tmp/fluxit-fb704-fixtures.json > /tmp/fluxit-fb704-fixture-preflight.log 2>&1
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -Pfluxit.parity.enabled=true -Pfluxit.firebase.emulator.enabled=true > /tmp/fluxit-fb704-emulator-apk.log 2>&1
env 'ORG_GRADLE_PROJECT_fluxit.parity.enabled=true' 'ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true' xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb704-emulator-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' 'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb704-emulator-xcode.log 2>&1
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
export FIREBASE_STORAGE_EMULATOR_HOST=127.0.0.1:9199
python3 firebase/room-removal/native.py --app /tmp/fluxit-fb704-emulator-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device 1B6EFEA8-AF39-4C81-A8AE-2132050AD13C --adb /Users/franzueto/Library/Android/sdk/platform-tools/adb > /tmp/fluxit-fb704-native.log 2>&1
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w -r -e class com.fluxit.data.PhotoStorageEmulatorIntegrationTest,com.fluxit.data.PhotoAvailabilityEmulatorIntegrationTest,com.fluxit.ui.components.ImageDecoderInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner > /tmp/fluxit-fb704-android-photos.log 2>&1
python3 firebase/photo-removal/ios-photos.py --app /tmp/fluxit-fb704-emulator-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device 1B6EFEA8-AF39-4C81-A8AE-2132050AD13C > /tmp/fluxit-fb704-ios-photos.log 2>&1
```

The actual native commands prefixed Python with the three explicit loopback environment
variables, equivalent to the exports. Results:16 contract checkpoints/platform,
bidirectional realtime/photos, offline pending mutations/recovery; runner-owned8 documents/
1 account deleted and all owned photos/recovery photos0. Targeted Android12 tests include
real resize/upload/download/delete, denial, fresh-client availability, interruption/retry
and two byte decoder tests. Apple43 assertions are Storage22, interruption12, cross-process
publish5/subscribe4. This is not a literal reinstall or manual picker gesture claim.

## Exact cleanup, failure disclosure and restore

The first cleanup and an initial resume failed; their logs remain:
`/tmp/fluxit-fb704-fixture-cleanup.log` and `...-cleanup-final.log` (both exit1).
The helper supplied HTTP method as urllib Request's fourth positional argument, which is
`origin_req_host`; intended Firestore DELETEs became GETs. Auth POST deletions occurred,
but three real photo-fixture lists remained. No failed attempt is counted as PASS.

Both apps were quiesced (Android force-stop; the Apple runner terminates each actual bundle,
subsequent correct-bundle termination confirmed "found nothing to terminate"). Before
resumed deletes, exact remaining document names matched the known iOS fixture literals
`FB-306 combined replace check`, `FB-307 interrupted replace check`,
`FB-307 cross-device check`; schemaVersion1, total/completed0 and no item descendants.
Their owner provenance had been validated against allowlisted synthetic account emails
before the first writes. Initial namespace hash/preflight and exact paths were retained
only in `/tmp/fluxit-fb704-residual-manifest.json` and `/tmp/fluxit-fb704-fixtures.json`.
No identity/path manifest is committed. The corrected helper now persists its trusted
owner/path plan before writes and uses `method=method` explicitly.

```bash
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am force-stop com.fluxit
python3 firebase/photo-removal/test_fixtures.py > /tmp/fluxit-fb704-fixture-tests.log 2>&1
python3 firebase/photo-removal/fixtures.py --mode cleanup --state /tmp/fluxit-fb704-fixtures.json > /tmp/fluxit-fb704-fixture-cleanup-verified.log 2>&1
```

Four mocked tests verify DELETE/POST/GET reach transport correctly and non-loopback routes
are rejected before transport. Final exact3-document cleanup exit0, actual user roots0,
all live descendant lists0/items0, accounts0/photos0, missing ancestor references0.
The final proof checks actual parent existence and collection-group queries separately
from missing-parent reference enumeration. Earlier account/photo deletion counts were not
retained and are not invented. No global reset, cloud endpoint or broad deletion was used.
Stop only the task-owned emulator CLI normally with Ctrl-C; this completed exit0.

```bash
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest :composeApp:dependencies > /tmp/fluxit-fb704-restore-gradle.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb704-default-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb704-restore-xcode.log 2>&1
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk > /tmp/fluxit-fb704-restore-installs.log 2>&1
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk >> /tmp/fluxit-fb704-restore-installs.log 2>&1
xcrun simctl install 1B6EFEA8-AF39-4C81-A8AE-2132050AD13C /tmp/fluxit-fb704-default-ios/Build/Products/Debug-iphonesimulator/FluxIt.app >> /tmp/fluxit-fb704-restore-installs.log 2>&1
python3 firebase/photo-removal/verify.py --dependencies /tmp/fluxit-fb704-restore-gradle.log --debug-app /tmp/fluxit-fb704-default-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --release-app /tmp/fluxit-fb704-release-ios/Build/Products/Release-iphonesimulator/FluxIt.app --native /tmp/fluxit-fb704-native.log --android /tmp/fluxit-fb704-android-photos.log --apple /tmp/fluxit-fb704-ios-photos.log --cleanup /tmp/fluxit-fb704-fixture-cleanup-verified.log --output firebase/photo-removal/packages.json > /tmp/fluxit-fb704-verification.log 2>&1
git diff --check
```

Ordinary source/APK/Apple binary/framework inspection rejects retired photo-path symbols,
Room/bundled SQLite and diagnostic fixtures, validates fresh photo/cleanup reports and
final emulator=false. Native Firebase persistence/system APIs and byte decoders remain.
Source fingerprint uses sorted `path + NUL + hash + newline`; reproduce with
`python3 firebase/photo-removal/fingerprint.py --check`. Evidence/prose/ignored config/raw
logs/local manifests are excluded. Repository search covers current Kotlin product/test
sources; historical documents/runners remain frozen, and broader README updates are FB-705.
