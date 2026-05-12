# Phase 15 — CI/CD

> **Goal:** Promote Phase 01's smoke build into a production pipeline: PR validation gating every merge, signed Android + iOS release artifacts produced from `main`, distribution to internal testers (Firebase App Distribution + TestFlight), secrets handled safely, and a documented branching/release flow that scales beyond one engineer.

**Owner:** Mobile platform
**Depends on:** Phase 01 (smoke CI exists), Phase 14 (gates defined), all module phases (build artifacts).
**Blocks:** Phase 17 (release hardening + store submission).
**Exit criteria (Definition of Done):**
- PR pipeline runs in < 12 min end-to-end on a typical change (cached Gradle warm + Pods cached).
- `main` push produces signed `.aab` (Android) and `.ipa` (iOS) uploaded to internal track + TestFlight automatically.
- Secrets only ever live in GitHub Actions encrypted secrets; nothing in repo, nothing in branch builds with PR write tokens.
- Branch protection on `main`: required checks, ≥1 approval, up-to-date with main, signed commits enforced.
- Release notes auto-drafted from Conventional Commits since the last tag.
- One end-to-end "release dry run" completed: tag → CI builds → both apps installed on real devices via TestFlight + App Distribution.

---

## 1. Branching strategy (ADR-011)

- [ ] **Trunk-based with short-lived branches.** `main` is always green and shippable.
- [ ] Feature branches: `<initials>/<short-slug>` (e.g. `fz/list-detail-composer`). Lifetime ≤ 3 days; longer = squash + rebase or split.
- [ ] No `develop`, no `release/*`, no GitFlow. Releases are tags on `main`.
- [ ] **Hotfix path**: branch off the release tag (e.g. `hotfix/v1.0.1` from `v1.0.0`), fix, tag `v1.0.1`, then forward-merge into `main`. Documented in `docs/RELEASE_PROCESS.md`.
- [ ] **Tag format**: `v{major}.{minor}.{patch}` (semver). Tags trigger the release pipeline; pushes to `main` only build + run smoke deploys.

## 2. Workflow files

```
.github/workflows/
  pr.yml              ← runs on pull_request; the merge gate
  main.yml            ← runs on push to main; smoke + internal distribution
  release.yml         ← runs on v* tag; signed builds + store track upload
  nightly.yml         ← runs on cron; perf benchmark + dependency-updates check
  pr-paparazzi.yml    ← (composite job triggered by pr.yml; isolated for clarity)
```

## 3. PR pipeline (`pr.yml`)

Triggered on `pull_request: [opened, synchronize, reopened, ready_for_review]`. Jobs run in parallel where independent.

### Job: `checks-jvm` (ubuntu-latest, ~5 min)

- [ ] Setup: actions/checkout (with `fetch-depth: 2` for git diff), actions/setup-java 21 Temurin, gradle/actions/setup-gradle (with cache).
- [ ] Run: `./gradlew spotlessCheck ktlintCheck detekt konsistTest`
- [ ] Run: `./gradlew :shared:domain:allTests :shared:data:allTests :shared:state:allTests :core:core-utils:allTests :platform:platform-config:allTests :platform:platform-logging:allTests`
- [ ] Run: `./gradlew :build-logic:test` (Konsist registry tests).
- [ ] Run: `./gradlew :shared:data:verifySchema` (the schema dump check from Phase 03).
- [ ] Coverage: `./gradlew koverXmlReport` then upload to Codecov (or GitHub Action `madrapps/jacoco-report` for inline PR comments).
- [ ] **Coverage threshold check**: a custom Gradle task `koverVerify` fails if any module is below the threshold from Phase 14 §3. Wired via Kover's `koverVerify` extension.

### Job: `checks-paparazzi` (ubuntu-latest, ~4 min)

- [ ] Same setup.
- [ ] Run: `./gradlew verifyPaparazziDebug` (verify only — never record on CI per Phase 14 §16).
- [ ] On failure, upload `build/paparazzi/failures/` as a GitHub artifact (PNG diffs visible in PR).

### Job: `build-android` (ubuntu-latest, ~6 min)

- [ ] `./gradlew :android-app:assembleDebug` — verifies the app graph compiles.
- [ ] Upload debug APK as artifact (testers can sideload PR builds).

### Job: `checks-ios` (macos-14, ~10 min)

- [ ] Setup: actions/checkout, ruby/setup-ruby (for Fastlane), bundle install (cached via `bundler-cache: true`).
- [ ] Cache: SwiftPM (`.build/`), DerivedData (scoped per-PR), CocoaPods (we don't use Pods in v1, but the cache key is in place if added).
- [ ] Run: `./gradlew :shared:state:assembleSharedDebugXCFramework` (produces the framework iOS consumes).
- [ ] Run: `bundle exec fastlane ios test_pr` — wraps `xcodebuild test -scheme FluxIt -destination 'platform=iOS Simulator,name=iPhone 13 mini,OS=17.0'` for unit + snapshot tests.
- [ ] Run: `bundle exec fastlane ios build_pr` — debug-signed build, no upload.

### Job: `integration` (ubuntu-latest, ~5 min) — runs in parallel

- [ ] `./gradlew :shared:state:jvmTest --tests 'com.fluxit.test.IntegrationFlow*'`
- [ ] Sets a hard 30s timeout per test (Phase 14 exit criterion).

### Job: `pr-summary` (ubuntu-latest, ~30s) — needs all above

- [ ] Posts a single summary comment to the PR: ✅ checks, coverage delta, snapshot diffs, APK download link, iOS test results.
- [ ] Idempotent (updates the same comment instead of spamming).

### Concurrency control

- [ ] `concurrency: group: pr-${{ github.event.pull_request.number }}, cancel-in-progress: true` — cancels stale runs when a PR is force-pushed.

## 4. Main pipeline (`main.yml`)

Triggered on `push: { branches: [main] }`. Identical checks to PR plus:

- [ ] **Snapshot regen guard**: if `verifyPaparazziDebug` fails, fail the build (this is a regression — should have been caught in PR). Don't auto-record.
- [ ] **Internal distribution job (Android)** — runs after checks pass:
  - Downloads signing keystore from GitHub secret `ANDROID_RELEASE_KEYSTORE_BASE64` to a temp file.
  - `./gradlew :android-app:bundleInternal` (custom build type for internal track — debuggable=false but with dev-server flags off; signs with internal keystore).
  - Uploads `.aab` to Firebase App Distribution via `wzieba/Firebase-Distribution-Github-Action`. Tester group: `internal`.
  - Build number derived from `GITHUB_RUN_NUMBER` so versions are monotonically increasing.
- [ ] **Internal distribution job (iOS)** — `bundle exec fastlane ios distribute_internal`:
  - Uses `match` to fetch signing certs/profiles from a private repo (separate from this codebase).
  - Builds with internal config; uploads to TestFlight internal testing group.
- [ ] **Failure notifications**: post to a `#fluxit-builds` Slack channel via `slackapi/slack-github-action`.

## 5. Release pipeline (`release.yml`)

Triggered on `push: { tags: ['v*'] }`.

- [ ] All PR checks repeat against the tag SHA.
- [ ] **Build Android release**: `./gradlew :android-app:bundleRelease` signed with `ANDROID_PLAY_KEYSTORE_BASE64`. R8 + minify enabled (Phase 17 wires R8 rules).
- [ ] **Build iOS release**: `bundle exec fastlane ios release` — App Store signing via `match`, builds + uploads to TestFlight external testing.
- [ ] **Generate release notes**: `mikepenz/release-changelog-builder-action` reads Conventional Commits since previous `v*` tag.
- [ ] **Create GitHub Release**: attaches release notes + `.aab` + Android `mapping.txt` (for crash deobfuscation).
- [ ] **Upload mapping to Crashlytics**: Android via `gradle-firebase-crashlytics-plugin`, iOS via `upload-symbols` from Fastlane.
- [ ] **Promote to Play Internal track** (not Internal App Sharing — actual Play Console internal testing): `r0adkll/upload-google-play` action with the service-account JSON in secrets.
- [ ] Manual promotion to Play Internal → Closed → Production happens **outside CI** in the Play Console (humans should look at metrics before promoting).
- [ ] Same pattern for iOS: TestFlight external testing → App Store submission triggered manually from App Store Connect.

## 6. Nightly pipeline (`nightly.yml`)

`cron: '0 6 * * *'` (daily 6:00 UTC).

- [ ] **Dependency updates**: `./gradlew dependencyUpdates` (Ben Manes plugin) → opens an issue listing outdated deps. Dependabot already opens PRs (Phase 01 §10) for trivial bumps; this is the digest report.
- [ ] **On-bench perf** (when we have a runner with a connected Pixel device, post-MVP): runs the perf scenarios from Phase 14 §8 and appends to `docs/PERF_LOG.md` via a PR. Skipped in v1 if we don't yet have a self-hosted runner.
- [ ] **Cache prune**: clears Gradle / SwiftPM caches older than 14 days to avoid GitHub Actions cache quota exhaustion (10 GB hard limit).

## 7. Caching

- [ ] **Gradle**: `gradle/actions/setup-gradle` with `cache-read-only: ${{ github.ref != 'refs/heads/main' }}` — only `main` populates the cache; PRs read from it. Avoids cache pollution from feature branches.
- [ ] **SwiftPM**: cache `.build/` keyed on `Package.resolved` hash.
- [ ] **DerivedData**: cached per-branch (PR scope), purged nightly.
- [ ] **Bundler**: cached via `bundler-cache: true`.
- [ ] **Konan**: cache `~/.konan` keyed on Kotlin version (saves multi-minute K/N download per run).
- [ ] Total cache size budget: ~3 GB per branch. Document in `docs/CI.md`.

## 8. Secrets inventory

Documented in `docs/CI.md` (not in `README` — limits exposure surface). Each secret has owner + rotation cadence.

| Secret | Used by | Rotation |
|---|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | main, release | Yearly + on team change |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | main, release | Yearly |
| `ANDROID_RELEASE_KEY_ALIAS` | main, release | Stable |
| `ANDROID_RELEASE_KEY_PASSWORD` | main, release | Yearly |
| `ANDROID_PLAY_SERVICE_ACCOUNT_JSON` | release | Yearly |
| `FIREBASE_APP_DISTRIBUTION_TOKEN` | main | Quarterly |
| `FIREBASE_GOOGLE_SERVICES_JSON` | all jobs | On Firebase project change |
| `IOS_GOOGLE_SERVICE_INFO_PLIST` | iOS jobs | On Firebase project change |
| `MATCH_GIT_PRIVATE_KEY` | iOS jobs | Yearly |
| `MATCH_PASSWORD` | iOS jobs | Yearly |
| `APPSTORE_CONNECT_API_KEY_ID` | release | Stable |
| `APPSTORE_CONNECT_API_KEY_ISSUER_ID` | release | Stable |
| `APPSTORE_CONNECT_API_KEY_BASE64` | release | Yearly |
| `SLACK_BOT_TOKEN` | all jobs | On bot rotation |
| `CODECOV_TOKEN` | PR | Yearly |

- [ ] **PRs from forks** never receive secrets (GitHub default). PR pipeline must run cleanly without them — non-secret jobs only. Distribution jobs gated on `if: github.event_name == 'push'`.

## 9. Code signing

### Android

- [ ] Two keystores: `internal` (CI internal track) and `play` (Play Store). Both 25-year RSA, generated once and stored encrypted.
- [ ] Signing config in `:android-app/build.gradle.kts` reads passwords from environment (CI) or `~/.gradle/gradle.properties` (local devs need only the `internal` keystore).
- [ ] `signingConfig` only applied when env vars present; debug builds always use the default debug key.
- [ ] Keystore SHA-1/SHA-256 fingerprints committed to `docs/SIGNING.md` for Firebase / OAuth verification.

### iOS

- [ ] **`fastlane match`** with a separate private repo `fluxit-certs` (no source code, just encrypted certs/profiles).
- [ ] Profiles: `match Development`, `match AppStore`. Internal distribution uses AppStore profile (TestFlight is App Store track).
- [ ] No `.p12` files in this repo. `MATCH_PASSWORD` decrypts the certs repo.
- [ ] Bundle ID: `com.fluxit.ios`. Documented in `docs/SIGNING.md`.

## 10. Branch protection (manual, but documented)

`main` branch:

- [ ] Require pull request reviews (1 approver minimum; 2 once team grows past 4).
- [ ] Require status checks: `checks-jvm`, `checks-paparazzi`, `build-android`, `checks-ios`, `integration`.
- [ ] Require branches to be up to date before merging (forces rebase on stale PRs).
- [ ] Require signed commits (sigstore or GPG). Documented in `docs/TEAM_GUIDELINES.md` setup steps.
- [ ] Require linear history (no merge commits — squash and rebase only).
- [ ] No force pushes (admin override allowed for emergencies; logged).
- [ ] No deletions.
- [ ] Auto-delete head branches after merge.

Tags `v*`:

- [ ] Protect from force-push and deletion.
- [ ] Only release-managers can push tags (controlled via `CODEOWNERS` on `release.yml` + protected refs).

## 11. Conventional Commits + commit message lint

- [ ] Format: `type(scope): subject` (e.g. `feat(list-detail): swipe to delete with undo`).
- [ ] Types: `feat | fix | refactor | docs | test | chore | perf | build | ci`.
- [ ] Linted in PR via `wagoid/commitlint-github-action`.
- [ ] Squash merge default uses the PR title as the commit message (so PR titles must follow the convention too — also linted).
- [ ] Powers `release.yml`'s release-notes generation.

## 12. PR template + CODEOWNERS

- [ ] `.github/pull_request_template.md` (sketched in Phase 01) — finalized:
  - Summary
  - Linked phase / ADR / issue
  - Screenshots (Android + iOS for UI changes)
  - Test plan (must include manual steps for UI changes; "covered by snapshot" is acceptable)
  - Migration / breaking changes
  - Visual changes (only present if Paparazzi goldens changed)
- [ ] `.github/CODEOWNERS`:
  - `*` → `@fluxit-mobile-platform`
  - `/ios-app/` → `@fluxit-ios`
  - `/android-app/` → `@fluxit-android`
  - `/.github/` → `@fluxit-platform-leads` (more cautious review of CI changes)
  - `/plan/` and `/docs/` → `@fluxit-mobile-platform`

## 13. Local parity

CI must be runnable locally to debug failures.

- [ ] `scripts/run-pr-checks.sh` — runs the PR pipeline locally end-to-end (sans iOS on non-mac, sans secrets-dependent jobs). Mirrors CI exactly.
- [ ] `scripts/setup-dev.sh` — installs JDK (via mise/asdf), Ruby, Gradle wrapper warm-up. Documented in `README.md`.
- [ ] Pre-commit hook (Phase 01 §8) runs the fast subset (spotless + ktlint + detekt on changed files).

## 14. Release dry-run checklist (one-time, before first production release)

- [ ] Tag `v0.0.1-rc1` from `main` → release pipeline runs.
- [ ] `.aab` lands in Firebase App Distribution; install on a real Android device.
- [ ] `.ipa` lands in TestFlight; install on a real iPhone.
- [ ] Crashlytics receives a synthetic crash from each platform.
- [ ] Release notes generated from commits look reasonable.
- [ ] Mapping files attached to GitHub Release.
- [ ] Document any sharp edges in `docs/RELEASE_PROCESS.md`.

## 15. ADRs to write in this phase

- [ ] **ADR-011** — Trunk-based branching, no GitFlow. Justification + hotfix path.
- [ ] **ADR-011a** — Conventional Commits required; powers release notes.
- [ ] **ADR-011b** — `fastlane match` with private certs repo (vs. App Store Connect API key cert generation). Why match: deterministic team-shared certs; works with multiple developers without churn.
- [ ] **ADR-011c** — No fork PR distribution; secrets only on push to `main` and tags. Reduces blast radius of compromised PR.

## 16. Open questions for this phase

- [ ] **Self-hosted runner for iOS?** GitHub-hosted macOS minutes are 10× the cost of Linux. For v1's volume, GitHub-hosted is fine. Revisit if iOS CI minutes blow the budget.
- [ ] **Codecov vs. inline GitHub Action coverage**. Codecov has nicer trends; inline is free + lives in the PR. Default proposal: inline (madrapps/jacoco-report) for v1; Codecov in v2 if we want trend visibility.
- [ ] **Distribution channel for internal Android**: Firebase App Distribution vs. Play Console internal track? FAD is faster (no Play review); Play internal is a real Play install. Default proposal: **both** — FAD for daily dev, Play internal for pre-release sanity.
- [ ] **Release cadence**: weekly tagged releases, on-demand, or only when meaningful changes accrue? Default proposal: **on-demand** in v1; we don't yet have the user base for weekly cadence to make sense.
- [ ] **Slack notifications scope**: only failures, or also successes? Default proposal: failures + first success after a failure (so the channel isn't noisy).

## 17. Hand-off checklist (gate to Phase 16)

- [ ] All checkboxes above ✅.
- [ ] PR pipeline measured at < 12 min on a typical change.
- [ ] First end-to-end release dry run completed (§14).
- [ ] Branch protection rules applied to `main` and `v*` tags.
- [ ] Secrets all created in GitHub; `docs/CI.md` published with the inventory.
- [ ] `MASTER_PLAN.md`: Phase 15 → 🟢, ▶ Next Step → Phase 16.
- [ ] `00_DECISIONS.md`: ADR-011 (a/b/c) accepted.
