# FB-702 source-binding cutover procedure

Development configuration only (DEC-011). No deployment or production provisioning.
Immediate rollback: `a3b90bc2a6f38336e35d44a515fb424ecc026df0` (reviewed FB-701,
ordinary Room bindings). Earlier pre-P7 rollback:
`462bac331698f0a10beaa253919a510ce30c54ab`.

Both actual platform modules bind Firebase directly. The retired
`fluxit.firebase.repositories.enabled` property is ignored, including explicit false.
Generated `FirebaseDevFlags.USE_FIREBASE_REPOSITORIES=true` is compatibility metadata,
not a selector. Emulator routing remains independently false by default. Room's lazy
database definition, types, dependencies and schemas remain for FB-703; no package
removal is claimed. Local photo cleanup belongs FB-704.

`IosAuthIntegrationCheck` now lives in `firebaseParityIos` and requires
`fluxit.parity.enabled=true`; its Swift hooks also require `FLUXIT_PARITY`. Auth
emulator and explicit launch gates remain. SessionTrace and its production calls,
including raw-UID formatting, are removed. Lifecycle behavior remains covered by tests.

Run from repository root with local Node 22, Android SDK, Xcode and the existing AVD
and iOS simulator. The commands below use local device identification, not cloud IDs.

```bash
set -euo pipefail
export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
fluxit_fb702_device=$(xcrun simctl list devices booted -j | node -e 'let s="";process.stdin.on("data",d=>s+=d);process.stdin.on("end",()=>{const d=Object.values(JSON.parse(s).devices).flat().filter(x=>x.state==="Booted");if(d.length!==1)throw Error("One booted simulator required");process.stdout.write(d[0].udid)})')
fluxit_fb702_adb="$ANDROID_HOME/platform-tools/adb"
./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:assembleDebugAndroidTest -Pfluxit.firebase.repositories.enabled=false > /tmp/fluxit-fb702-default-gradle.log 2>&1
"$fluxit_fb702_adb" -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
"$fluxit_fb702_adb" -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
"$fluxit_fb702_adb" -s emulator-5554 shell am instrument -w -r -e class com.fluxit.di.RepositoryDiInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner > /tmp/fluxit-fb702-default-android-di.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb702-default-ios -destination "platform=iOS Simulator,id=$fluxit_fb702_device" CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb702-default-debug-xcode.log 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -derivedDataPath /tmp/fluxit-fb702-release-ios -destination "platform=iOS Simulator,id=$fluxit_fb702_device" CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb702-default-release-xcode.log 2>&1
```

The iOS diagnostic binary compiles an opt-in probe with ordinary default routing.
It calls the exact startup helper used by MainViewController, resolves the actual
Koin graph, and asserts Room/Auth repository factories remain uncreated. Swift checks
SSL and the default Firestore host. The probe's SwiftUI route renders an empty view;
it never mounts Compose/auth UI, restores auth or calls a repository operation.
This is runtime proof in a diagnostic binary, distinct from ordinary-binary exclusion.

```bash
env 'ORG_GRADLE_PROJECT_fluxit.parity.enabled=true' 'ORG_GRADLE_PROJECT_fluxit.firebase.repositories.enabled=false' xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb702-probe-ios -destination "platform=iOS Simulator,id=$fluxit_fb702_device" 'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb702-probe-xcode.log 2>&1
python3 firebase/cutover/default-ios.py --app /tmp/fluxit-fb702-probe-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device "$fluxit_fb702_device" > /tmp/fluxit-fb702-default-ios-di.log 2>&1
```

Start task-owned local emulators separately using the ignored mobile project config;
keep raw logs local. This does not deploy or contact the configured cloud project.

```bash
fluxit_fb702_project=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync("composeApp/google-services.json")).project_info.project_id)')
env XDG_CONFIG_HOME=/tmp/fluxit-fb702-emulator-config firebase/node_modules/.bin/firebase --config firebase.json --project "$fluxit_fb702_project" emulators:start --only auth,firestore,storage > /tmp/fluxit-fb702-emulators.log 2>&1
```

After emulators are ready, build and run the existing strict-loopback native runners:

```bash
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -Pfluxit.parity.enabled=true -Pfluxit.firebase.emulator.enabled=true -Pfluxit.firebase.repositories.enabled=false > /tmp/fluxit-fb702-parity-apk.log 2>&1
env 'ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true' 'ORG_GRADLE_PROJECT_fluxit.parity.enabled=true' 'ORG_GRADLE_PROJECT_fluxit.firebase.repositories.enabled=false' xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb702-probe-ios -destination "platform=iOS Simulator,id=$fluxit_fb702_device" 'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build > /tmp/fluxit-fb702-parity-xcode.log 2>&1
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
export FIREBASE_STORAGE_EMULATOR_HOST=127.0.0.1:9199
python3 firebase/parity/native.py --app /tmp/fluxit-fb702-probe-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device "$fluxit_fb702_device" --adb "$fluxit_fb702_adb" > /tmp/fluxit-fb702-native.log 2>&1
python3 firebase/parity/ios-checks.py --app /tmp/fluxit-fb702-probe-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device "$fluxit_fb702_device" > /tmp/fluxit-fb702-ios-checks.log 2>&1
"$fluxit_fb702_adb" -s emulator-5554 shell am instrument -w -r -e notClass com.fluxit.parity.FirebaseRoomParityInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner > /tmp/fluxit-fb702-android-full.log 2>&1
```

Stop only the task-owned emulator CLI normally after checks; in-memory legacy fixtures
are discarded. The native runner separately verifies scoped account/document/photo
teardown. Restore default APK/test APK and ordinary iOS app using the commands above,
rebuild ordinary Debug Xcode to restore the generated default framework/header, then:

```bash
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest > /tmp/fluxit-fb702-restore-default.log 2>&1
python3 firebase/cutover/verify.py --debug-app /tmp/fluxit-fb702-default-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --release-app /tmp/fluxit-fb702-release-ios/Build/Products/Release-iphonesimulator/FluxIt.app > /tmp/fluxit-fb702-verification.log 2>&1
```

For source rollback, preserve the current checkout/user edits and ignored configs.
Create an isolated checkout at the full reviewed SHA rather than resetting the shared
working tree: `git worktree add --detach /tmp/fluxit-fb702-rollback a3b90bc2a6f38336e35d44a515fb424ecc026df0`.
Place the existing ignored mobile configs locally in that checkout under the established
policy, then run ordinary builds there; do not add configs to Git, export cloud data,
erase installed data or apply an automatic reverse patch over unrelated edits.
This restores source bindings to Room; it does not undo any cloud writes or deployment.
