# FB-710 swipe-to-delete Undo verification

Defect: after swipe-delete + Undo, a restored ListDetail row stayed a dismissed red swipe background and a restored Dashboard row crashed with `AnchoredDraggableState` "offset was read before being initialized". Both screens use `ui/components/SwipeToDelete.kt`.

## Root cause

Material3 1.4.0 (Android) / JetBrains 1.9.0 (Compose Multiplatform 1.10.3, iOS) `rememberSwipeToDismissBoxState` is `rememberSaveable` and saves `currentValue`. A deleted row settles at `EndToStart`. In a keyed `LazyColumn`, Undo re-adds the same id, the saved `EndToStart` is restored, the content is laid out at -width (invisible, red background) and the dismissed anchored state is measured before layout initializes it. Separately, `confirmValueChange` was observed invoking `onDelete` four times for one swipe (harmless only because the ViewModels de-duplicate by id).

## Fix

`SwipeToDeleteContainer` now uses a plain `remember`ed `SwipeToDismissBoxState` (discarded with the removed row; restored rows start `Settled`) and fires `onDelete` once from a `snapshotFlow` of `settledValue == EndToStart` (reads current `enabled`/`onDelete` via `rememberUpdatedState`). No ViewModel, repository, Rules or data change. Test-only dependency: `iosTest` gets `compose.uiTest` (experimental `ExperimentalComposeLibrary`).

## Automated checks

```sh
export JAVA_HOME=/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home
./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleDebug :composeApp:assembleRelease :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosSimulatorArm64 :composeApp:assembleDebugAndroidTest --rerun-tasks
# Android instrumented (keeps the installed app's data; -r reinstall, then remove only the test APK)
adb -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb -s emulator-5554 install -r -t composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.fluxit.ui.components.SwipeToDeleteContainerInstrumentedTest,com.fluxit.feature.swipeundo.SwipeUndoScreensInstrumentedTest com.fluxit.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 uninstall com.fluxit.test
```

Pre-fix proof: restore the original `SwipeToDelete.kt` (`git show 9a9c946:composeApp/src/commonMain/kotlin/com/fluxit/ui/components/SwipeToDelete.kt`), rerun the instrumented classes and `:composeApp:iosSimulatorArm64Test --tests '*SwipeToDeleteContainerIosTest*'`, then restore the fix.

Tests: `SwipeToDeleteContainerInstrumentedTest` (8: restored row displayed, first/only row restored, delete-again, delete without Undo stays removed, one swipe invokes once, disabled blocks swipe, start-to-end never deletes, restored row clickable), `SwipeUndoScreensInstrumentedTest` (2: real `DashboardScreen` and `ListDetailScreen` active+completed over in-memory repositories, two delete/Undo rounds each), `SwipeToDeleteContainerIosTest` (1, Apple simulator).

## Live Android flow (manual probes)

Use only throwaway `zzfb710` list/items. The Undo snackbar lasts 5 s, so chain swipe and Undo tap in one shell command (`adb shell input swipe 950 <y> 150 <y> 400`, sleep ~1 s, tap Undo; ListDetail Undo ~y=2120, Dashboard ~y=2057, confirm with `android layout`). Check `adb logcat -d | grep FATAL`. Delete all probes afterwards and let Undo expire; do not touch other data.
