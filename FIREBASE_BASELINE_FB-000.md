# FB-000 — Pre-Migration Baseline

> Task: `FB-000` (Phase P0) — "Inventory IDs/source sets/contracts/build commands and record Android, common-test, iOS simulator baseline plus known failures."
>
> Captured: **2026-09-17** · Branch `epic/firebase` at `311ec74` · Read-only capture; no product, Gradle, or Xcode configuration was changed.
>
> This document is evidence only. Canonical task state stays in [`FIREBASE_MIGRATION_STATUS.md`](FIREBASE_MIGRATION_STATUS.md).

---

## 1. Identifiers and versions

### Android

| Item | Value | Source |
|---|---|---|
| `applicationId` | `com.fluxit` | `composeApp/build.gradle.kts:68` |
| `namespace` | `com.fluxit` | `composeApp/build.gradle.kts:64` |
| `compileSdk` | `36` | `composeApp/build.gradle.kts:65` via `gradle/libs.versions.toml:17` |
| `minSdk` | `26` | `composeApp/build.gradle.kts:69` via `gradle/libs.versions.toml:18` |
| `targetSdk` | `36` | `composeApp/build.gradle.kts:70` via `gradle/libs.versions.toml:19` |
| `versionCode` / `versionName` | `1` / `1.0` | `composeApp/build.gradle.kts:71-72` |
| Java source/target | `17` (also `jvmTarget = JVM_17`) | `composeApp/build.gradle.kts:18, 80-81` |
| Application class | `com.fluxit.FluxItApplication` | `composeApp/src/androidMain/AndroidManifest.xml:4` |
| Launcher activity | `com.fluxit.MainActivity` | `composeApp/src/androidMain/AndroidManifest.xml:10` |
| Release signing | **None configured** — `assembleRelease` produces `composeApp-release-unsigned.apk` | `composeApp/build.gradle.kts:74-78` |

Note for `FB-002`/`FB-004`: the Android app to register in Firebase is `com.fluxit`. There is no flavor/`applicationIdSuffix` split today, so a dev/prod split needs a deliberate decision (build types, flavors, or separate Firebase projects with one app ID).

### iOS

| Item | Value | Source |
|---|---|---|
| Bundle identifier (build setting) | `$(BUNDLE_ID)$(TEAM_ID)` — **superseded 2026-09-17 by `FB-012`**, which removed the `$(TEAM_ID)` suffix so the setting now reads `$(BUNDLE_ID)`; this row records the pre-`FB-012` baseline state and is intentionally left unedited otherwise | `iosApp/iosApp.xcodeproj/project.pbxproj:332, 356` |
| `BUNDLE_ID` | `com.fluxit.FluxIt` | `iosApp/Configuration/Config.xcconfig:2` |
| `TEAM_ID` | **empty** (so the effective bundle id resolves to `com.fluxit.FluxIt`, confirmed in the `actool` invocation of the simulator build) | `iosApp/Configuration/Config.xcconfig:1` |
| `APP_NAME` / `PRODUCT_NAME` | `FluxIt` | `iosApp/Configuration/Config.xcconfig:3`; `project.pbxproj:333, 357` |
| `CFBundleName` | `FluxIt` | `iosApp/iosApp/Info.plist` |
| `IPHONEOS_DEPLOYMENT_TARGET` | `15.0` | `project.pbxproj:235, 300, 326, 350` |
| `SWIFT_VERSION` | `5.0` | `project.pbxproj:334, 358` |
| Code signing | `CODE_SIGN_IDENTITY = "Apple Development"`, `CODE_SIGN_STYLE = Automatic`, `DEVELOPMENT_TEAM = $(TEAM_ID)` (unset) — `DEVELOPMENT_TEAM`/`CODE_SIGN_IDENTITY`/`CODE_SIGN_STYLE` were unaffected by `FB-012` (2026-09-17) and remain exactly as recorded here | `project.pbxproj:319-323, 343-347` |
| Xcode target / scheme | `iosApp` (single target, single shared scheme) | `xcodebuild -list` |
| KMP framework | `ComposeApp`, **static** (`isStatic = true`), targets `iosArm64` + `iosSimulatorArm64` | `composeApp/build.gradle.kts:22-31` |
| Xcode → Gradle hook | Run-script phase executes `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` | `project.pbxproj:162` |

`TEAM_ID` being empty is why the bundle id is currently `com.fluxit.FluxIt`. If a team id is later set, the effective bundle id becomes `com.fluxit.FluxIt<TEAM_ID>` — this must be settled in `DEC-002` **before** registering the iOS app in Firebase, otherwise `GoogleService-Info.plist` will not match.

### Toolchain and libraries

| Item | Version | Source |
|---|---|---|
| Gradle wrapper | `8.13` | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | `8.9.2` | `gradle/libs.versions.toml:4` |
| Kotlin | `2.3.20` | `gradle/libs.versions.toml:2` |
| KSP | `2.3.6` | `gradle/libs.versions.toml:3` |
| Compose Multiplatform | `1.10.3` | `gradle/libs.versions.toml:5` |
| Room | `2.7.2`; androidx.sqlite `2.5.2` | `gradle/libs.versions.toml:9-10` |
| Koin | `4.1.0` | `gradle/libs.versions.toml:11` |
| Navigation3 | `1.1.1`; lifecycle `2.10.0` | `gradle/libs.versions.toml:7-8` |
| coroutines / datetime / serialization | `1.10.2` / `0.7.1` / `1.9.0` | `gradle/libs.versions.toml:12-14` |
| Local JDK (Gradle launcher + daemon) | Azul Zulu **23.0.2** | `./gradlew --version` |
| Xcode | `27.0` (build `27A266a`), iPhoneSimulator SDK `27.0` | `xcodebuild -version` |
| OS | macOS 26.6.2, arm64 | `./gradlew --version` |

Gradle features in use that Firebase work must not silently break: `org.gradle.configuration-cache=true` and `org.gradle.caching=true` (`gradle.properties`), plus `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` (`settings.gradle.kts:2`).

Repository filtering in `settings.gradle.kts` uses `google { mavenContent { includeGroupAndSubgroups("androidx"/"com.android"/"com.google") } }`. Firebase Android artifacts (`com.google.firebase`, `com.google.gms`) fall under `com.google`, so they resolve without editing the repository filters — but this should be re-verified in `FB-006`.

---

## 2. Modules and source sets

Single Gradle module: **`:composeApp`** (`settings.gradle.kts:31`). Root project name `FluxIt`. There is no separate `shared` module — Android app code, iOS framework code, and shared code all live in `:composeApp`.

Targets: `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`. (No `iosX64`, so Intel simulators are unsupported.)

```
composeApp/src/
├── commonMain/kotlin/com/fluxit/
│   ├── App.kt                         Compose root (FluxItTheme + AppNavHost)
│   ├── data/                          Room entities, DAOs, database, Room repos,
│   │                                  DebugSeeder, PhotoPicker/PhotoStorage interfaces
│   ├── di/AppModule.kt                Koin appModule + `expect fun platformModule(): Module`
│   ├── domain/                        Models.kt, Repositories.kt (no platform imports)
│   ├── feature/{createlist,dashboard,itemdetail,listdetail}/   Screen + ViewModel pairs
│   ├── navigation/AppNavHost.kt       Navigation 3 routes
│   └── ui/{components,theme}/
├── commonMain/composeResources/values/strings.xml
├── androidMain/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/fluxit/FluxItApplication.kt, MainActivity.kt
│   ├── kotlin/com/fluxit/data/PhotoBridges.android.kt
│   ├── kotlin/com/fluxit/di/PlatformModule.android.kt
│   └── kotlin/com/fluxit/ui/components/ImageDecoder.android.kt
├── iosMain/
│   ├── kotlin/com/fluxit/MainViewController.kt
│   ├── kotlin/com/fluxit/data/PhotoBridges.ios.kt
│   ├── kotlin/com/fluxit/di/PlatformModule.ios.kt
│   └── kotlin/com/fluxit/ui/components/ImageDecoder.ios.kt
└── commonTest/kotlin/com/fluxit/
    ├── FakeRepositories.kt
    └── ViewModelTests.kt
```

Total Kotlin source: **3,191 lines** across `composeApp/src`.

**There is no `androidUnitTest`, `androidInstrumentedTest`, or `iosTest` source set.** All tests live in `commonTest` and are executed by every target's test task.

### iOS app shell

```
iosApp/
├── Configuration/Config.xcconfig      TEAM_ID / BUNDLE_ID / APP_NAME
├── iosApp/
│   ├── iOSApp.swift                   @main SwiftUI App -> ContentView
│   ├── ContentView.swift              UIViewControllerRepresentable -> MainViewControllerKt.MainViewController()
│   ├── Info.plist
│   └── Assets.xcassets/
└── iosApp.xcodeproj                   single target `iosApp`, single scheme `iosApp`
```

Firebase-relevant consequence: `MainViewController()` in `composeApp/src/iosMain/kotlin/com/fluxit/MainViewController.kt:11-17` is where Koin is started (guarded by a `koinStarted` flag). `iOSApp.swift` has **no `AppDelegate`**, so `FirebaseApp.configure()` in `FB-007` needs either a new `@UIApplicationDelegateAdaptor`/`init` in `iOSApp.swift`, or a Kotlin-side initialization call reached from `MainViewController()`.

---

## 3. Integration map (repository contracts and behavior to preserve)

### 3.1 Domain models — `composeApp/src/commonMain/kotlin/com/fluxit/domain/Models.kt`

```kotlin
enum class ListIcon { CART, TRAVEL, WORK, HOME, GIFT, FOOD, FITNESS, STAR }   // :3-5
enum class ListColor { PRIMARY_BLUE, ORANGE, EMERALD, ROSE, INDIGO, SKY }     // :7-9

data class FluxList(id: String, name: String, icon: ListIcon, color: ListColor,
                    sortOrder: Double, createdAt: Long, updatedAt: Long)      // :11-19
data class FluxListSummary(list: FluxList, totalItems: Int, completedItems: Int) // :21-25
data class FluxItem(id: String, listId: String, title: String, description: String?,
                    isCompleted: Boolean, photoPath: String?, sortOrder: Double,
                    createdAt: Long, updatedAt: Long)                          // :27-37
```

Timestamps are `Long` epoch-millis, **not** nullable — Firestore server timestamps arrive unresolved (`null`) in the local snapshot, so Phase 2 mapping needs a client-time fallback or the model must change.

Domain models carry **no `deletedAt`**; the tombstone lives only in the Room entity and is filtered by the DAO queries. Firebase repositories must replicate that filtering so the domain surface stays identical.

### 3.2 Repository contracts — `composeApp/src/commonMain/kotlin/com/fluxit/domain/Repositories.kt`

```kotlin
interface ListRepository {                                              // :5-13
    fun observeListSummaries(): Flow<List<FluxListSummary>>
    fun observeList(listId: String): Flow<FluxList?>
    suspend fun createList(name: String, icon: ListIcon, color: ListColor): String
    suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor)
    suspend fun softDeleteList(listId: String)
    suspend fun restoreList(listId: String)
    suspend fun purgeExpired()
}

interface ItemRepository {                                              // :15-26
    fun observeItems(listId: String): Flow<List<FluxItem>>
    fun observeItem(itemId: String): Flow<FluxItem?>
    suspend fun addItem(listId: String, title: String)
    suspend fun updateItem(itemId: String, title: String, description: String?)
    suspend fun setCompleted(itemId: String, completed: Boolean)
    suspend fun setPhotoPath(itemId: String, photoPath: String?)
    suspend fun softDeleteItem(itemId: String)
    suspend fun restoreItem(itemId: String)
    suspend fun deleteItem(itemId: String)
    suspend fun clearCompleted(listId: String)
}
```

Neither interface declares a failure channel: every mutation returns `Unit`/`String` and cannot signal an error. This is the contract gap Phase 4 has to close.

`observeItem(itemId)` takes only an item id — with the planned `users/{uid}/lists/{listId}/items/{itemId}` layout, an item id alone does not identify a document path. Phase 2 must either add `listId` to this signature or use a collection-group query.

### 3.3 Room layer

- Entities — `composeApp/src/commonMain/kotlin/com/fluxit/data/Entities.kt`
  - `ListEntity` (`list_table`, :9-19): `id` PK, `name`, `icon` (enum `name`), `color` (enum `name`), `sortOrder: Double`, `createdAt`, `updatedAt`, `deletedAt: Long?`.
  - `ItemEntity` (`item_table`, :21-44): `id` PK, `listId` (FK to `list_table.id`, `onDelete = CASCADE`, indexed), `title`, `description`, `isCompleted`, `photoPath`, `sortOrder`, `createdAt`, `updatedAt`, `deletedAt: Long?`.
  - `ListWithCounts` projection (:46-56) adds `totalItems`/`completedItems`.
- DAOs — `composeApp/src/commonMain/kotlin/com/fluxit/data/Daos.kt`
  - **Dashboard count query** (`ListDao.observeListsWithCounts`, :11-21): two correlated `SELECT COUNT(*)` subqueries over `item_table` filtered by `deletedAt IS NULL` (and `isCompleted` for the completed count), outer filter `l.deletedAt IS NULL`, `ORDER BY l.sortOrder ASC`. This is the query the plan replaces with denormalized `totalItems`/`completedItems` counters on the list document.
  - `ListDao.maxSortOrder()` (:35-36) and `ItemDao.maxSortOrder(listId)` (:59-60): `COALESCE(MAX(sortOrder), 0)` — the unsafe `MAX + 1` ordering scheme the plan replaces with `createdAt` + doc-id tie-break.
  - Tombstone purge: `ListDao.purgeDeleted(olderThan)` (:41-42) and `ItemDao.purgeDeleted(olderThan)` (:80-81), both `DELETE ... WHERE deletedAt IS NOT NULL AND deletedAt < :olderThan`.
  - `ItemDao.softDeleteCompleted(listId, deletedAt)` (:77-78) backs `clearCompleted`.
- Database — `composeApp/src/commonMain/kotlin/com/fluxit/data/FluxItDatabase.kt`: `@Database(entities = [ListEntity, ItemEntity], version = 1)`, `expect object FluxItDatabaseConstructor`. Schema JSON checked in at `composeApp/schemas/com.fluxit.data.FluxItDatabase/1.json`.
- DB file name: `fluxit.db` on both platforms (`PlatformModule.android.kt:21`, `PlatformModule.ios.kt:32`). Both use `BundledSQLiteDriver()`.

### 3.4 Soft delete / tombstone purge (behavior to preserve)

| Concern | Current implementation |
|---|---|
| Undo window (UI) | **5 s** — `DashboardViewModel.deleteList` (`.../dashboard/DashboardViewModel.kt:53-61`) and `ListDetailViewModel.deleteItem` (`.../listdetail/ListDetailViewModel.kt:81-89`), both `delay(5_000)` |
| Tombstone retention (data) | **60 s** — `RoomListRepository.purgeExpired` (`.../data/Repositories.kt:97-101`): `cutoff = nowMillis() - 60_000L`, purges both tables |
| Purge trigger | `DashboardViewModel.init { viewModelScope.launch { listRepository.purgeExpired() } }` (`.../dashboard/DashboardViewModel.kt:45-47`) — client-side, fires whenever the dashboard VM is created |
| Hard delete | Only `ItemRepository.deleteItem` (`Repositories.kt:144`, from `ItemDetailViewModel.deleteItem`) — no undo |
| Cascade | Relies on the SQLite FK `onDelete = CASCADE`; Firestore has no equivalent, so Phase 5 must delete item subcollections explicitly |

Note the mismatch to carry into `DEC-003`: the UI undo window is 5 s but the data retention window is 60 s.

### 3.5 Photo storage path handling

- Contracts — `composeApp/src/commonMain/kotlin/com/fluxit/data/PhotoBridges.kt`:
  ```kotlin
  interface PhotoPicker  { suspend fun pickPhoto(): ByteArray? }            // :4-6
  interface PhotoStorage {                                                  // :9-12
      suspend fun savePhoto(bytes: ByteArray): String   // returns a stable ABSOLUTE path
      suspend fun deletePhoto(path: String)
  }
  ```
- Android (`PhotoBridges.android.kt`): `AndroidPhotoPicker` wraps `ActivityResultContracts.PickVisualMedia` and **must be registered from `MainActivity.onCreate`** (`MainActivity.kt:18` casts the injected `PhotoPicker` to `AndroidPhotoPicker` and calls `register(this)`). `AndroidPhotoStorage` writes `<filesDir>/photos/<uuid>.jpg` and returns `file.absolutePath` (:51-55).
- iOS (`PhotoBridges.ios.kt`): `IosPhotoPicker` presents `PHPickerViewController`; `IosPhotoStorage` writes `<documents>/photos/<uuid>.jpg` and returns that path (:98-105).
- Decoding: `expect fun decodeImageFile(path: String): ImageBitmap?` (`ui/components/ImageDecoder.kt:6`), with `BitmapFactory.decodeFile` on Android and `NSData.dataWithContentsOfFile` + Skia on iOS. This synchronous, path-based decode does not survive a move to remote objects and will need an async/loading-state replacement in Phase 3.
- `photoPath` occurrences to rename in Phase 3 (18 non-test sites): `domain/Models.kt:33`, `domain/Repositories.kt:21`, `data/Entities.kt:39`, `data/Daos.kt:68-69`, `data/Repositories.kt:37,122,137-138`, `feature/itemdetail/ItemDetailViewModel.kt:24,55,90,94,102,106,112`, `feature/itemdetail/ItemDetailScreen.kt:226,243`. Test doubles add `FakeRepositories.kt:77-78`.
- Replacement ordering already matches the plan's intent (`ItemDetailViewModel.pickPhoto`, :84-99): save new → update repo → delete old, inside `try/finally` resetting `isPickingPhoto`.

### 3.6 Koin wiring — `composeApp/src/commonMain/kotlin/com/fluxit/di/AppModule.kt`

```kotlin
expect fun platformModule(): Module                                       // :18

val appModule = module {                                                  // :20-29
    single<ListRepository> { RoomListRepository(get<FluxItDatabase>()) }  // :21  <- Room binding to remove (FB/Phase 2)
    single<ItemRepository> { RoomItemRepository(get<FluxItDatabase>()) }  // :22  <- Room binding to remove (FB/Phase 2)
    single { DebugSeeder(get(), get()) }
    viewModel { DashboardViewModel(get(), get()) }
    viewModel { (listId: String) -> ListDetailViewModel(listId, get(), get()) }
    viewModel { (editingId: String?) -> CreateListViewModel(editingId, get()) }
    viewModel { (itemId: String) -> ItemDetailViewModel(itemId, get(), get(), get(), get()) }
}
```

`actual platformModule()` currently provides `FluxItDatabase`, `PhotoPicker`, `PhotoStorage`:
- `composeApp/src/androidMain/kotlin/com/fluxit/di/PlatformModule.android.kt:16-29`
- `composeApp/src/iosMain/kotlin/com/fluxit/di/PlatformModule.ios.kt:30-39`

Koin start points: `FluxItApplication.onCreate` (`androidMain/.../FluxItApplication.kt:12-15`) and `MainViewController()` (`iosMain/.../MainViewController.kt:11-16`).

### 3.7 Other Firebase-relevant observations

- `ListIcon.valueOf(...)` / `ListColor.valueOf(...)` are called unguarded in `data/Repositories.kt:24-25, 53-54` — with Firestore's untyped documents this throws on unknown/missing values (called out in the plan's Phase 2).
- `DebugSeeder` (`data/DebugSeeder.kt`) writes sample data through the repository interfaces; it is injected into `DashboardViewModel` and will issue authenticated writes once Firebase is in place.
- Navigation routes (`navigation/AppNavHost.kt:24-33`): `DashboardRoute`, `ListDetailRoute(listId)`, `CreateListRoute(editingId?)`, `ItemDetailRoute(itemId)`. Phase 1 needs a pre-auth root above `AppNavHost` so no user-scoped listener starts before the UID resolves.
- No `AuthRepository`, session model, error type, or loading/offline state exists anywhere in the codebase today.

---

## 4. Existing tests

| Source set | File | Contents |
|---|---|---|
| `commonTest` | `composeApp/src/commonTest/kotlin/com/fluxit/FakeRepositories.kt` | `FakeListRepository`, `FakeItemRepository` — in-memory `MutableStateFlow` doubles implementing the full domain interfaces |
| `commonTest` | `composeApp/src/commonTest/kotlin/com/fluxit/ViewModelTests.kt` (243 lines) | `DashboardViewModelTest` (3), `ListDetailViewModelTest` (4), `CreateListViewModelTest` (4) |

**11 tests total**, all `runTest` with an injected test dispatcher. Named tests:

- `DashboardViewModelTest`: `searchFiltersListsCaseInsensitively`, `deleteThenUndoRestoresList`, `undoWindowExpiresAfterFiveSeconds`
- `ListDetailViewModelTest`: `composerSubmitAddsItemAndClearsText`, `blankComposerIsIgnored`, `toggleMovesItemBetweenSections`, `clearCompletedSoftDeletesOnlyCompleted`
- `CreateListViewModelTest`: `blankNameIsInvalid`, `nameIsCappedAtMaxLength`, `saveCreatesListAndExposesId`, `editModeLoadsExistingValuesAndTracksDirty`

There are **no** repository/Room tests, no `ItemDetailViewModel` tests, no instrumented tests, no UI tests, and no Rules/emulator tests. Adding Firebase repositories therefore adds no failing tests by itself — the fakes keep these green — which is exactly why Phase 2/6 need new emulator-backed tests.

`commonTest` dependencies: `kotlin-test` and `kotlinx-coroutines-test` only (`composeApp/build.gradle.kts:56-59`).

---

## 5. Baseline command results

Environment: macOS 26.6.2 arm64, JDK Azul Zulu 23.0.2, Xcode 27.0. All commands run from the repository root on **2026-09-17**, branch `epic/firebase` at `311ec74`.

| # | Command | Exit | Duration | Result |
|---|---|---|---|---|
| 1 | `./gradlew --version` | 0 | <1 s | Gradle 8.13, Launcher/Daemon JVM 23.0.2 |
| 2 | `./gradlew tasks --all` | 0 | 6 s | `BUILD SUCCESSFUL in 4s` — task names enumerated below |
| 3 | `./gradlew :composeApp:allTests` | 0 | 8 s | `BUILD SUCCESSFUL`, but **every** test task was `UP-TO-DATE` — no test actually ran. Superseded as evidence by the forced runs #6, #9 and #10 |
| 4 | `./gradlew :composeApp:assembleDebug` | 0 | 3 s | `BUILD SUCCESSFUL`; `composeApp/build/outputs/apk/debug/composeApp-debug.apk` (28 MB) |
| 5 | `./gradlew :composeApp:assembleRelease` | 0 | 38 s | `BUILD SUCCESSFUL`; `composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk` (21 MB). **No signing config, so no signing secrets are required** |
| 6 | `./gradlew :composeApp:cleanAllTests :composeApp:allTests` | 0 | 3 s | `BUILD SUCCESSFUL`; **only `iosSimulatorArm64Test` was actually re-executed** — see the `cleanAllTests` caveat below |
| 7 | `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath iosApp/build/dd CODE_SIGNING_ALLOWED=NO build` | 0 | 7 s | `** BUILD SUCCEEDED **`; produced `iosApp/build/dd/Build/Products/Debug-iphonesimulator/FluxIt.app` |
| 8 | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` | 0 | 10 s | `BUILD SUCCESSFUL` |
| 9 | `./gradlew :composeApp:testDebugUnitTest --rerun-tasks` | 0 | 12 s | `BUILD SUCCESSFUL in 11s`; `testDebugUnitTest` genuinely re-executed |
| 10 | `./gradlew :composeApp:testReleaseUnitTest --rerun-tasks` | 0 | 5 s | `BUILD SUCCESSFUL in 5s`; `testReleaseUnitTest` genuinely re-executed (added after review — see the `cleanAllTests` caveat below) |

#### The `cleanAllTests` caveat

`:composeApp:cleanAllTests` does **not** clean the Android/JVM unit-test tasks. Verified:

```
$ ./gradlew :composeApp:cleanAllTests --dry-run
:composeApp:cleanIosSimulatorArm64Test SKIPPED
:composeApp:cleanAllTests SKIPPED
```

It depends only on `cleanIosSimulatorArm64Test`. So command #6 forced **only** `iosSimulatorArm64Test`, and command #9 forced **only** the debug variant. Until command #10 was added, no command in this table had ever forced or cleaned `testReleaseUnitTest`, and its results were carried over from a previous session dated 2026-09-16. Command #10 closes that gap.

#### Test outcomes, with the JUnit XML timestamp each row is based on

Each test task writes `composeApp/build/test-results/<task>/TEST-com.fluxit.*.xml`. The counts below are the sum of the three XML files per task; the timestamps are the `timestamp` attribute (UTC) and file mtime (local), so a stale row would be self-evident.

| Test task | tests | failures | errors | skipped | JUnit XML `timestamp` (UTC) | XML mtime (local) | Forced by |
|---|---|---|---|---|---|---|---|
| `testDebugUnitTest` | 11 | 0 | 0 | 0 | `2026-09-17T13:49:42.930Z` / `...:42.984Z` / `...:42.999Z` | 2026-09-17 07:49:43 | #9 |
| `testReleaseUnitTest` | 11 | 0 | 0 | 0 | `2026-09-17T13:53:50.580Z` / `...:50.633Z` / `...:50.649Z` | 2026-09-17 07:53:50 | #10 |
| `iosSimulatorArm64Test` | 11 | 0 | 0 | 0 | `2026-09-17T13:49:22.062Z` / `...:22.065Z` / `...:22.066Z` | 2026-09-17 07:49:22 | #6 |

Per-class breakdown, identical for all three tasks: `DashboardViewModelTest` 3, `ListDetailViewModelTest` 4, `CreateListViewModelTest` 4.

All three tasks were force-executed in this session on 2026-09-17 and every XML timestamp was re-read from disk after the run rather than assumed from the exit code.

**Baseline verdict: green, and all three test tasks are force-verified as of 2026-09-17.** No failing tests and no failing builds on Android debug, Android release, common tests (JVM debug + JVM release + iOS simulator), the iOS simulator framework link, or the Xcode simulator app build.

### Relevant task names (from `./gradlew tasks --all`)

```
:composeApp:allTests                                Runs the tests for all targets and creates an aggregated report
:composeApp:cleanAllTests                           Despite the description ("Deletes all the test results") it
                                                    depends ONLY on cleanIosSimulatorArm64Test — it does NOT clean
                                                    testDebugUnitTest or testReleaseUnitTest
:composeApp:testDebugUnitTest                       JVM/Android unit tests (debug)
:composeApp:testReleaseUnitTest                     JVM/Android unit tests (release)
:composeApp:iosSimulatorArm64Test                   Kotlin/Native unit tests on iosSimulatorArm64
:composeApp:assembleDebug / :composeApp:assembleRelease
:composeApp:installDebug
:composeApp:linkDebugFrameworkIosSimulatorArm64     framework link, simulator
:composeApp:linkDebugFrameworkIosArm64              framework link, device
:composeApp:linkReleaseFrameworkIosSimulatorArm64 / :composeApp:linkReleaseFrameworkIosArm64
:composeApp:embedAndSignAppleFrameworkForXcode      invoked by the Xcode run-script phase
:composeApp:connectedDebugAndroidTest               (no instrumented test sources exist)
```

There is **no** `iosArm64Test` task (Kotlin/Native cannot run device tests without a device), so `iosArm64` is build/link-verified only.

### Notes on build-cache state (important for interpreting durations)

The worktree carried pre-existing `build/` directories, so commands #3, #4, #5, #7 and #8 ran incrementally. The durations above are **incremental**, not cold-build baselines, and should not be used as a regression threshold for Firebase-enabled builds. If a cold-build baseline is required for a later phase, re-run after `./gradlew clean` and record separately.

Because of that pre-existing state, an `UP-TO-DATE` test task proves nothing about this session — it only means Gradle found prior results on disk. This bit once and is worth spelling out:

- Command #3 (`allTests`) reported success with **every** test task `UP-TO-DATE`; the results it "passed" on were from a previous session.
- Command #6 (`cleanAllTests`) looked like it fixed that, but `cleanAllTests` only cleans `iosSimulatorArm64Test` (see the caveat above), so the two Android/JVM variants stayed stale.
- The first version of this document consequently reported a `testReleaseUnitTest` row whose JUnit XML was dated **2026-09-16T15:19Z** while claiming a 2026-09-17 capture. The reviewer caught it; command #10 (`testReleaseUnitTest --rerun-tasks`) was added and the XML re-read from disk to confirm the new timestamp.

Rule for later phases: force each test task you intend to cite (`--rerun-tasks`, or a clean task you have verified actually covers that variant) and check the JUnit XML timestamp afterwards. Do not treat `BUILD SUCCESSFUL` plus an `UP-TO-DATE` test task as evidence that tests ran.

---

## 6. Known failures and environment limitations

No command failed. The limitations below are gaps in coverage, not failures.

1. **No cold-build timing baseline.** See the note above — all build timings are incremental.
2. **iOS physical device not verified.** `TEAM_ID` is empty in `iosApp/Configuration/Config.xcconfig`, `DEVELOPMENT_TEAM` resolves to empty, and the simulator build was run with `CODE_SIGNING_ALLOWED=NO`. `:composeApp:embedAndSignAppleFrameworkForXcode` was consequently `SKIPPED` in the Xcode build (the framework was still built and linked via `assembleDebugAppleFrameworkForXcodeIosSimulatorArm64`). Device signing, the device build, and the embed-and-sign path are **unverified** — this is the scope of `MAN-003` / `FB-011` and requires the user.
3. **No app was launched or exercised on a device, emulator, or booted simulator.** Only compilation/link/test evidence was gathered. No manual functional check of the current app behavior (undo, photos, seeding) has been performed or claimed.
4. **`connectedDebugAndroidTest` was not run** — there are no instrumented test sources and no device/emulator was attached.
5. **`iosArm64` has no test task**, so common tests are verified on JVM and `iosSimulatorArm64` only.
6. **Xcode 27.0 / iOS 15.0 deployment target.** The Firebase Apple SDK raises the minimum deployment target on recent versions; `IPHONEOS_DEPLOYMENT_TARGET = 15.0` may have to be raised in `FB-007`, which is a user-visible support change.
7. **Kotlin 2.3.20 with AGP 8.9.2 and KSP `2.3.6`** is an unusual combination. `lintVitalRelease` emitted non-fatal `e: ... kotlin-stdlib-2.3.20.jar!/META-INF/*.kotlin_module Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 2.1.0.` messages during `assembleRelease`. The build still succeeded (exit 0). **This is pre-existing and unrelated to Firebase** — record it now so it is not mistaken for a Firebase regression later.
8. **`org.gradle.configuration-cache=true`** is enabled. The Google Services Gradle plugin has historically been sensitive to the configuration cache; `FB-006` should verify the configuration cache entry is still stored after adding it.
9. **No Firebase artifacts exist anywhere yet** — no `google-services.json`, no `GoogleService-Info.plist`, no `firebase.json`, no `firestore.rules`, no `storage.rules`, no emulator config, and no Firebase dependency in `gradle/libs.versions.toml`. `.gitignore` currently has no entry covering Firebase config files; their handling policy is `DEC-002`.

---

## 7. Git state at completion

```
branch:  epic/firebase
commit:  311ec745565e8056fb99b0198597274b3d8ed60a
git status --short:
 M FIREBASE_MIGRATION_STATUS.md
?? FIREBASE_BASELINE_FB-000.md
```

`FIREBASE_MIGRATION_STATUS.md` was already modified at session start (orchestrator-owned; not touched by this task). `FIREBASE_BASELINE_FB-000.md` is the only file added by `FB-000`. Nothing was committed. No product code, Gradle configuration, or Xcode configuration was modified.

Build outputs were regenerated under the git-ignored `composeApp/build/`, `iosApp/build/`, `.gradle/`, and `.kotlin/` directories.

No credentials, tokens, keystore passwords, team identifiers, or signing secrets are recorded in this document.
