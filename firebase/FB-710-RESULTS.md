# FB-710 developer results

Developer evidence 2026-10-06 on `epic/firebase`, baseline `9a9c946`, uncommitted diff. Implementation evidence for independent review, not self-approval.

## Result matrix

| Check | Result |
|---|---|
| New tests on original `SwipeToDelete.kt` (Android emulator-5554) | 10 run, 7 FAILED (restored row not displayed x5, `onDelete` invoked 4x per swipe, ListDetail screen test timeout); 3 invariant tests (disabled, start-to-end, delete-without-Undo) passed |
| iOS simulator test on original file | FAILED: `row-a` not displayed after restore |
| Same tests on fixed file (Android emulator) | 10/10 passed |
| `testDebugUnitTest` / `testReleaseUnitTest` (`--rerun-tasks`) | 250 / 250 tests, 0 failures/errors/skips |
| `iosSimulatorArm64Test` (`--rerun-tasks`) | 312 tests, 0 failures/errors/skips; includes `SwipeToDeleteContainerIosTest` |
| `assembleDebug`, `assembleRelease`, `linkDebugFrameworkIosSimulatorArm64`, `linkReleaseFrameworkIosSimulatorArm64`, `assembleDebugAndroidTest` | BUILD SUCCESSFUL, 148 tasks executed, 0 from cache |

Dashboard "offset read before initialized" crash was not reproduced by the harness or screen tests (only the stuck non-displayed row); it is covered by the live flow and the shared root cause.

## Live Android (emulator-5554, debug build with fix, `zzfb710` probes only)

PASS, no FATAL in logcat, process id unchanged: ListDetail active-item delete -> Undo restores a displayed row (counter 1/2 restored); delete -> expire stays removed; re-delete after Undo works; completed-section item delete -> Undo twice, restored row toggles; Dashboard list delete -> Undo twice, restored row opens; Dashboard list delete -> expire removed. Probes were soft-deleted via swipe and allowed to expire; no other list/item touched. Installed app is the ordinary debug build (test APK removed). iOS simulator live gestures were not run (no UI automation available); iOS is covered by the compose UI test above.

## Source fingerprints (sha256)

- `composeApp/src/commonMain/kotlin/com/fluxit/ui/components/SwipeToDelete.kt` eacb0a2aa1b4ce59...ec238
- `composeApp/build.gradle.kts` db28d15a71e189b2...084af
- `SwipeToDeleteContainerInstrumentedTest.kt` a59f2d1e07a706f8...f0f
- `SwipeUndoScreensInstrumentedTest.kt` c40d223f253313c3...e8f
- `SwipeToDeleteContainerIosTest.kt` c8cb3865d6a7b88f...7e7
