# KMP Architecture Reference

An offline-first list-making app for Android and iOS, built as a teaching
reference. It uses Kotlin Multiplatform to share domain, data, and presentation
state while keeping the UI native with Jetpack Compose and SwiftUI.

The project is a worked example of modularization, dependency boundaries, native
UI over a shared state layer, code generation, and architecture enforcement.

## Relationship to the product

The patterns here are drawn from **FluxIt**, a commercial app I'm building. This
repository is a teaching reference, not the product — it implements a subset of
the app's functionality and is deliberately structured to demonstrate more than
the product needs.

**It is deliberately over-engineered**, to demonstrate patterns you'd only reach
for at scale. A four-feature list app does not need sixteen Gradle modules,
generated design tokens, or Konsist architecture tests. The point is to show
those mechanisms working end to end on a codebase small enough to read in an
afternoon — not to suggest this is the right amount of structure for an app this
size.

Source code is licensed under Apache-2.0; the FluxIt brand assets and the
reference mockups in `design/` are reserved. See
[`LICENSE`](LICENSE) and [`LICENSE-ASSETS`](LICENSE-ASSETS).

## Product status

The core list-management experience is implemented on both platforms:

- Lists dashboard with search, create, edit, delete, and undo flows
- List detail with item creation, completion, deletion, and progress
- Item detail with notes and photo capture or selection
- Native Android and iOS navigation over shared MVI stores
- Local persistence with SQLDelight
- Platform logging, analytics, configuration, reminders, and photo capabilities

Calendar and Starred remain placeholder tabs. Authentication, networking, and
multi-device sync are outside the current offline-first scope. Future work should
be prioritized from product needs rather than an inherited implementation plan.

## Prerequisites

- JDK 21 (Temurin recommended), pinned in `mise.toml`
- Android SDK platform 35 and build-tools 35.0.0
- Xcode 16+ with an iOS 16+ Simulator runtime
- Ruby 3.4.9, pinned in `mise.toml`
- `xcodegen`, `openssl`, `libyaml`, and `readline` from Homebrew for iOS setup

Gradle is supplied by the wrapper and does not need to be installed separately.

## Run Android

1. Create a gitignored `local.properties` at the repository root:

   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```

2. Build the debug APK:

   ```bash
   ./gradlew :android-app:assembleDebug
   ```

3. Install it on a running emulator or connected device:

   ```bash
   ./gradlew :android-app:installDebug
   ```

You can also open the repository in Android Studio and run `android-app`.

## Run iOS

1. Install the native build dependencies:

   ```bash
   brew install xcodegen openssl libyaml readline
   mise install
   ```

2. Generate the Xcode project, build the shared XCFramework, and run the iOS
   smoke build:

   ```bash
   scripts/build-ios.sh
   ```

3. Open `ios-app/FluxIt.xcodeproj`, select the `FluxIt` scheme, and run it on an
   iOS 16+ Simulator.

`ios-app/project.yml` is the source of truth for the generated Xcode project.

## Repository map

```text
android-app/          Compose application and Android navigation
ios-app/              SwiftUI application consuming the shared XCFramework
shared/domain/        Entities, use cases, repository and platform contracts
shared/data/          SQLDelight database and repository implementations
shared/state/         Shared Flow-based MVI stores
shared/domain-testing Shared fakes for domain and state tests
core/                 Design system, generated tokens and shared utilities
features/             Android feature UI modules
platform/             Android and iOS capability implementations
build-logic/          Gradle convention plugins, generators and architecture tests
design/               Reference mockups for the core product surfaces (reserved)
docs/                 Current architecture, decisions and team conventions
```

For the dependency graph and layer responsibilities, see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Product and technical tradeoffs
are recorded in [`docs/DECISIONS.md`](docs/DECISIONS.md), and visual conventions
live in [`DESIGN.md`](DESIGN.md).

## Technical stack

- Gradle Kotlin DSL with a version catalog and convention plugins
- Kotlin Multiplatform, Coroutines, Flow and kotlinx-datetime
- SQLDelight for local persistence
- Koin for dependency injection
- SKIE for Swift-friendly shared APIs
- Jetpack Compose and SwiftUI for native UI
- WorkManager and `UNUserNotificationCenter` for reminders
- Platform-native photo capture and storage behind shared ports
- ktlint, detekt, Spotless and Konsist for quality enforcement

## Verification

Useful repository checks:

```bash
./gradlew check
./gradlew :build-logic:test --rerun-tasks
scripts/test-ios.sh
```

The architecture tests intentionally live in `build-logic`; use
`--rerun-tasks` when validating dependency-rule changes so Gradle does not reuse a
stale result.

## Contributing

Use short-lived branches and Conventional Commits. Keep changes focused, include
tests for behavior changes, and run the relevant Android and iOS checks before
requesting review. See [`docs/TEAM_GUIDELINES.md`](docs/TEAM_GUIDELINES.md) for the
working agreements.

The optional pre-commit hook formats staged Kotlin, Kotlin DSL, and Markdown:

```bash
scripts/install-hooks.sh
```

## License

Source code is licensed under the Apache License, Version 2.0 — see
[`LICENSE`](LICENSE).

The FluxIt name and brand assets, and the reference mockups in `design/`, are
reserved and not covered by that license — see [`LICENSE-ASSETS`](LICENSE-ASSETS).

Third-party assets keep their own licenses: the Material Symbols icons in
`core/core-designsystem/icons/` are Apache-2.0 (Copyright 2014 Google LLC, see
[`ATTRIBUTION.md`](core/core-designsystem/icons/ATTRIBUTION.md)), and the Inter
font files are under the SIL Open Font License 1.1.
