# Phase 17 — Release Hardening

> **Goal:** Take the technically-complete app from end of Phase 16 and make it **shippable**: accessibility audit, performance gates, R8 + bitcode tuning, store-listing assets, privacy policy, app-store metadata, security review, internationalization minimums, and a launch-readiness checklist that goes from "code is ready" to "humans can install this from the store."

**Owner:** Mobile platform + product (for store copy and screenshots)
**Depends on:** ALL prior phases. This is the final v1 phase.
**Blocks:** Public release (Play Production track / App Store production).
**Exit criteria (Definition of Done):**
- v1.0.0 candidate passes the launch-readiness checklist (§16) end-to-end on real devices.
- Both stores' listings published in draft state with all required assets, privacy declarations, and content ratings.
- Crash-free rate ≥ 99.5% across a 7-day TestFlight + Play Internal track soak.
- Perf budgets (§3) green on the perf-bench device matrix for the release build.
- Privacy policy + terms of service pages live at stable URLs.

---

## 1. Accessibility audit (formal pass)

Per-feature a11y was checked at each phase's hand-off. This is the cumulative, explicit-coverage audit before submission.

- [ ] **TalkBack pass (Android)**: every screen reachable, every interactive element labelled, every state change announced. Recorded as a 5-minute screen capture for the review record.
- [ ] **VoiceOver pass (iOS)**: same.
- [ ] **Switch Control (iOS) + Voice Access (Android)**: dashboard → create list → add item → toggle complete reachable without the touchscreen.
- [ ] **Dynamic type / system font scale**: tested at default + 130% + 200%. Truncation acceptable on labels; never on body content. Long lists still scrollable.
- [ ] **Reduce motion**: animations honor it (collapse to instant transitions). Already implemented per phase; verified end-to-end here.
- [ ] **Color contrast**: text on every surface ≥ WCAG AA (Phase 02 §8 verified at design time; re-verified on real OLED screens with brightness reduced).
- [ ] **Tap targets**: all interactive elements ≥ 48dp (Android) / 44pt (iOS). Verified by automated a11y framework + spot check.
- [ ] **Forms**: labels properly associated; errors announced via live region; submit button state announced.
- [ ] **Outcomes** logged in `docs/A11Y_AUDIT.md` with date, version, findings, and resolution status.
- [ ] **No Critical findings unresolved** at submission time. High findings either fixed or documented with v1.0.1 mitigation plan.

## 2. Internationalization (v1 minimum)

- [ ] **v1 ships English only.** Confirmed; documented in `docs/I18N.md`.
- [ ] **No hardcoded user-facing strings outside `core-designsystem` string registry**. Konsist rule: any `Text("…")` (Compose) / `Text("…")` literal (SwiftUI) outside `:core:core-designsystem/strings/` and outside test/preview source sets fails the build. Allows hard-coded debug labels via `// @SuppressLint("HardcodedString") debug:` comment.
- [ ] **String registry shape**: typed Kotlin `object FluxItStrings { val createList: String = "Create list" }` for v1. v2 swaps to platform string resources with locale loading; the typed-string façade stays.
- [ ] **Number / date formatting** uses `kotlinx.datetime` + `Intl`-equivalent platform helpers, not hand-rolled.
- [ ] **RTL readiness** (zero-cost while the codebase is small): every layout uses logical-edge modifiers (Compose `Modifier.padding(start/end)` not `left/right`; SwiftUI `.leading/.trailing`). Verified by spot-flipping `LayoutDirection` to RTL in a debug-only toggle and walking each screen — no broken layouts.
- [ ] **App store listing copy** authored in English; placeholders for v2 locales noted.

## 3. Performance gates (release build)

Phase 14 §8 sets the budgets; this phase re-runs them on **release** builds (R8 enabled, signing config = play, optimizations on) and **gates** the release on green numbers.

| Metric | Target | Measured on |
|---|---|---|
| Cold start (process start → dashboard first frame) | ≤ 800ms | Pixel 6a |
| Cold start | ≤ 600ms | iPhone 12 mini |
| Dashboard scroll, 50 lists | 60fps sustained, no dropped frames > 16ms | Pixel 6a + iPhone SE 1st gen |
| List detail scroll, 100 items | 60fps sustained | Both |
| Item completion toggle animation | 60fps | Both |
| Photo decode + render | < 200ms p95 from tap to image visible | Both |
| Memory at idle (dashboard, 50 lists) | < 80 MB | Both |
| Install size | < 25 MB (Android `.aab`); < 30 MB (iOS `.ipa`) | — |
| ANR rate (24h after release to internal) | 0 | — |

- [ ] All metrics captured in `docs/PERF_LOG.md` for release builds.
- [ ] Regressions vs. previous release > 10% block release; ≤ 10% require a reasoned acceptance note from the release manager.
- [ ] Use Android Profiler + Xcode Instruments for the cold-start trace; capture at least one CPU + memory profile per release.

## 4. R8 / Proguard (Android) + bitcode (iOS)

### Android

- [ ] R8 enabled in release build; full mode (`android.enableR8.fullMode=true`).
- [ ] Per-module consumer ProGuard rules declared in any module that has reflective access (Koin runtime, kotlinx-serialization). Rules file: `consumer-rules.pro` per module.
- [ ] Aggregated `proguard-rules.pro` in `:android-app` keeps:
  - SQLDelight generated classes (`-keep class app.cash.sqldelight.** { *; }` — refined to actually-needed types).
  - kotlinx-serialization metadata (`-keepattributes Signature, *Annotation*; -keep,includedescriptorclasses class kotlinx.serialization.** { *; }`).
  - Crashlytics annotations.
- [ ] Build with R8 + run the full smoke flow on a real device pre-release. Catches obfuscation breaking reflection.
- [ ] Mapping file uploaded to Crashlytics (Phase 15 §5 wires this).

### iOS

- [ ] Bitcode disabled (Apple deprecated it in Xcode 14; no choice).
- [ ] Strip debug symbols from release binaries; dSYMs uploaded separately to Crashlytics.
- [ ] Optimize for size: `SWIFT_OPTIMIZATION_LEVEL = -Osize` for the App Store config (small wins on icon-heavy app).
- [ ] Whole-module optimization on for release.

### Verification

- [ ] Side-by-side install-size diff before/after R8 noted in `docs/PERF_LOG.md`. Expect 30–50% binary reduction on Android.
- [ ] Manual smoke flow on the obfuscated build:
  - Cold start → seed → create list → add item → toggle → schedule reminder → take photo → save → reminder fires → tap notification → land on list.
  - Any reflection breakage = blocker.

## 5. Store listings

### Google Play Console

- [ ] **App name**: "FluxIt"
- [ ] **Short description** (80 chars): "Beautiful lists for everything. Reminders, photos, and a calm dark UI."
- [ ] **Full description**: TBD with product (1–4 paragraphs). Highlights: list-making, reminders, photos, dark mode, offline-first. Ends with "More features coming."
- [ ] **App icon**: adaptive icon (foreground + background); designed to Phase 02's brand. Generated at all required densities.
- [ ] **Feature graphic**: 1024×500. Designed at brand colors with the FluxIt mark.
- [ ] **Screenshots**: 8 max, ordered by user value:
  1. Dashboard with seeded lists.
  2. List Detail with progress bar.
  3. Composer in action.
  4. Create List form.
  5. Edit Item with photo.
  6. Reminder editor (Weekly recurrence).
  7. Notification on lock screen (mocked).
  8. Empty state ("No lists yet").
- [ ] **Captured on real devices** (Pixel 6a) + framed via Fastlane `frameit` or equivalent.
- [ ] **Categorization**: Productivity > Productivity. Tags: lists, todo, reminders, organization.
- [ ] **Content rating**: Everyone. Submit IARC questionnaire.
- [ ] **Target audience + content**: 13+ (no user accounts, no UGC sharing).
- [ ] **Data safety form**:
  - Data collected: crash diagnostics (anonymous, optional opt-out, NOT linked to user identity).
  - Data shared: none.
  - Encryption in transit: yes (Crashlytics over HTTPS).
  - User can request deletion: yes (clear all data → device-only data deleted; no server data exists).
- [ ] **Privacy policy URL**: stable URL (see §7).
- [ ] **Test track plan**: Internal → Closed (testers list) → Production. v1.0.0 sits on Closed for 7 days minimum before Production promotion.

### App Store Connect

- [ ] **Bundle ID**: `com.fluxit.ios` (per Phase 15 §9).
- [ ] **App name**: "FluxIt"
- [ ] **Subtitle** (30 chars): "Lists, reminders, photos."
- [ ] **Promotional text** (170 chars): editable post-release; first text mirrors Play short description.
- [ ] **Description**: same as Play Full description, formatted for App Store (no Markdown).
- [ ] **Keywords** (100 chars total, comma-separated): "lists,todo,reminders,shopping,tasks,organize,checklist,dark mode,productivity"
- [ ] **App icon**: 1024×1024 (no transparency, no rounded corners — Apple applies them).
- [ ] **Screenshots**: 6.5" (iPhone 14 Pro Max) + 5.5" (iPhone 8 Plus, fallback) + 12.9" iPad (skip if `LSRequiresIPhoneOS = true`).
- [ ] **Categories**: Productivity (primary), Lifestyle (secondary).
- [ ] **Age rating**: 4+.
- [ ] **App privacy**:
  - Data Used to Track You: none.
  - Data Linked to You: none.
  - Data Not Linked to You: Diagnostics (crash data) — opt-out available.
- [ ] **App Privacy Policy URL**: stable URL.
- [ ] **Sign-In not required**: ensure flag is set (no auth in v1).
- [ ] **TestFlight setup**: internal group "FluxIt Team" (auto-distribution), external group "FluxIt Beta" (10–50 testers, requires App Review for first build).

## 6. Asset pipeline

- [ ] All store assets (icons, screenshots, graphics) generated reproducibly via Fastlane lanes in the `fluxit-marketing` private repo. **Not in this code repo** — separate ownership.
- [ ] Icon source: master `.fig` / `.svg` rendered to all densities by lane. Versioned with the app.
- [ ] Screenshots: Fastlane `snapshot` lane runs the screenshot UI test scenarios (separate from regression UI tests in §6 of Phase 14) on simulators with seeded data, then `frameit` adds device chrome. Re-runnable per release.

## 7. Privacy policy + terms of service

- [ ] **Privacy policy** drafted with these v1 facts:
  - Data stored locally only (lists, items, photos, reminders).
  - Crash diagnostics sent to Google Crashlytics; opt-out available in Settings → Privacy.
  - No analytics SDK, no advertising IDs, no third-party trackers.
  - No accounts, no sign-in.
  - Photos stay on device.
  - Reminders use the OS notification system.
  - GDPR/CCPA: contact-email request flow for hypothetical data deletion (will be a no-op since we hold no server data).
- [ ] **Terms of service**: short v1 covering: as-is provision, no warranty, no commercial use restriction, app store EULA supplement.
- [ ] **Hosted at stable URLs**: `https://fluxit.app/privacy` and `https://fluxit.app/terms` (or whatever domain product owns). Static pages, not in this repo.
- [ ] Both linked from Settings → Privacy (Phase 07 §12 backfill, Phase 16 resolution).

## 8. Security review

- [ ] **No HTTP traffic** in v1 (local-only). Confirmed via `usesCleartextTraffic = false` in Android manifest, NSExceptionAllowsArbitraryLoads = false in iOS Info.plist.
- [ ] **No certificate pinning needed in v1** (Crashlytics uses Google's certs; we don't pin third parties).
- [ ] **No sensitive data on disk** that needs encryption beyond OS sandbox: photos and lists are user content but not "sensitive" by GDPR Art. 9 standards. Documented in `docs/SECURITY.md`.
- [ ] **WebView**: not used. Konsist rule: no `WebView` / `WKWebView` imports.
- [ ] **Deep link injection**: parser in `RootStore.OpenDeepLink` validates UUID format strictly; rejects malformed input with a logged warning.
- [ ] **Notification action injection**: no action buttons in v1 (Phase 13), so no injection surface.
- [ ] **R8 + obfuscation** present (§4) — raises the bar against trivial reverse engineering of the schema.
- [ ] **Dependency audit**: `./gradlew dependencyCheckAnalyze` (OWASP plugin) run pre-release. Any High/Critical CVE blocks; Medium triaged.
- [ ] **Secrets in repo**: confirm none. Run `gitleaks` against the full repo + history pre-release.
- [ ] **Manifest permissions audit** (Android): only `POST_NOTIFICATIONS` (API 33+). No `INTERNET` (we don't make HTTP calls — Crashlytics adds this implicitly via Firebase, expected). No `READ_EXTERNAL_STORAGE` (PickVisualMedia API 33+ is permission-less).
- [ ] **Info.plist usage strings** (iOS): `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription` only. Audit no others added inadvertently.

## 9. Final QA pass

- [ ] **`docs/MANUAL_QA_CHECKLIST.md`** (Phase 14 §9) executed cleanly on:
  - Pixel 6a (Android 14) — primary
  - Pixel 4a (Android 13) — older API + lower-end perf check
  - Samsung Galaxy A15 (One UI customizations)
  - iPhone 14 Pro (iOS 17)
  - iPhone SE 1st gen (smallest screen, oldest supported iOS) — verifies layout doesn't break and perf still acceptable
- [ ] **Real-world reminder test**: schedule a reminder for "tomorrow at 9am" on a Friday → leave device idle overnight (incl. doze) → notification fires near 9am Saturday → tap → land on list. Repeat with each recurrence type across a 14-day window.
- [ ] **Photo persistence stress test**: capture 50 photos across 50 items → kill app → reopen → all visible → uninstall + reinstall → all gone (expected, no backup).
- [ ] **Process kill survival**: every screen tested for state restoration as scoped in its phase plan.
- [ ] **Permission denial recovery**: deny notifications, deny camera, deny library on each platform; navigate to Settings, enable, return — banners auto-dismiss, retries succeed.

## 10. Launch monitoring readiness

- [ ] Crashlytics alerts wired to Slack `#fluxit-builds` per Phase 16 resolution.
- [ ] Velocity threshold: 1% sessions in 24h → page release manager.
- [ ] Crash-free rate dashboard bookmarked in Crashlytics.
- [ ] Play Console + App Store Connect daily-active-installs notifications enabled.
- [ ] One-page `docs/LAUNCH_PLAYBOOK.md` covering:
  - Where to look in the first 24h post-release.
  - Rollback procedure (Play Console → halt rollout; App Store → expedited review for hotfix).
  - Hotfix branch process (Phase 15 §1).
  - On-call rotation for the first 7 days.

## 11. Onboarding (zero-screen v1)

- [ ] **No onboarding flow in v1.** App opens to dashboard with empty state copy "No lists yet — tap + to create one." This is intentional — list-making is self-evident.
- [ ] **In-app first-run hint**: a one-time tooltip pointing at the FAB on first launch ("Tap to create your first list"). Dismissed on first FAB tap or on tap-anywhere; tracked via `ConfigKey<Boolean>` so it never re-shows.
- [ ] Localized copy lives in the string registry (§2).

## 12. Versioning + release artifacts

- [ ] **Version**: `1.0.0` (semver). Stamped via Gradle from a single source: `version.properties` at repo root, read by both Android (`versionName` + `versionCode = base + GITHUB_RUN_NUMBER`) and iOS (`CFBundleShortVersionString` + `CFBundleVersion = GITHUB_RUN_NUMBER`).
- [ ] **Build number monotonically increasing** across stores; `GITHUB_RUN_NUMBER` is the source of truth.
- [ ] **Tag**: `v1.0.0` (Phase 15 release pipeline takes over).
- [ ] **Release artifacts checked in to GitHub Release**: `.aab`, Android mapping.txt, iOS dSYMs (zipped), release notes (auto-generated from Conventional Commits).

## 13. Final documentation refresh

- [ ] **`README.md`**: prerequisites, run instructions, link to MASTER_PLAN, link to ARCHITECTURE.
- [ ] **`docs/ARCHITECTURE.md`**: final module diagram + layer descriptions; reflects what was actually built (not just planned).
- [ ] **`docs/SCALING.md`**: filled in (was a stub from Phase 01) — module ownership matrix, when to split a feature module, version catalog stewardship, the v2 Backlog from MASTER_PLAN promoted to actionable items.
- [ ] **`docs/TEAM_GUIDELINES.md`**: PR review SLAs, branch naming, commit conventions (Phase 15 §11), pre-commit hook setup, snapshot recording workflow (Phase 14 §16).
- [ ] **`docs/RELEASE_PROCESS.md`**: tag → CI → distribution → store promotion → monitor (§10). Includes hotfix path (Phase 15 §1).
- [ ] **`docs/SIGNING.md`**, **`docs/CI.md`**, **`docs/PERF_LOG.md`**, **`docs/PRIVACY_LOGS.md`**, **`docs/PRIVACY_EVENTS.md`**, **`docs/A11Y_AUDIT.md`**, **`docs/SECURITY.md`**, **`docs/I18N.md`**, **`docs/LAUNCH_PLAYBOOK.md`**, **`docs/MANUAL_QA_CHECKLIST.md`** — all confirmed up to date.
- [ ] **MASTER_PLAN.md** marked v1 complete; v2 backlog promoted to a separate `docs/V2_BACKLOG.md` so v1 plan files can be archived.

## 14. ADRs to write in this phase

- [ ] **ADR-013** — Dark-mode-only at v1 release (re-confirms ADR-005b post-implementation; documents that no light tokens were added).
- [ ] **ADR-013a** — English-only at v1; typed-string registry as the i18n seam.
- [ ] **ADR-013b** — No onboarding flow in v1; first-run hint only. Justification + measurement plan (event `app_started{is_first_launch=true}` as denominator for v2 onboarding A/B).

## 15. Open questions for this phase

- [ ] **Open-source license decision** (carried from Phase 01). v1 going to public stores — needs a real LICENSE file. Default proposal: **All rights reserved** (closed source); revisit if we open-source later.
- [ ] **Beta tester recruitment**. Who's in the external TestFlight + Play Closed group? Default proposal: 20–50 testers from product's network; recruit during Phase 16 work so they're ready by §9 final QA pass.
- [ ] **First-run hint exact copy + behavior**. Tooltip vs. animated FAB pulse vs. inline empty-state CTA. Default proposal: **inline empty-state CTA** ("Tap + below to create your first list" with a small arrow above the FAB position) — no tooltip overlay. Less invasive.
- [ ] **App icon final design.** Mockup brand exists (Phase 02); final adaptive icon design + iOS icon PSD/Figma owned by design. Confirm asset delivery date.
- [ ] **Domain ownership** for `fluxit.app` (or whatever) — needed for privacy/terms hosting (§7). Confirm secured before submission.

## 16. Launch-readiness checklist (the gate)

This is the single sign-off list to ship. Every box must be checked.

### Code & quality
- [ ] All Phase 01–16 hand-off checklists ✅.
- [ ] Coverage thresholds (Phase 14 §3) green.
- [ ] No `@Ignore` without issue link; flake-log clean for the touched modules.
- [ ] R8 smoke flow passes (§4).
- [ ] All Konsist rules green.

### Performance
- [ ] All §3 perf budgets met on real-device matrix.
- [ ] Perf log entry recorded for v1.0.0.

### Accessibility
- [ ] §1 audit complete; no Critical findings.
- [ ] `docs/A11Y_AUDIT.md` updated.

### Privacy & security
- [ ] Privacy policy + ToS published at stable URLs.
- [ ] Settings → Privacy toggle wired and verified.
- [ ] OWASP dependency check: no High/Critical.
- [ ] `gitleaks` clean.
- [ ] Manifest / Info.plist permissions are exactly the documented set.

### Release plumbing
- [ ] CI release pipeline dry-run completed (Phase 15 §14).
- [ ] Both stores' listings drafted with all assets.
- [ ] Crashlytics symbols upload verified.
- [ ] Slack alerts wired.

### Final manual pass
- [ ] §9 QA matrix executed across all listed devices.
- [ ] Real-world overnight reminder test passed.
- [ ] 7-day Play Internal + TestFlight soak: crash-free ≥ 99.5%.

### Sign-off
- [ ] Engineering sign-off (release manager).
- [ ] Product sign-off (store copy, screenshots, content rating).
- [ ] Design sign-off (icons, feature graphic, screenshots).
- [ ] **Tag `v1.0.0` on `main`** → Phase 15 release pipeline takes over from here.

---

## Hand-off (post-release)

- [ ] First 24h: monitor crash-free rate, ANR rate, install funnel.
- [ ] First 7 days: weekly review of Diagnostic-screen reports from internal testers + crash trends.
- [ ] First 30 days: prioritize v2 backlog based on real usage patterns; close out any v1.0.x hotfix needs.
- [ ] `MASTER_PLAN.md`: Phase 17 → 🟢; **v1 complete**; archive `plan/` → `plan/v1/`; create `plan/v2/` empty for the next planning round.
- [ ] `00_DECISIONS.md`: ADR-013 (a/b) accepted.
