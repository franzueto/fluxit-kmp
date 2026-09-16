# FluxIt

An offline-only list-making app with system-adaptive light and dark themes, built with
**Kotlin Multiplatform** and **Compose Multiplatform** (shared UI on Android and iOS).

## Build & run

### Android

```sh
./gradlew :composeApp:installDebug
```

Then launch **FluxIt** on the device/emulator (min SDK 26). Or open the project in
Android Studio and run the `composeApp` configuration.

### iOS

Open `iosApp/iosApp.xcodeproj` in Xcode and run the `iosApp` scheme (iOS 15+,
simulator or device — set your team in `iosApp/Configuration/Config.xcconfig` for
device signing). The Xcode build invokes
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` automatically.

### Tests

```sh
./gradlew :composeApp:testDebugUnitTest
```

Common unit tests cover ViewModel/repository logic with fake repositories.

## Package layout

```
composeApp/src/
├── commonMain/kotlin/com/fluxit/
│   ├── data/            Room entities, DAOs, database, repository impls, debug seeder,
│   │                    PhotoPicker/PhotoStorage bridge interfaces
│   ├── domain/          plain models + repository interfaces (no platform imports)
│   ├── ui/theme/        design tokens (light/dark colors, type, spacing, shapes)
│   ├── ui/components/   reusable composables (swipe-to-delete, empty state, icon mapping)
│   ├── di/              Koin modules
│   ├── navigation/      routes + NavHost
│   └── feature/         dashboard, listdetail, createlist, itemdetail (screen + ViewModel)
├── androidMain/         MainActivity, Application, photo picker/storage actuals, Room driver
├── iosMain/             MainViewController, PHPicker photo picker, storage actuals, Room driver
└── commonTest/          ViewModel unit tests with fake repositories
iosApp/                  thin SwiftUI shell hosting the shared Compose UI
```

- **Stack:** Compose Multiplatform (Material 3), MVVM with `ViewModel` + `StateFlow`,
  Room KMP (BundledSQLiteDriver), Koin, Compose Navigation, kotlinx-datetime, `kotlin.uuid.Uuid`.
- **Platform bridges** (`expect`/`actual` or interface + platform impl): photo picker
  (system picker only), photo storage, image decoding, and the Room database builder.
- **Soft delete + undo:** deleting a list/item sets `deletedAt` and shows a 5-second undo
  snackbar; expired rows are purged when the dashboard loads.
- **Debug seeding:** the `[ ]` icon in the dashboard header seeds sample lists/items.

## Out of scope for v1

- Analytics
- Camera capture (photos come from the system photo picker only)
- Reminders / notifications / recurrence
- CI / GitHub Actions / Fastlane
- Sync, networking, auth, multi-device
- Calendar/Starred tabs, feature flags
