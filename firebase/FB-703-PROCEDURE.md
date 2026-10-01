# FB-703 Room removal verification procedure

Source cleanup against the existing development configuration (DEC-011). No deployment,
production provisioning, account/data migration, or manual PASS. Immediate source rollback:
`5e33b5b51e9a0cad8b7baa0ef80aea61bfe08bed` (reviewed FB-702 Firebase bindings with Room
still present). Restore sources in an isolated checkout, preserving shared user edits and
ignored mobile configurations; do not reset the shared checkout or erase installed data.

PLAN-009 follows P7's explicit Room **and bundled SQLite** scope: remove AndroidX
Room/SQLite and their packaged libraries, retaining native Firebase persistence. Android
Firestore still uses the system `android.database.sqlite` API. Apple native Firestore
retains its required persistence (including LevelDB); the inspected app binaries had no
direct `libsqlite3.dylib` linkage. This task makes no blanket claim that Firebase or OS
storage APIs are absent.

Executed from repository root on 2026-10-01 UTC. JDK is the existing Zulu 23; Android
Studio's JDK 25 is incompatible with the current Gradle setup. Native commands require
normal local cache/simulator access outside the filesystem sandbox. All successful
build commands below ran sequentially.

## Clean builds and tests

```bash
export JAVA_HOME=/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home
./gradlew clean :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:assembleDebugAndroidTest :composeApp:linkReleaseFrameworkIosSimulatorArm64 --rerun-tasks > /tmp/fluxit-fb703-clean-gradle.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb703-default-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb703-default-debug-xcode.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -derivedDataPath /tmp/fluxit-fb703-release-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb703-default-release-xcode.log 2>&1
./gradlew :composeApp:dependencies > /tmp/fluxit-fb703-dependencies.log 2>&1
```

Each actual build invocation prefixed the displayed command with
`env JAVA_HOME='/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home'`;
the export above is its replay equivalent. Fresh derived-data directories were used for
ordinary Debug/Release and the diagnostic target. Both simulator configurations use
ad-hoc signing. `iosArm64CompileKlibraries` is resolved/inspected, but no physical-device
build/run is claimed (permanent MAN-003 waiver).

## Actual platform startup graphs and fixture compilation

```bash
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w -r -e class com.fluxit.di.RepositoryDiInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner > /tmp/fluxit-fb703-default-android-di.log 2>&1
./gradlew :composeApp:assembleDebugAndroidTest -Pfluxit.parity.enabled=true > /tmp/fluxit-fb703-regression-compile.log 2>&1
env 'ORG_GRADLE_PROJECT_fluxit.parity.enabled=true' xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb703-probe-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' 'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb703-probe-xcode.log 2>&1
python3 firebase/room-removal/default-ios.py --app /tmp/fluxit-fb703-probe-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device 1B6EFEA8-AF39-4C81-A8AE-2132050AD13C > /tmp/fluxit-fb703-default-ios-di.log 2>&1
xcrun simctl install 1B6EFEA8-AF39-4C81-A8AE-2132050AD13C /tmp/fluxit-fb703-default-ios/Build/Products/Debug-iphonesimulator/FluxIt.app
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb703-default-ios -destination 'platform=iOS Simulator,id=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb703-restore-default-xcode.log 2>&1
./gradlew :composeApp:assembleDebugAndroidTest > /tmp/fluxit-fb703-restore-default-gradle.log 2>&1
/Users/franzueto/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk > /tmp/fluxit-fb703-restore-test-install.log 2>&1
```

Android checks the ordinary running app's GlobalContext. iOS uses the exact
`initializeIosKoin()` startup helper in an opt-in diagnostic app with an empty SwiftUI
route: no auth/session UI composition or repository I/O. It checks Firebase bindings,
absence of a retired database definition, uncreated Auth repository factory and ordinary
Firestore host/SSL routing. Ordinary binaries/frameworks separately exclude diagnostics.

## Package/dependency and source evidence

```bash
python3 firebase/room-removal/verify.py --dependencies /tmp/fluxit-fb703-dependencies.log --debug-app /tmp/fluxit-fb703-default-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --release-app /tmp/fluxit-fb703-release-ios/Build/Products/Release-iphonesimulator/FluxIt.app --output firebase/room-removal/packages.json > /tmp/fluxit-fb703-verification.log 2>&1
git diff --check
```

The verifier fails closed for missing/incomplete build reports, any Room/AndroidX SQLite/
KSP dependency, retired Kotlin imports/build wiring/schema/generated KSP, Room/AndroidX
SQLite in every ordinary APK DEX or Apple binary/framework symbol table, any APK-native
SQLite entry and any app-bundled SQLite file. It verifies native Firebase presence and
ordinary flags. Exact package/dependency hashes are in `room-removal/packages.json`.

Three negative executions used `/tmp/fluxit-fb703-negative-{room,sqlite,incomplete}.log`:
the real report plus `androidx.room:room-runtime:2.7.2`, plus
`androidx.sqlite:sqlite-bundled:2.5.2`, or with `BUILD SUCCESSFUL` replaced by
`BUILD INCOMPLETE`. Each verifier invocation used the same ordinary app paths and
`--output /tmp/fluxit-fb703-negative-output.json`; each returned 1 and created no output.
Python files also passed in-memory `compile(path.read_text(), str(path), 'exec')`.

`room-removal/evidence.json` lists source hashes, explicit deleted-path markers, changed
paths, JUnit counts, build/report hashes and bounds. Its combined source hash uses sorted
`path + NUL + hash-or-DELETED + newline` UTF-8 bytes. Tracker, prose, generated files,
local logs, package/evidence outputs and ignored mobile config contents are excluded.
Historical FB-701/FB-702 evidence remains frozen and resolves against its recorded Git
snapshot, not the current intentionally changed sources.

The converted opt-in Firebase regression fixtures retain 16 fixed expected contract
checkpoints, realtime, offline and cloud photo tests without a Room oracle. Their
compilation is fresh here; their **live** execution is unrun in FB-703. The new
`room-removal/native.py` runner uses the renamed fixtures/launch hook and strict loopback
emulators only, for the next task's photo regressions. Never use the historical
`parity/native.py` runner against the post-removal sources.
