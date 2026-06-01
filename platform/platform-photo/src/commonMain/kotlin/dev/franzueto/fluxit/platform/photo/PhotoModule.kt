package dev.franzueto.fluxit.platform.photo

import org.koin.core.module.Module

/**
 * Koin module for `:platform:platform-photo` (plan/06 §6, §7, §8). Binds the
 * production [PhotoStorage][dev.franzueto.fluxit.shared.domain.port.PhotoStorage],
 * [PhotoCapture][dev.franzueto.fluxit.shared.domain.port.PhotoCapture], and
 * [PhotoEncoder] actuals — `AndroidPhotoStorage`/`AndroidPhotoEncoder`/`AndroidPhotoCapture`
 * on Android, `IosPhotoStorage`/`IosPhotoEncoder`/`IosPhotoCapture` on iOS.
 *
 * `expect`/`actual` rather than a single common `module { }` because the Android
 * bindings need an `androidContext()` (Koin-Android) plus an `ActivityResultRegistry`
 * host-holder (§7), and the iOS bindings need a `TopViewControllerProvider` — neither
 * is expressible in commonMain. Replaces the interim `NoOp` photo bindings in
 * `:shared:state`'s `InterimPlatformModule` (Slice 6).
 */
public expect fun photoModule(): Module
