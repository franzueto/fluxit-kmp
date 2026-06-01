# :platform:platform-photo

Per-platform implementations of the `PhotoStorage`, `PhotoCapture`, and
`PhotoEncoder` ports (plan/06 §6/§7). `photoModule()` (expect/actual) binds them.

## The "I need an Activity / UIViewController" problem (§7 host-holder)

`PhotoCapture` must present system UI (camera, picker) but the domain port can't
carry a UI host. The pattern adopted here — reuse it for any future capability that
needs a host (file picker, share sheet):

- **Android** — `AndroidPhotoCapture` consumes an app-scoped
  `ActivityResultRegistryProvider`. The composition-root Activity calls
  `attach(activityResultRegistry)` on resume and `detach(...)` on pause. Capture
  suspends until a registry is present (5s timeout → `CaptureError.Unknown`), then
  drives `ActivityResultContracts.TakePicture` / `PickVisualMedia` via a
  continuation. No CAMERA runtime permission is needed (the system camera owns its
  own); the full-resolution capture is written to a cache temp file exposed through
  a `FileProvider` (`${applicationId}.fileprovider`, declared in this module's
  manifest), read back, and deleted.
- **iOS** — `IosPhotoCapture` consumes a `TopViewControllerProvider`; the default
  walks the key window's presented-controller chain, so no app-side wiring is
  needed. v1 uses `UIImagePickerController` for both camera and library (PHPicker
  is a follow-up). `NSCameraUsageDescription` / `NSPhotoLibraryUsageDescription`
  must be in the app `Info.plist`.

## Storage

Root `filesDir/photos` (Android) / `applicationSupport/photos` (iOS). Writes are
atomic; paths are sandbox-relative (`photos/<uuid>.<ext>`). `resolveAbsolute`
returns an absolute **file path** in v1 — a `content://` FileProvider URI for
*storage* (sharing a photo to an external app) is deferred until sharing lands.
The capture FileProvider above is a separate, narrower concern (camera temp file).

## Backup asymmetry (§10)

Android photos are excluded from auto-backup in v1 (ADR-009b); iOS photos are left
in the default iCloud backup so an iCloud restore brings them back.
