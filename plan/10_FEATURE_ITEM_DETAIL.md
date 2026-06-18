# Phase 10 — Feature: Item Detail (Edit + Photo)

> **Goal:** Ship the "Edit Item" screen on Android (Compose) and iOS (SwiftUI), wired to `ItemDetailStore` (Phase 05). This is the most platform-heavy feature: it consumes the camera, the photo library picker, and the sandboxed photo storage from Phase 06's `PhotoCapture` + `PhotoStorage` ports, with permission flows that must degrade gracefully.

**Owner:** Mobile platform (Android + iOS pair)
**Depends on:** Phases 02, 03, 04, 05 (`ItemDetailStore`), 06 (`PhotoCapture`, `PhotoStorage`, `PermissionRequester`), 08 (entry point: tap item row from list detail).
**Blocks:** —
**Exit criteria (Definition of Done):**
- Open item → edit name/description → save → return to list with changes reflected.
- Capture photo from camera AND pick from library on both platforms; persists across app restart.
- Permission denied → user sees actionable explanation with "Open Settings" deep link, no crash.
- Process-death restoration: unsaved edits + selected-but-unsaved photo survive activity recreation / scene restoration.
- All design-system primitives — Konsist literal-ban green.
- Snapshot tests checked in for: loading, loaded-no-photo, loaded-with-photo, dirty-state, capture-in-progress, permission-denied banner, delete-confirm.

---

## 0. Slice plan & cadence

Phase 10 ships one `feat` commit per slice (plan file synced in-commit, impl-log
entry `_Commit `<pending>`._`) + a `docs(plan):` SHA-backfill commit, per the Phase
05–09 cadence. Pre-commit gate: `:check` of each touched module + `:build-logic:test
--rerun-tasks`; iOS-facing slices also run `scripts/test-ios.sh` (must print
`** TEST SUCCEEDED **`). Kover gates `:shared:state` (≥90%) when the store changes.

**Decisions taken at kickoff (2026-06-18):**

(a) **`ItemDetailStore` already ships the full edit/photo/delete contract** (built ahead
of this phase; 36 store-test cases green) — **unlike** Phase 09, where the create-only
`CreateListStore` needed an edit-mode backfill. So Slice 1 is a **thin** store backfill,
not a rewrite: add a `submitting` save-in-flight flag (Save disabled + "Saving…" label
while in flight — **no in-button spinner**, carrying the Phase 09 DS debt) and
title-validation/cap-120 gating (§2/§5), then grow `ItemDetailStoreTest`. If review finds
the shipped contract already adequate for the UI, Slice 1 collapses to the DI/resolver
facade only.

(b) **Photo capture uses the simple system UI already shipped in Phase 06 — no CameraX,
no custom preview.** Android = `ActivityResultContracts.TakePicture()` (system camera
Intent) + `PickVisualMedia`; iOS = `UIImagePickerController` for both camera and library.
Phase 10 only *drives* the Phase 06 `PhotoCapture` port; it builds no capture surface of
its own. **Permission UX is thin (diverges from §4):** the domain `AttachPhotoToItem`
surfaces a flat `CaptureError.PermissionDenied` with **no** soft-vs-hard
(`canRequestAgain`) distinction, so §4's soft/hard banner split is **not** implementable
without a domain change — deferred to v2. Moreover, Android's `TakePicture()` delegates
camera permission entirely to the system camera app, so `AndroidPhotoCapture` **never
returns `PermissionDenied` for the camera path** (only `UserCancelled`/`Unknown`) — the
in-screen permission banner is effectively **iOS-camera-only** and is a no-op on Android.
v1 ships one banner ("… access is off — Open Settings") inside the photo section, driven
by the existing `RequestCameraPermission` / `RequestPhotoLibraryAccess` effects. Logged
as a §13 divergence.

(c) **`PhotoStatus.Uploading` stays unreachable** (already documented in the store KDoc —
`AttachPhotoToItem` is atomic capture→ingest→attach). The whole acquire span shows the
single `Capturing` busy state, so §3's `Capturing → Uploading → Loaded` sequence collapses
to `Capturing → Loaded`.

(d) **`dirty` is a boolean flag** set on any title/description/photo change, not §5's
per-field `editing != original` comparison (the shipped store derives it eagerly). A
type-and-retype back to the original value still reads dirty → back shows the discard
confirm. Document as a §13 divergence; per-field derivation deferred.

(e) **Snapshot tests deferred to v2** (standing decision, Phase 08/09 §0) — §14's snapshot
list is replaced by store tests + previews + Konsist + exhaustive `when`/`switch`.

(f) **Process-death restoration (§7) is host-only and outside v1's automated gate** — the
store reloads via `Init(itemId)` and re-syncs the working copy while not dirty; the §7
`SavedStateHandle` / `@SceneStorage` edit-overlay persistence is a nice-to-have left to
the on-device QA pass (snapshot/restore not automated, per the standing v2 deferral).

1. **Store backfill + DI/resolver** (`:shared:state`) — `submitting` flag threaded through
   `save()` (Save gating + "Saving…" label), title validation/cap 120 (§2/§5);
   `resolveItemDetailStore()` facade in `InitKoin.kt` + the SKIE accessors iOS needs to
   pass the `ItemId` value-class through `Init` (string accessor in `IosEffectIds.kt` if
   the boxed id won't surface); `ItemDetailStoreTest` grows to cover the new gating (Kover
   ≥90% stays green). Gate: `:shared:state:check` + `:build-logic:test`.
2. **Android `:features:feature-item-detail` module + nav** — `ItemDetailRoute` /
   `ItemDetailScreen` / `PhotoSection` / `PermissionBanner` / `ItemDetailComponents` /
   `ItemDetailPreviews` (§8); **both** placeholder routes (`list/{listId}/item/{itemId}`
   and the `item/{itemId}` deep link) in `FluxItRoot` swap from `Placeholder` to the real
   screen; PhotoCapture / PermissionRequester glue via the Phase 06 ports (no direct
   `androidx.activity.result.*` import — §11); Coil 3 `AsyncImage` render (§12). Gate:
   `:features:feature-item-detail:check` + `:build-logic:test`.
3. **iOS ItemDetail screen + wiring** — `ItemDetailView` / `PhotoSection` /
   `PermissionBanner` / `ItemDetailFormSections` / `ItemDetailPreviews` (§8); the
   `.itemDetail(id)` case in `ContentView` swaps from `PlaceholderView` to the real screen
   on both nav stacks; `UIImagePickerController` via the port (no direct UIKit import —
   §11); Kingfisher / `AsyncImage` render (§12). Gate: `:shared:state:check` (if DI
   touched) + `scripts/test-ios.sh` (`** TEST SUCCEEDED **`).
4. **Close-out** — `MASTER_PLAN.md` → Phase 10 🟢, M4 (Core User Surfaces) **complete**,
   ▶ Next Step → Phase 13 (Phases 11/12 deferred per ADR-004/ADR-003); §13 mockup
   divergences + (a)–(d) above signed off; §16 hand-off; reconcile §14/§15.

---

## 1. Screen anatomy

```
FluxItScaffold (modal-detail variant — no tab bar, no FAB)
├── Top bar (FluxItTopBar variant B with text-button trailing)
│   ├── Leading: text button "‹ {list name}" (primary.blue, body.md, ellipsize at 12 chars)
│   ├── Center: "Edit Item" (title.md, white)
│   └── Trailing: text button "Save" (primary.blue, semibold; disabled when !dirty || saving)
├── Form (vertical stack, 16dp horizontal, scrollable)
│   ├── Section header "General Info" (title.md white, 24dp top)
│   │   ├── ITEM NAME label (caption.xs uppercase muted)
│   │   ├── FluxItTextField single-line, maxLength = 120
│   │   ├── DESCRIPTION label (caption.xs uppercase muted)
│   │   └── FluxItTextField multi-line (4-line min, 12-line max), maxLength = 2000
│   ├── Section header "Item Photo" (title.md white, 24dp top)
│   │   └── Trailing aligned: 📷 icon + "Update" text button (primary.blue)
│   ├── Photo card
│   │   ├── If photo present: 16:9 aspect, FluxItCard, rounded-xl, bg = surface.card, image fills with `contentScale = Crop`
│   │   ├── If no photo: empty state inside the card — camera icon + "No photo yet" + tap-to-add affordance
│   │   ├── If capturing: skeleton + spinner
│   │   └── If error: inline error banner with retry
│   ├── FluxItDestructiveButton "Delete Item" (full-width, 24dp top, rose-tinted outlined)
│   └── "Last edited on {Mon DD, YYYY}" (caption.xs muted, 16dp top, centered)
```

## 2. Form bindings → store intents

| Field | Intent | State path |
|---|---|---|
| Name TextField onChange | `TitleChanged(text)` | `state.editing.title`, `state.dirty` |
| Description TextField onChange | `DescriptionChanged(text)` | `state.editing.description` |
| "Update" tap (or photo card tap when empty) | `UpdatePhotoClicked` | (effect: `ShowPhotoSourceSheet`) |
| Photo card tap when populated | `UpdatePhotoClicked` | same — replacing is the same action |
| Action sheet "Take photo" | `PhotoSourceSelected(Camera)` | triggers `AttachPhotoToItem(Camera)` |
| Action sheet "Choose from library" | `PhotoSourceSelected(Library)` | triggers `AttachPhotoToItem(Library)` |
| Action sheet "Remove photo" (only if photo present) | `RemovePhotoClicked` | clears `editing.photoId` (dirty) |
| Save tap | `SaveClicked` | `state.submission` |
| Delete tap | `DeleteClicked` | `state.confirmDelete = true` |
| Confirm-delete tap | `ConfirmDelete` | calls `DeleteItem`, navigates back with undo |
| Cancel-delete tap | `CancelDelete` | `state.confirmDelete = false` |
| Back gesture | `BackClicked` | (effect: `NavigateBack` or `ConfirmDiscardChanges`) |

## 3. Photo flows

### Capture (camera)

- [ ] On `PhotoSourceSelected(Camera)`:
  - Call `PermissionRequester.ensure(Camera)`. If `PermanentlyDenied` → emit `Effect.ShowPermissionBanner(Camera)` (in-screen banner with "Open Settings" CTA). Stop.
  - If `Granted` → set `photoStatus = Capturing` → call `PhotoCapture.capture()`.
  - On success → set `photoStatus = Uploading` → call `PhotoEncoder.reencode()` (JPEG 0.85, max 2048px per Phase 06 §6) → `PhotosRepository.ingest()` → set `editing.photoId = newId` → set `photoStatus = Loaded(uri)` → mark dirty.
  - On `CaptureError.UserCancelled` → revert to previous `photoStatus`.
  - On any other error → set `photoStatus = Error(message)` with retry button.

### Pick from library

- [ ] On `PhotoSourceSelected(Library)`:
  - **iOS**: PHPicker doesn't require permission (returns ephemeral results). Skip permission check; go straight to `PhotoCapture.pickFromLibrary()`.
  - **Android**: `PickVisualMedia` (photo picker) requires no permission either since API 33; skip permission check.
  - Same `Capturing → Uploading → Loaded` sequence as camera.

### Remove

- [ ] On `RemovePhotoClicked`:
  - Optimistically: clear `editing.photoId`, set `photoStatus = None`, mark dirty.
  - **No file deletion at this point** — the photo file remains until `SaveClicked` commits and `PhotoJanitor` (Phase 04 §7) reclaims orphaned files. If user cancels (back without save), the original photo is restored from disk on the next render via `editing.photoId` revert.

### Replace

- [ ] No special handling — replace = remove + new pick. The previous `photoId` is dereferenced from the item; janitor reclaims the file later.

### Crash safety while capturing

- [ ] If process dies during `Uploading`, the temp file may exist but the item row is unchanged (since we only update `item.photo_id` after `ingest`). On next launch, `PhotoJanitor` finds the orphaned file and deletes it. No bookkeeping needed in this screen.

## 4. Permission UX

- [ ] **First-ask flow**: action sheet for source → `Camera` selected → permission requester pops the system dialog → on `Granted`, capture proceeds.
- [ ] **Soft-denied** (`Denied(canRequestAgain = true)`): inline error banner inside the photo section, "Allow camera access to take photos.", with a "Try Again" button that re-requests.
- [ ] **Hard-denied** (`PermanentlyDenied`): banner copy "Camera access is off. Enable it in Settings." with "Open Settings" button → deep link to app settings page on both platforms.
- [ ] Banner uses `surface.card` + `accent.rose` left border, body.md white text. Lives **inside the Item Photo section**, not as a top-level snackbar (contextual placement).
- [ ] Permission state observed on screen resume — if user enables in Settings and returns, banner dismisses automatically.

## 5. Save / dirty / discard

- [ ] `state.dirty` derivation: any of `editing.title != original.title`, `editing.description != original.description`, `editing.photoId != original.photoId`.
- [ ] **Save button** disabled when `!dirty || submitting || titleValidation != Valid`.
- [ ] On `SaveClicked`:
  - Set `submitting = true`; Save label cross-fades to spinner.
  - Call `UpdateItemDetails(itemId, ItemPatch(...))`.
  - On success → emit `Effect.NavigateBack`. The list-detail re-observes via `ObserveListDetail` and shows the updated row.
  - On failure → `submitting = false`, error banner, stay on screen.
- [ ] **Back with `dirty = true`** → emit `Effect.ConfirmDiscardChanges` → UI shows alert ("Discard changes? — Discard / Keep editing"). On Discard → `Effect.NavigateBack`. On Keep → no-op.
- [ ] Process death: see §7.

## 6. Delete

- [ ] `DeleteClicked` → `state.confirmDelete = true` → UI renders an alert/sheet "Delete '{title}'? This can't be undone for 5 seconds." with destructive "Delete" + "Cancel".
- [ ] On `ConfirmDelete` → call `DeleteItem(id)` → emit `Effect.NavigateBackWithUndo(deletedItem)` → list-detail picks up the pending-delete via `RootStore` glue (analogous to Phase 08 §6 cross-screen undo for list delete).
- [ ] If user is in the middle of editing when they delete: discard the pending edits silently (delete supersedes).

## 7. Process-death restoration

- [ ] **Title + description**: persisted via Android `SavedStateHandle["edit:item:{id}:title"]` + `"...:description"`; iOS `@SceneStorage("edit:item:{id}")` JSON blob.
- [ ] **Photo selection**: persist `editing.photoId` (the new id, if user picked but hasn't saved). The photo *file* is already on disk from `PhotosRepository.ingest`. On restore, the photo row is still in DB; UI re-renders from the persisted photoId.
- [ ] **`photoStatus = Capturing` or `Uploading`**: NOT persisted. If process dies mid-capture, the picker UI is gone (it was system-owned); on restore, the screen returns to its previous photo state and the user re-initiates.
- [ ] **`confirmDelete` dialog state**: NOT persisted (transient).
- [ ] On restore, store re-emits `Init(itemId)` which loads original from DB; persisted edits overlay onto `editing` field.

## 8. File layout

### Android

```
:features:feature-item-detail/src/androidMain/kotlin/com/fluxit/itemdetail/
  ItemDetailRoute.kt           ← Koin/ViewModel glue, observes store, handles SavedStateHandle
  ItemDetailScreen.kt          ← stateless, takes (state, onIntent)
  PhotoSection.kt              ← photo card variants (loaded / empty / capturing / error)
  PermissionBanner.kt          ← in-screen permission affordance
  ItemDetailComponents.kt      ← form sections, delete button, last-edited footer
  ItemDetailPreviews.kt
```

### iOS

```
ios-app/Features/ItemDetail/
  ItemDetailView.swift
  PhotoSection.swift
  PermissionBanner.swift
  ItemDetailFormSections.swift
  ItemDetailPreviews.swift
```

## 9. Animations

- [ ] Photo card → `Loaded` transition: 200ms cross-fade from skeleton/empty.
- [ ] Action sheet (photo source): platform-default sheet animation.
- [ ] Save button → spinner: 80ms cross-fade.
- [ ] Permission banner: 200ms slide-down from photo section header.
- [ ] Reduce-motion: instant transitions.

## 10. Accessibility

- [ ] Name field labelled "Item name", description labelled "Description".
- [ ] Photo card:
  - With photo: "Photo of {item title}, double-tap to update."
  - Without photo: "No photo. Double-tap to add one."
- [ ] "Update" button labelled "Update photo".
- [ ] Action sheet items: standard platform a11y.
- [ ] Permission banner: announced via live region on appearance.
- [ ] Save button announces enabled/disabled state.
- [ ] Delete button uses destructive trait.
- [ ] "Last edited on …" exposed as static label, not interactive.

## 11. Konsist rules (additions)

- [ ] `feature-item-detail` cannot depend on other feature modules.
- [ ] No raw literals.
- [ ] Direct imports of `androidx.activity.result.*`, `UIKit.UIImagePickerController`, etc. are forbidden — must go through `PhotoCapture` port (Phase 06).

## 12. Image rendering

- [ ] **Android**: Coil 3 with `AsyncImage(model = photoStorage.resolveAbsolute(relPath))`. Pre-warm with `ImageLoader.execute()` on `Init` so the photo is decoded before the section is scrolled into view.
- [ ] **iOS**: Kingfisher (or `AsyncImage` from SwiftUI) with the same absolute path. Cache by `photoId` (immutable).
- [ ] Both use the photo's known `widthPx`/`heightPx` (from DB) to reserve aspect-ratio space before the bitmap loads — prevents layout jank.
- [ ] Memory: cap decoded size to display-pixel dimensions × 1x; we don't need original-resolution decoding.

## 13. Mockup divergences (for design review)

- [ ] **Photo "Update" affordance is duplicated** — the section header has a "📷 Update" text button, AND tapping the photo card itself also opens the action sheet. Mockup only shows the header button. We're adding tap-on-card because it's the more discoverable affordance on touch. Flag for design.
- [ ] **"Remove photo" appears in the action sheet** when a photo is present. Mockup doesn't show a remove affordance at all. Flag for design — alternative: long-press the photo card.
- [ ] **Delete confirmation dialog vs. swipe** — we use a confirmation dialog here (single-item destructive, no swipe surface in a form), not the swipe-with-undo pattern from list rows. Document.

## 14. Testing

- [ ] **Snapshot tests**: loading, loaded-no-photo, loaded-with-photo, dirty-state (Save enabled), capturing (skeleton), permission-denied-soft (banner + Try Again), permission-denied-hard (banner + Open Settings), delete-confirm dialog, error-state, edit-name-typed.
- [ ] **UI behavior**:
  - Type name → Save enables.
  - Pick from library (fake `PhotoCapture` returning canned bytes) → photo renders.
  - Capture from camera → permission granted → photo renders.
  - Capture with permission denied (soft) → banner; tap Try Again → retries.
  - Capture with permission denied (hard) → banner; tap Open Settings → assertion that intent / `UIApplication.openSettingsURLString` was invoked (verified via fake).
  - Save success → navigates back; list detail row updated.
  - Back with dirty → confirm dialog; discard → back; keep → stay.
  - Delete → confirm → navigate back with pending-delete snackbar on list detail.
  - Process death restoration: simulate via `ActivityScenario.recreate()` / iOS scene-restore launch arg; assert title/description/photoId restored.
- [ ] **Effect mapping**: exhaustive test.
- [ ] **A11y audit** TalkBack + VoiceOver.
- [ ] **Manual QA on real devices**: Pixel 6a (camera + library), iPhone 12 mini (camera + library), iPhone SE 1st gen (smallest supported screen — verify form scrolls, photo card doesn't overflow).

## 15. Resolved decisions for this phase (2026-05-11)

- ✅ **Description: plain text only in v1.** Multi-line `FluxItTextField`, no markdown, no auto-link detection. Rich-text / linkification deferred to v2.
- ✅ **Photo count: one per item.** Schema already enforces via `item.photo_id` single column. UI copy stays singular ("Photo", "Update photo", "Remove photo"). Multi-photo deferred to v2.
- ✅ **No share / save / copy photo actions in v1.** No long-press menu on the photo card; no share sheet integration. Tap (or "Update") opens the source action sheet only. Defers FileProvider grant setup (Android) and `UIActivityViewController` wiring (iOS) to v2.
- ✅ **Save model: explicit Save button.** Matches mockup. Disabled until `dirty && valid && !submitting`. Back-with-dirty shows discard confirm dialog. Aligns with Phase 09's create/edit semantics for predictable behavior across the form pair.
- ✅ **Item delete undo: 5 seconds.** Same as list delete (Phase 07/08). Users learn one timing.

### Implications

- Photo section action sheet items in v1: **"Take Photo" / "Choose from Library" / "Remove Photo" (when present) / "Cancel"** — three actions, no Share, no Save to Library.
- §3 "Remove" flow stays as currently specified.
- The mockup divergence list in §13 unchanged — already noted the action sheet items and the photo-card tap affordance.

## 16. Hand-off checklist (gate to Phase 11/13)

- [ ] All checkboxes above ✅.
- [ ] Both apps demoed: open item → edit name/description → take photo → save → returns to list updated. Then: open item → tap delete → confirm → returns with undo snackbar.
- [ ] Permission flows tested on real devices (deny soft, deny hard, recover via Settings).
- [ ] Snapshot tests checked in; CI golden compare green.
- [ ] A11y audit clean.
- [ ] Mockup divergences (§13) signed off by design.
- [ ] `MASTER_PLAN.md`: Phase 10 → 🟢, M4 (Core User Surfaces) **complete** — advance to M3/M5 work; ▶ Next Step → Phase 13 (notifications + reminders editor) since Phases 11 & 12 are deferred to v2 per ADR-004 / ADR-003.

---

## 18. Implementation log

> One entry per slice (see §0). Each `feat` commit lands its entry with the SHA left as
> `_Commit `8b527f5`._`, then a follow-up `docs(plan):` commit backfills the SHA.

### Slice 1 — store backfill + DI/resolver

`ItemDetailStore` was already feature-complete from an earlier phase (18 store-test
cases), so this was the thin backfill decision (a) called for — no rewrite:

- **`submitting` flag** threaded through `save()`: set true on entry, cleared on both
  success and failure; success additionally clears `dirty` and emits `NavigateBack`.
  Save now no-ops on re-entry while a save is in flight.
- **Title validation** — new `titleValidation: NameValidation` (reuses the enum shared
  with `CreateListStore`), recomputed on every `TitleChanged` and on the prefill sync
  (only while not dirty). `validateTitle` caps at `TITLE_MAX_LEN = 120` (§2). `save()`
  also guards server-side: an `!= Valid` title never persists even if the host's
  button-disable lags.
- **DI/resolver** — `resolveItemDetailStore()` in `InitKoin.kt` (no-param factory; the
  item id arrives later via `Init`) + `itemIdOf(value:)` in `IosEffectIds.kt` so Swift
  can build the `ItemId` value class for `ItemDetailIntent.Init` (mirrors `listIdOf`).
- **Tests** — 6 new cases (valid/empty/too-long title, invalid-save no-op, submitting
  cleared on success + on failure); 24 total, Kover ≥90% green.

Gate: `:shared:state:check` + `:build-logic:test --rerun-tasks` green.

_Commit `8b527f5`._

### Slice 2 — Android `:features:feature-item-detail` + nav

New Compose feature module (`fluxit.kmp.feature` + compose), mirroring
`feature-create-list`: `ItemDetailRoute` (Koin/VM glue + effect→chrome mapping),
`ItemDetailViewModel` (store in `viewModelScope`, dispatches `Init`),
`ItemDetailScreen` (stateless), `PhotoSection`, `PermissionBanner`,
`ItemDetailComponents` (form sections + formatters + delete + footer), and
`ItemDetailPreviews` (the §14 snapshot matrix as previews). Both placeholder routes
in `FluxItRoot` (`list/{listId}/item/{itemId}` + the `item/{itemId}` deep link) now
render the real screen; the unused `Placeholder` composable was removed.

- **Camera/picker need no feature-module glue** — `MainActivity` already attaches
  the Phase 06 `ActivityResultRegistryProvider`, and `AttachPhotoToItem` orchestrates
  capture→ingest→attach, so the UI just dispatches `PhotoSourceSelected` and renders
  `photoStatus`. No `androidx.activity.result.*` import here (§11 / Konsist green).
- **Photo render** uses a `BitmapFactory.decodeFile` → `ImageBitmap` on `Dispatchers.IO`
  (§12 divergence: Coil 3 + pre-warm/caching deferred to v2 — avoids a new dependency,
  keeps the slice self-contained; one photo per item, decoded once per uri).
- **Store touch-ups discovered during UI integration:** added
  `ItemDetailIntent.PhotoSourceSheetDismissed` and made `RemovePhotoClicked` close the
  sheet, so the state-driven action sheet is fully controllable (2 new store tests).
  Also made the `ItemDetailStore` Koin factory accept an optional `CoroutineScope` so
  the ViewModel passes `viewModelScope` (no-arg `resolveItemDetailStore()` unaffected).

**Divergences logged:** Save lives in a sticky bottom dock (`FluxItPrimaryButton`),
not a top-bar text trailing — the DS centered top bar has only a disabled-less *icon*
trailing (mirrors Phase 09's `SubmitDock`, §1). Item delete emits a plain `NavigateBack`
(no undo) — the store has no `NavigateBackWithUndo`, so §6's 5-second item-undo is not
wired in v1. Permission banner is iOS-camera-only per §0 (b) (no-op on Android). Photo
card tap opens the sheet (§13 — kept).

Gate: `:features:feature-item-detail:check` + `:shared:state:check` + `:build-logic:test`
+ `:android-app:compileDebugKotlin` green.

_Commit `<pending>`._

### Slice 3 — iOS ItemDetail screen + wiring

_Commit `<pending>`._

### Slice 4 — close-out

_Commit `<pending>`._
