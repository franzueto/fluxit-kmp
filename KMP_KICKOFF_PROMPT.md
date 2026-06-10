# FluxIt — Kotlin Multiplatform v1 Kickoff Prompt

---

You are a senior Kotlin Multiplatform engineer. Build **FluxIt**, a dark-mode-first, **offline-only list-making app**, as a clean, modern **Kotlin Multiplatform** application targeting **Android and iOS** with **Compose Multiplatform** as the shared UI. This is a fresh greenfield project.

Build only what is specified here. Do not add features, abstractions, or "future-proofing" that isn't called for.

## Design references (read these first)

The project ships with design assets — **use them as the source of truth for layout, spacing, and color**; the descriptions in this prompt are secondary to what the mockups show:

- **`DESIGN.md`** (repo root) — the brand/design language: color palette, typography scale, spacing rhythm, elevation, shapes, and per-component specs. Read it before building the theme.
- **`design/<screen>/screen.png`** — the visual mockup for each screen. Match these.
- **`design/<screen>/code.html`** — a Tailwind/HTML reference implementation of each screen with exact hex colors, Inter + Material Symbols fonts, radii, and structure. Translate this layout into Compose (do **not** ship a WebView — these are reference only).

The four screen folders are: `main_lists_dashboard`, `list_items_view`, `create_new_list`, `item_details_photo`. Open the PNG and the HTML for each screen before implementing it, and reconcile any difference between this prompt and the mockups in favor of the mockups (except where this prompt's scope rules remove a feature — e.g. ignore any bottom tab bar with Calendar/Starred tabs, since those are out of scope).

## Hard scope rules (what NOT to build)

- **No analytics** of any kind.
- **No camera / CameraX / AVCapture.** Photos come only from each platform's **system photo picker** (Android: `ActivityResultContracts.PickVisualMedia`; iOS: `PHPickerViewController`).
- **No reminders, notifications, WorkManager, AlarmManager, local notifications, deep links, or recurrence.**
- **No CI / GitHub Actions / Fastlane.** No `.github/`.
- **No sync, no networking (Ktor/Retrofit), no auth, no Calendar tab, no Starred tab, no feature flags, no multi-device anything.** Build a single, simple v1.
- **No SKIE, no Swift-side business logic.** The iOS app is a thin SwiftUI/UIKit shell hosting the shared Compose UI plus the platform photo-picker bridge.
- Keep dependencies minimal and idiomatic.

## Tech stack (locked)

- **Language/UI:** Kotlin + **Compose Multiplatform** (Material 3) shared across Android and iOS. Single-Activity on Android; a `UIViewController` (`ComposeUIViewController`) hosting the same UI on iOS.
- **Architecture:** MVVM — `ViewModel` (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`) + `StateFlow` UI state, unidirectional data flow. Clean-ish layering by package, everything in `commonMain` except true platform bridges.
- **Persistence:** **Room KMP** (androidx.room 2.8+, which supports common/iOS targets) with the **BundledSQLiteDriver**. Flows for reads, suspend functions for writes. (If a Room KMP blocker appears, fall back to SQLDelight — but try Room first.)
- **DI:** **Koin** (Hilt is Android-only; do not use it).
- **Navigation:** **Compose Multiplatform Navigation** (`org.jetbrains.androidx.navigation:navigation-compose`), single `NavHost` in common code.
- **Concurrency:** Coroutines + Flow.
- **Time/IDs:** `kotlinx-datetime` (or epoch millis as `Long`) + `kotlin.uuid.Uuid` (Kotlin 2.x standard library). **No `java.time`, no `java.util.UUID` in common code.**
- **Platform bridges via `expect`/`actual`, kept to exactly two:**
  1. `PhotoPicker` — launches the system photo picker and returns the picked image bytes (or a temp path).
  2. `PhotoStorage` (or the storage path provider) — copies the picked image into app-internal storage and returns a stable path string.
  Database builder/driver setup may also need a small per-platform `actual` (Room/SQLite path) — that's expected and fine.
- **Android:** Min SDK 26, target latest stable, JDK 17. **iOS:** iOS 15+, `iosArm64` + `iosSimulatorArm64`.
- Gradle Kotlin DSL + version catalog (`libs.versions.toml`).

## Project structure

```
composeApp/                      ← single shared KMP module
└── src/
    ├── commonMain/kotlin/com/fluxit/
    │   ├── data/            ← Room entities, DAOs, database, repository impls, photo storage, debug seeder
    │   ├── domain/          ← plain model classes + repository interfaces (no platform imports)
    │   ├── ui/theme/        ← design tokens: Color, Type, Shape, Spacing, Theme (dark-only)
    │   ├── ui/components/   ← reusable composables (list row, swipe-to-delete, empty state)
    │   ├── di/              ← Koin modules
    │   ├── navigation/      ← routes + NavHost
    │   └── feature/
    │       ├── dashboard/       ← Lists Dashboard screen + ViewModel
    │       ├── listdetail/      ← List Detail screen + ViewModel
    │       ├── createlist/      ← Create/Edit List modal + ViewModel
    │       └── itemdetail/      ← Edit Item screen + ViewModel
    ├── androidMain/         ← MainActivity, Application, photo picker + storage actuals, Room driver actual
    ├── iosMain/             ← MainViewController, photo picker (PHPicker) + storage actuals, Room driver actual
    └── commonTest/          ← unit tests for ViewModel/repository logic (kotlin.test)
iosApp/                          ← minimal Xcode project: SwiftUI App wrapping MainViewController
```

Single shared module + thin `iosApp`. No multi-module Gradle graph, no Konsist/detekt/ktlint gates. A small set of common unit tests for ViewModel logic (with fake repositories) is welcome; **no snapshot tests, no UI tests, no CI**.

## Design system (dark-mode only)

Implement a Compose theme with **dark mode only** (ignore system setting — always dark). Default sans font is fine (bundle Inter via compose-resources only if trivial). Edge-to-edge on Android with transparent status bar and light icons; on iOS let the Compose content extend into the safe areas the same way.

Color tokens:
- `background` `#101822` (app base)
- `surface` `#1E2632` (cards, search field, inputs)
- `textPrimary` `#FFFFFF`
- `textMuted` `#9DA8B9`
- `primaryBlue` `#2B7CEE` (FAB, active states, primary buttons)
- Semantic accents (used **only** for list/category icon tints): orange `#F97316`, emerald `#10B981`, rose `#F43F5E`, indigo `#6366F1`

Type scale (Inter-equivalent): display-lg 32/700, title-md 18/600, body-md 16/400, label-sm 14/400, caption-xs 10/500.

Shape: cards & list items & search use **12dp** rounded corners; FAB is a perfect circle; small icon containers 10dp or circular.

Spacing: 16dp horizontal container padding, 8dp/12dp vertical rhythm, 4dp gap between list rows.

Aesthetic: calm, corporate/modern, depth via surface luminosity (not heavy shadows). FAB gets a primary-tinted elevation shadow. Material Icons (the `material-icons` set available to Compose Multiplatform), outlined style, 24dp.

> Implementation note (learned from the Android build): rows that sit on top of a swipe-to-delete background must use **opaque** colors — if a "completed" row is rendered with a translucent surface (e.g. `Surface @ 50% alpha`), the red dismiss background bleeds through. Composite the alpha over the background color (`color.copy(alpha = .5f).compositeOver(background)`) instead.

## Data model (Room, all in commonMain)

**List** (`list_table`): `id: String (UUID, PK)`, `name`, `icon` (enum name, e.g. `CART`), `color` (token key, e.g. `PRIMARY_BLUE`), `sortOrder: Double` (new lists append), `createdAt: Long`, `updatedAt: Long`, `deletedAt: Long?` (nullable — soft delete for undo).

**Item** (`item_table`): `id: String (UUID, PK)`, `listId` (FK → list, cascade), `title`, `description: String?`, `isCompleted: Boolean`, `photoPath: String?` (path of picked photo copied into app storage), `sortOrder: Double`, `createdAt`, `updatedAt`, `deletedAt: Long?`.

Use **soft delete** (`deletedAt`) only to support 5-second undo; all queries filter `WHERE deletedAt IS NULL`. Purge rows whose `deletedAt` is older than a minute when the dashboard loads. (No reminders/photos tables — `photoPath` lives on the item.)

Dashboard needs per-list counts: total items and completed items (subquery or join), plus last-updated time.

## Screens & functional requirements

Define an enum of **8 list icons** (CART, TRAVEL, WORK, HOME, GIFT, FOOD, FITNESS, STAR) mapped to Material icons, and **6 color tokens** (primary blue + the four accents + one more, your choice — sky `#38BDF8` worked well). These drive the icon/color pickers.

### 1. Lists Dashboard (start destination)
- Header row: avatar placeholder (leading) + settings icon (trailing, inert in v1) + **debug-only seed button**.
- Title "My Lists" (display-lg).
- **Search field** (full-width): filters lists by name substring (case-insensitive), live.
- Lists in a `LazyColumn`:
  - **Empty state**: "No lists yet — tap + to create one".
  - **Row**: 56dp colored leading icon container (icon's color token @ ~20% bg, full-color icon), title (title-md), subtitle (label-sm muted): `"No items yet"` if 0 items, else `"{n} items · {x}% completed"` when partially done, else `"{n} items"`. Trailing chevron.
  - **Swipe-to-delete** with a **5-second undo snackbar** (soft-delete; undo restores).
  - Tap row → List Detail.
- **Center-docked circular FAB** (64dp) → Create List modal.

### 2. List Detail
- Top bar: "‹ Lists" back button, centered list name, ⋯ overflow menu (Edit list details → Create/Edit modal in edit mode; Delete list; Clear completed).
- **Completion header**: "LIST COMPLETION" caption + `{completed}/{total}` + thin progress bar (primary blue fill).
- Items grouped into **TO BUY** and **COMPLETED** sections:
  - Active item: outlined radio circle, title, chevron. Tap radio → toggle complete (optimistic, animate move to COMPLETED).
  - Completed item: filled blue check circle, strikethrough muted title (opaque composited background — see note above). Tap radio → un-complete.
  - Tap row body → Edit Item screen.
  - Swipe-to-delete on items → optimistic remove + 5s undo snackbar.
  - "Hide/Show" toggle on the COMPLETED section header (session-only).
- **Inline composer** docked at bottom above keyboard: text field ("+ Add new item…") + circular submit button (enabled only when non-blank). IME send also submits.
- Empty state when both sections empty.

### 3. Create / Edit List (full-screen modal)
- Same screen serves create and edit (optional `editingId` nav arg).
- Top bar: "Cancel" (left, dirty-check → confirm-discard dialog), centered "New List"/"Edit List".
- Form: **LIST NAME** text field (single-line, max 60, inline validation); **CHOOSE ICON** 4-column grid of the 8 icon chips (give the grid enough fixed height that the second row isn't clipped — ~200dp); **LIST COLOR** horizontal row of 6 swatches; selected icon/color highlighted.
- Bottom: full-width primary "Create List"/"Save" button, disabled until valid.
- Create → dismiss + navigate into the new list. Edit → save + dismiss.

### 4. Edit Item (full-screen modal)
- Top bar: "‹ {list name}" back, centered "Edit Item", "Save" text button (right, disabled unless dirty & not saving).
- Form: **ITEM NAME** (single-line, max 120), **DESCRIPTION** (multiline, max 2000).
- **Item Photo** section with an "Update" action: opens the **system photo picker only** (via the `PhotoPicker` expect/actual). Picked image is copied into app-internal storage and its path stored on the item; render it in a 16:9 rounded card with crop (decode from the stored file — no image-loading library needed for a single local file, but Coil 3 Multiplatform is acceptable if decoding by hand fights you on iOS). Empty state inside card ("No photo yet") when none; "Remove photo" option when present. Persists across restarts.
- Full-width destructive "Delete Item" button (rose outlined) with confirm.
- "Last edited on {Mon DD, YYYY}" caption at bottom.

## Deliverables

1. A compiling KMP project: Gradle KDSL, `libs.versions.toml`, Koin + Room KMP + Compose Multiplatform + Navigation wired; `iosApp` Xcode project that builds and runs the shared UI.
2. The 4 screens above fully functional end-to-end against Room (offline) **on both platforms**, with the design tokens applied.
3. A **debug-only "seed sample data"** affordance on the dashboard to populate a few lists/items (e.g. Supermarket/Home To-Do/Trip to Japan/Gift Ideas/Work Q4 Goals, with one partially-completed list to demo the `x% completed` subtitle).
4. A short `README.md`: how to build/run both targets (`./gradlew :composeApp:installDebug`; open `iosApp` in Xcode or use the run gradle task), the package layout, and an explicit "Out of scope for v1" list (analytics, camera, reminders/notifications, CI, sync/networking).

Verify as you go: build the Android target first (fastest feedback loop), run it on an emulator and visually compare each screen against the PNGs, then bring up the iOS target. Start by scaffolding the project and version catalog (the JetBrains KMP wizard / `kmp.jetbrains.com` template or the `android create` CLI is a fine starting skeleton — but replace its versions with a known-good set), then build bottom-up: theme → Room/data → repositories → ViewModels → screens (dashboard first). Keep commits granular. Ask me only if a genuine product decision is ambiguous; otherwise pick the simplest reasonable option and proceed.
