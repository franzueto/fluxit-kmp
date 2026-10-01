# FB-706 final verification procedure

Developer-only verification of reviewed product commit `9281019e8e22fc98ee2d4a0ebd75760dc6c438d6`; no self-approval, canonical transition, deployment or production provisioning. Historical runners/reports remain frozen. PLAN-011 permits only the new local native runners' freshly owned empty unexported emulator lease and honest process disposal. PLAN-012 leaves literal human checks to FB-707 / MAN-008; see [the checklist](FB-706-MANUAL.md).

Paths used on this host:

```sh
JDK=/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home
NODE=/tmp/fluxit-node22/node_modules/node/bin
ADB=/Users/franzueto/Library/Android/sdk/platform-tools/adb
SIM=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C
SPM=/Users/franzueto/Library/Developer/Xcode/DerivedData/iosApp-hbawgxixifennwfnlhinwgjuddqj/SourcePackages
```

Run each local emulator suite sequentially; security safety tests also must run sequentially with the security runner because both use the same ignored manifest path. Raw logs/manifests stay local and may contain synthetic identities or credentials. Committed evidence contains hashes/counts only.

## Fresh shared and ordinary Android/framework matrix

```sh
env JAVA_HOME="$JDK" ./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64 --rerun-tasks
env JAVA_HOME="$JDK" ./gradlew :composeApp:dependencies
```

JUnit counts come from every `TEST-*.xml` in the three named test-results directories, summing tests/failures/errors/skips rather than console inference.

## Rules, security and backend

From `firebase`, `env PATH="$NODE:$PATH" JAVA_HOME="$JDK" XDG_CONFIG_HOME=/tmp/fluxit-fb706-rules-config npm test` runs config4 + Rules/storage/query60.

From repository root:

```sh
env PATH="$NODE:$PATH" JAVA_HOME="$JDK" XDG_CONFIG_HOME=/tmp/fluxit-fb706-security-config firebase/node_modules/.bin/firebase --config firebase.json --project demo-fluxit emulators:exec --only auth,firestore,storage "$NODE/node firebase/final-verification/security_matrix.js"
env PATH="$NODE:$PATH" node --test firebase/security/safety.test.js
env -u FIRESTORE_EMULATOR_HOST -u FIREBASE_AUTH_EMULATOR_HOST -u FIREBASE_STORAGE_EMULATOR_HOST -u STORAGE_EMULATOR_HOST PATH="$NODE:$PATH" node firebase/security/preflight.js
env -u FIRESTORE_EMULATOR_HOST -u FIREBASE_AUTH_EMULATOR_HOST -u FIREBASE_STORAGE_EMULATOR_HOST -u STORAGE_EMULATOR_HOST PATH="$NODE:$PATH" node firebase/security/run.js --development --execute
```

The existing reviewed development runner predeclares exact collision-checked synthetic owners, document paths and photo intents before mutations, refuses drift, uses no global data query/delete, and verifies exact scoped cleanup. Its local ignored manifest must be absent afterward. No deploy or native cloud tier is implied.

From `functions`:

```sh
env PATH="$NODE:$PATH" npm run check
env PATH="$NODE:$PATH" JAVA_HOME="$JDK" XDG_CONFIG_HOME=/tmp/fluxit-fb706-functions-config npm run test:emulator
```

## Local native matrix

Build opt-in fixtures without changing ordinary defaults:

```sh
env JAVA_HOME="$JDK" ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -Pfluxit.parity.enabled=true -Pfluxit.firebase.emulator.enabled=true
env JAVA_HOME="$JDK" ORG_GRADLE_PROJECT_fluxit.parity.enabled=true ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath /tmp/fluxit-fb706-native-ios -clonedSourcePackagesDirPath "$SPM" -destination "platform=iOS Simulator,id=$SIM" 'OTHER_SWIFT_FLAGS=$(inherited) -D FLUXIT_PARITY' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build
env PYTHONPYCACHEPREFIX=/tmp/fluxit-fb706-pycache python3 -m unittest discover -s firebase/final-verification -p 'test_*.py'
env PYTHONPYCACHEPREFIX=/tmp/fluxit-fb706-pycache python3 firebase/final-verification/isolated_native.py --app /tmp/fluxit-fb706-native-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --adb "$ADB" --device "$SIM"
```

The wrapper refuses any existing listener on ports 8080/9099/9199/4400/4500/9150. It spawns its own new process group with a private local config, no functions/import/export, and loopback-only Auth/Firestore/Storage. Before SDK fixture writes it persists task/process/group/endpoints/projects/buckets/suites and exact source/app hashes; actual configured and demo roots, collection-group lists/items/cleanup jobs, accounts and photos must all be zero. The new child wrappers require the armed lease and native emulator gates. All post-suite actual residue is captured locally before terminating only the owned process group and verifying every listed port closed. No inferred-owner, prefix or global deletion/reset is used. Residue need not be zero; disappearance through instance disposal is explicitly not per-path cleanup.

Within that lease the full Android command is:

```sh
"$ADB" -s emulator-5554 shell am instrument -w -r -e notClass com.fluxit.parity.FirebaseRegressionInstrumentedTest,com.fluxit.parity.SessionCleanupInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner
```

It must report exactly `OK (90 tests)` and no failures/crash. Apple launches the 11 existing gated Auth/restoration/list/item/cross-client/photo/interruption/publish/subscribe/dashboard checks through `ios_checks.py`. `native_contract.py` executes the existing actual Koin 16-checkpoint scenario on Android and Apple with the same synthetic account, bidirectional edits/photo bytes and native offline recovery. Neither child owns emulator shutdown or data deletion.

## Default-routing graph checks and final ordinary builds

After the owned native instance is disposed:

```sh
env JAVA_HOME="$JDK" ./gradlew :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:assembleDebugAndroidTest :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64
"$ADB" -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
"$ADB" -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
"$ADB" -s emulator-5554 shell am instrument -w -r -e class com.fluxit.di.RepositoryDiInstrumentedTest,com.fluxit.firebase.auth.AndroidAuthDiInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner
```

Build `/tmp/fluxit-fb706-default-graph-ios` with the same Xcode Debug arguments as above, only `ORG_GRADLE_PROJECT_fluxit.parity.enabled=true`, and no emulator flag. Then:

```sh
python3 firebase/room-removal/default-ios.py --app /tmp/fluxit-fb706-default-graph-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device "$SIM"
```

The unchanged historical graph probe is read-only and launches its empty UI: it resolves the actual default graph without authentication/repository I/O, checks Firebase wrapped bindings/Room absence and proves the lazy raw Auth factory stays uncreated. Its old FB-703 log tag is not a new historic report modification.

Build ordinary Xcode Debug and Release separately with no parity/emulator environment variables or `OTHER_SWIFT_FLAGS`, using the same project/scheme/device/SPM/signing arguments, derived paths `/tmp/fluxit-fb706-ordinary-ios-debug` and `/tmp/fluxit-fb706-ordinary-ios-release`.

```sh
python3 firebase/final-verification/packages.py --dependencies /tmp/fluxit-fb706-dependencies.log --debug-app /tmp/fluxit-fb706-ordinary-ios-debug/Build/Products/Debug-iphonesimulator/FluxIt.app --release-app /tmp/fluxit-fb706-ordinary-ios-release/Build/Products/Release-iphonesimulator/FluxIt.app --output /tmp/fluxit-fb706-packages.json
```

The scanner checks all four dependency configurations, Kotlin/build sources, Android APK DEX/native entries, Apple app symbols/files/system SQLite linkage, both framework headers/symbols, new and historic parity diagnostics, retired file-photo paths and generated emulator=false/Firebase-repositories=true. Official Firebase/system persistence remains.

Restore ordinary Android Debug and iOS Debug installs, remove the task instrumentation APK, compare installed binary hashes against built ordinary binaries, verify generated flags and task ports closed. Installing/restoring artifacts does not claim literal human fresh-install/session/picker gestures. Final source/evidence replay:

```sh
python3 firebase/final-verification/verify.py --local --selftest
git diff --check
```

Reviewed FB-709 native lifecycle proof is retained at the unchanged product commit, not represented as freshly re-executed FB-706. The final evidence file distinguishes executed tiers, retained receipts, failed attempts, actual residue/process disposal, exact cloud cleanup and every unrun/manual bound.
