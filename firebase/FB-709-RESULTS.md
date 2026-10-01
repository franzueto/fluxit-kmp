# FB-709 developer results

Developer verification completed on 2026-10-01 against `epic/firebase`, baseline `351d3c0195564d615c5f225b468785b13cba7911`, with an uncommitted task diff. This is implementation evidence for independent review, not self-approval or a canonical status transition. DEC-012 keeps closure development-only; production provisioning is waived. Historical FB-705 and prior migration evidence remain unchanged.

## Implemented behavior

Production Koin Auth/List/Item/Photo bindings now share an SDK-neutral session work boundary. Sign-out closes the gate and screen/navigation stores, cancels and joins scoped jobs/listeners, persists an identity-free pending marker, waits for terminal Storage transfers, clears stopped Firestore persistence, recreates/reconfigures the native client, and removes Auth credentials before success. Failures remain blocked and retryable; interrupted credential changes cannot reopen from delayed raw Auth callbacks. Existing repository singletons resolve recreated handles on both platforms.

Cold recovery clears an unstarted SDK handle before termination to avoid briefly starting a client with persisted queued writes. Only the exact SDK failed-precondition error permits started-client terminate+clear fallback. Ordinary offline persistence remains enabled. Cleanup has its own 15-second budget, which takes priority over the gate's normal 10-second network-restoration timeout. Marker-write failure is reported honestly; no cross-process durability is claimed when that write fails.

Android explicitly tracks upload and official stream-download tasks; the previous download output ceiling remains 5 MiB and oversized input reads at most ceiling+1. Streams close on all read outcomes. Swift tracks upload/getData tasks through completion, including creation/completion/cancellation races. Neither platform reports cleanup success while a tracked transfer remains nonterminal.

Storage byte paths do not own a persistent photo file/HTTP cache: shipped Android network requests set `useCaches(false)`; shipped Apple Storage uses an unconfigured GTMSessionFetcher service, which creates ephemeral sessions, and `getData` has no destination file URL. Exact shipped-source hashes are in evidence. This is logical SDK deletion and task/byte release, without secure disk overwrite, forensic erasure, or a guarantee about OS/SDK transient memory internals. Already-uploaded orphan cloud bytes retain the approved 30-day backend policy; sign-out does not delete cloud objects.

## Final verification

| Tier | Result | Evidence |
|---|---|---|
| Ordinary Android Debug unit | 250 tests, 0 failures/errors/skips | Fresh rerun; includes 16 new lifecycle tests and 4 Android bounded-reader tests |
| Ordinary Android Release unit | 250 tests, 0 failures/errors/skips | Same fresh source and checks |
| iOS simulator unit | 311 tests, 0 failures/errors/skips | Includes existing real iOS transform/decoder tests plus 16 lifecycle tests |
| Android image decoder instrumentation | 2/2 passed | Actual AVD PNG decode and corrupt/empty rejection |
| Ownership cleanup guards | 8/8 passed | Exact UID/email normalization, namespace, extra-account and unlisted-path refusal |
| Android native lifecycle | 3 formal JUnit phase executions, 1 test each, passed | Normal / persisted restart prepare / fresh OS-process recover |
| Apple native lifecycle | 3 console phase checks passed | Scripted simulator checks, not XCTest or manual gestures |
| Native Storage cancellation | Live uploads and downloads terminal-canceled on both platforms | Android active-task cleanup timeout remains failure until terminal cancellation and successful retry; Apple completion callbacks precede cleanup success |
| Cache/pending-write isolation | A→B and same-user cache absence; queued list/item writes discarded | Actual Koin-wrapped repositories and actual native SDK clients; handle/settings identity checks |
| Real process restart | Both platforms force-stopped/terminated and relaunched | Persisted pending marker/queue removed before credential validation; independent server baseline never overwritten by the two queued restart writes |
| Ordinary build/package | Debug/Release Android APKs, Kotlin simulator frameworks and Xcode simulator apps passed | New/previous parity diagnostics and Room absent; native Firestore/system persistence retained; generated emulator gate false |
| Restoration | Ordinary Android/iOS Debug installed and binary hashes matched | Task instrumentation APK removed; only task-owned Auth/Firestore/Storage emulators stopped |

Final native run log: `/tmp/fluxit-fb709-native-final3.log`; local diagnostic directory: `/var/folders/2q/1rw05q3s6ds294x7k6sd9ng40000gn/T/fluxit-fb709-native-0b6i453b`. The runner persisted its exact manifest before writes and captured actual observations before deletes. Scoped cleanup removed 12 declared documents and 4 declared accounts; photos were already removed by successful photo operations. Actual configured namespace and `demo-fluxit` roots/lists/items plus accounts/photos all ended at zero. No global reset or cloud deletion occurred. Local manifests/logs contain synthetic identities/credentials and must not be committed or shared; sanitized hashes/counts are in `session-cleanup/evidence.json`.

Key exact commands are in [the procedure](FB-709-PROCEDURE.md). Final command/log receipts include:

- `./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64 --rerun-tasks`: `/tmp/fluxit-fb709-ordinary-gradle.log`, BUILD SUCCESSFUL, 121/121 tasks executed.
- Ordinary `xcodebuild` Debug and Release using the procedure's project/scheme/device/package path and no parity/emulator flags: `/tmp/fluxit-fb709-ordinary-xcode-debug.log` and `/tmp/fluxit-fb709-ordinary-xcode-release.log`, BUILD SUCCEEDED.
- Final opt-in native build commands: `/tmp/fluxit-fb709-download-fixture-build-r3.log` and `/tmp/fluxit-fb709-download-fixture-xcode.log`; final native command is the procedure's `native.py` invocation, with the three explicit loopback environment variables.
- `adb -s emulator-5554 shell am instrument -w -r -e class com.fluxit.ui.components.ImageDecoderInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner`: `/tmp/fluxit-fb709-android-decoder.log`, OK (2 tests).
- `python3 -m unittest discover -s firebase/session-cleanup -p 'test_*.py'`: `/tmp/fluxit-fb709-ownership-tests-final.log`, 8/8.
- `python3 firebase/session-cleanup/packages.py --debug-app /tmp/fluxit-fb709-ordinary-ios-debug/Build/Products/Debug-iphonesimulator/FluxIt.app --release-app /tmp/fluxit-fb709-ordinary-ios-release/Build/Products/Release-iphonesimulator/FluxIt.app --output /tmp/fluxit-fb709-packages.json`: package scan PASS.
- Final ordinary artifact restoration: `/tmp/fluxit-fb709-ordinary-restore-gradle.log` and `/tmp/fluxit-fb709-restoration-final.log`. The first installed-package verification used an incorrect hardcoded Apple bundle ID; its failed read-only check remains in `/tmp/fluxit-fb709-restoration.log`. The final verification derives the real bundle ID from the built plist and passes.

## Retained unsuccessful attempts

All earlier raw logs, manifests and scoped cleanup records remain local and hashed. No failed native attempt is counted as a pass.

1. Native r1/r2 timed out waiting for pending item snapshots. The fixture had not primed a complete item document before applying a partial offline mutation, so the production decoder correctly omitted the incomplete cached snapshot. Full online snapshot priming and positive cache checkpoints fixed the fixture. Android r1's `Process crashed` report followed runner termination after Apple failure; its crash buffer was empty, and no independent Android product crash is inferred.
2. r1 cleanup initially refused normalized Auth emails despite exact trusted UID matches. The correction permits only normalized expected email with the same exact predeclared UID, never broad email/prefix ownership. Scoped resume verified the four known accounts/twelve known paths and zero remaining data. Negative guard tests cover foreign IDs/emails/extra accounts/namespaces/paths.
3. Native r3 passed Apple normal checks but Android's raw upload terminal wait timed out. SDK source inspection showed asynchronous cancellation during retry; production cleanup was strengthened to await terminal transfers within its budget rather than report early success.
4. r4 passed both normal phases, then restart preparation lacked a complete primed list cache and timed out. The fixture was corrected to prime the current server document before offline mutation. r5 then passed both platforms' cache and real restart phases; it is provisional earlier-source evidence.
5. The first final attempt used a 25-second fixture terminal observation window and timed out. The next combined upload/download attempt omitted explicit tracking for its raw upload: the SDK's path-keyed global task map had been overwritten by its simultaneous stream download. The corrected fixture uses the same explicit task registry as production and a bounded observation window. Final3 passed every phase with terminal upload/download cancellation. Production retry settings were never shortened.
6. Initial compile/build attempts exposed a wrong Kotlin return type/resource import, fixture generic inference/import/positional-provider compatibility issues, Swift protocol underscore names, and SDK protected generic visibility. These unsuccessful build logs are retained; final fresh builds/tests passed.

## Bounds and next owner

No new cloud/deployment/backend/security matrix, production smoke/provisioning, manual picker or real radio gesture, literal uninstall/reinstall photo check, aged-photo cloud deletion, physical iOS check, or manual accessibility/localization acceptance is inferred. Existing permanent physical iOS waiver remains; no additional manual waiver was granted.

FB-706 owns the wider final regression matrix. Existing `firebase/room-removal/native.py` and larger photo helpers allocate random owners/document/photo IDs during execution; before reusing them, add trusted exact owner/path manifests before writes (or deterministic injected fixture IDs/accounts), inventory actual roots and collection-group lists/items in both namespaces, preserve raw failed logs, and use only exact scoped cleanup. The older photo cleanup prefix-based owner admission is insufficient for this task's exact-manifest requirement. Current wrappers also require sign-in cleanup before network disable, and full cached-document priming before partial offline writes; the iOS offline fixture now awaits disable-network completion. Historical results/fingerprints remain frozen, and future final evidence needs a new owner/fingerprint.

First recommended action: independent `firebase_reviewer` review of this complete source/evidence inventory, then orchestrator/handoff reconciliation and FB-706 selection. No commit, self-approval or canonical task status change was made by the developer.
