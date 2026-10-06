# FB-708 final verification procedure (committed state)

Developer-delegated evidence run for the FB-708 gate (Orchestrator/gate, development-only closure under DEC-012). It re-executes the [FB-706 procedure](FB-706-PROCEDURE.md) tiers plus the [FB-710 regression tests](FB-710-PROCEDURE.md) on the **committed** state, per FB-707-NB2 (the FB-706 matrix predates the FB-710 `SwipeToDelete.kt` change; the FB-710 matrix ran on an uncommitted worktree). No product, Rules, backend, configuration, script or historical-evidence file was changed. No commit, no status change, no self-approval.

Target state: branch `epic/firebase`, HEAD `2c3bd88d0766a524c761e77571e2aa3a6176a762`. `git status --short` at start and at end: only ` M FIREBASE_MIGRATION_STATUS.md` (orchestrator-owned, untouched). Date 2026-10-06 (UTC 14:43-15:06). Targets: Android AVD `emulator-5554`, iOS simulator iPhone 17 `1B6EFEA8-AF39-4C81-A8AE-2132050AD13C` (iOS 27.0 runtime), Xcode/macOS host.

## Product/test file hashes at start (sha256)

```text
composeApp/src/commonMain/kotlin/com/fluxit/ui/components/SwipeToDelete.kt                                     eacb0a2aa1b4ce590186fa96795889356cc85640314205ab2fe59cb3a35ec238
composeApp/build.gradle.kts                                                                                       db28d15a71e189b202ca126d4bcfaa856bb9ebf7ba2f4fe0fc7d817a41a084af
composeApp/src/androidInstrumentedTest/kotlin/com/fluxit/feature/swipeundo/SwipeUndoScreensInstrumentedTest.kt   c40d223f253313c379f0497165233d609ca662fcae06e69d59b3f42099113e8f
composeApp/src/androidInstrumentedTest/kotlin/com/fluxit/ui/components/SwipeToDeleteContainerInstrumentedTest.kt a59f2d1e07a706f81c0b6993dc0459dc886fd4fa87aaead490b41177f2f23f0f
composeApp/src/iosTest/kotlin/com/fluxit/ui/components/SwipeToDeleteContainerIosTest.kt                           c8cb3865d6a7b88f7e88bd9353ce5f1cd75a1affe8be150db283d42a0877d6e7
```

These equal the hashes recorded in FB-710-RESULTS (so the committed bytes equal the previously verified worktree bytes for these files).

## Host paths and two environment notes

```sh
JDK=/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home
ADB=/Users/franzueto/Library/Android/sdk/platform-tools/adb
SIM=1B6EFEA8-AF39-4C81-A8AE-2132050AD13C
SPM=/Users/franzueto/Library/Developer/Xcode/DerivedData/iosApp-hbawgxixifennwfnlhinwgjuddqj/SourcePackages
NODE=<scratch dir containing only a `node` symlink to Node v22.23.3>
```

1. The FB-706 Node 22 path `/tmp/fluxit-node22/node_modules/node/bin/node` had been removed by host `/tmp` cleanup (the package's nested `node-bin-darwin-arm64/bin/node`, v22.23.3, survived). Rules/security/backend tiers used a scratch `NODE` directory with a symlink to that binary. Before the native lease runner (which hard-codes the old path in its child PATH) the symlink was re-created at the original `/tmp` location. No repository file changed.
2. `firebase/final-verification/isolated_native.py` hard-codes `OK (90 tests)`. FB-710 added 10 androidInstrumentedTest cases, so the strict count is now 100. Rather than edit the committed script, the native tier ran an unmodified-otherwise copy (`isolated_native.py` with only the two strings `OK (90 tests)` / `instrumentation=90` changed to 100) from a scratch mirror of the repository (symlinks to every top-level path; the `firebase/final-verification` scripts copied). The committed script is unchanged (sha256 prefix `e74721b630ae91da`; patched copy `440030fe5936d268`). Follow-up: bump the strict count in the committed script if it is ever reused.

## Tiers executed, in order (all sequential)

1. Fresh shared/Android/iOS matrix (`--rerun-tasks`):

```sh
env JAVA_HOME="$JDK" ./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64 --rerun-tasks
env JAVA_HOME="$JDK" ./gradlew :composeApp:dependencies
```

JUnit counts were summed from every `TEST-*.xml` in `composeApp/build/test-results/{testDebugUnitTest,testReleaseUnitTest,iosSimulatorArm64Test}` (tests/failures/errors/skipped).

2. Rules/config/storage/query: from `firebase`, `env PATH="$NODE:$PATH" JAVA_HOME="$JDK" XDG_CONFIG_HOME=/tmp/fluxit-fb708-rules-config npm test`.
3. Security emulator matrix and fail-closed safety (sequential, same ignored manifest path), from repository root:

```sh
env PATH="$NODE:$PATH" JAVA_HOME="$JDK" XDG_CONFIG_HOME=/tmp/fluxit-fb708-security-config firebase/node_modules/.bin/firebase --config firebase.json --project demo-fluxit emulators:exec --only auth,firestore,storage "$NODE/node firebase/final-verification/security_matrix.js"
env PATH="$NODE:$PATH" node --test firebase/security/safety.test.js
```

4. Backend, from `functions`: `npm run check`, then `npm run test:emulator` with `XDG_CONFIG_HOME=/tmp/fluxit-fb708-functions-config`.
5. Native lease tests: `python3 -m unittest discover -s firebase/final-verification -p 'test_*.py'`.
6. Opt-in native fixtures and PLAN-011 owned-ephemeral-lease native tier (commands as in FB-706 with `/tmp/fluxit-fb708-native-ios`): Gradle `assembleDebug assembleDebugAndroidTest -Pfluxit.parity.enabled=true -Pfluxit.firebase.emulator.enabled=true`; Xcode Debug build with both `ORG_GRADLE_PROJECT_*` flags and `-D FLUXIT_PARITY`; then `isolated_native.py` (copy, see note 2). Before any fixture write the wrapper refused existing listeners on 8080/9099/9199/4400/4500/9150 and verified actual roots/lists/items/cleanup jobs/accounts/photos zero in both namespaces; the Auth/Firestore/Storage process group was fresh, local, loopback-only, unexported and task-owned, and was the only process disposed.
7. Ordinary builds, restoration to default flags, FB-710 regression and package checks:

```sh
env JAVA_HOME="$JDK" ./gradlew :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:assembleDebugAndroidTest :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64 --rerun-tasks
"$ADB" -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
"$ADB" -s emulator-5554 install -r -t composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
"$ADB" -s emulator-5554 shell am instrument -w -r -e class com.fluxit.di.RepositoryDiInstrumentedTest,com.fluxit.firebase.auth.AndroidAuthDiInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner
"$ADB" -s emulator-5554 shell am instrument -w -r -e class com.fluxit.ui.components.SwipeToDeleteContainerInstrumentedTest,com.fluxit.feature.swipeundo.SwipeUndoScreensInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner
```

Then three Xcode Debug/Release builds into `/tmp/fluxit-fb708-default-graph-ios` (parity flag only, `-D FLUXIT_PARITY`), `/tmp/fluxit-fb708-ordinary-ios-debug` and `/tmp/fluxit-fb708-ordinary-ios-release` (no parity/emulator flags, `CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=`), `python3 firebase/room-removal/default-ios.py`, `python3 firebase/final-verification/packages.py --dependencies ... --debug-app ... --release-app ... --output ...`, and `python3 firebase/final-verification/restore.py --adb "$ADB" --device "$SIM" --app <ordinary debug .app> --output ...`.

8. `git diff --check`; confirmation of generated `ENABLED=false` / `USE_FIREBASE_REPOSITORIES=true`; no listeners on the six task ports; no Firebase emulator process left.

## Tiers deliberately not executed

- `firebase/security/preflight.js` and `run.js --development --execute` (the development-cloud client-security tier of FB-706): they write exact-scoped synthetic fixtures to the real development Firebase project and need credentials; the delegation covered emulator tiers only.
- `firebase/final-verification/verify.py` (FB-706 evidence replay): it asserts the FB-706 changed-file ownership inventory and is not applicable to a clean committed tree.
- FB-709 native lifecycle fixture (retained reviewed evidence at the unchanged product commit), iOS live swipe/Undo gestures, literal pickers/reinstall/radio, physical iOS (DEC-004), any deployment/production work (DEC-012).
