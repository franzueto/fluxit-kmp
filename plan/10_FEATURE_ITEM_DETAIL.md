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
