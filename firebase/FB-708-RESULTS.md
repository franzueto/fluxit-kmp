# FB-708 developer-delegated evidence results

Evidence run 2026-10-06 (UTC 14:43-15:06) on branch `epic/firebase`, **committed HEAD `2c3bd88d0766a524c761e77571e2aa3a6176a762`**, worktree clean except the orchestrator-owned ` M FIREBASE_MIGRATION_STATUS.md` (not touched). Commands and sequencing: [FB-708-PROCEDURE.md](FB-708-PROCEDURE.md). This is evidence for the orchestrator's FB-708 gate and independent review, not a self-approval, status change or commit. No product, Rules, backend, configuration or script change; **no regression found**.

Scope statements that must carry into closure:

- **M01-M10 (MAN-008) are user-attested** (blanket PASS on Android AVD and iOS simulator, 2026-10-06, recorded in FB-707) and were **not** executed or re-observed by this run. No per-row observation or build fingerprint exists for them (FB-707-NB1).
- **No physical iOS device** was used (DEC-004: iOS target is the simulator; MAN-003 permanently WAIVED).
- **No production/deployment/provisioning** and no cloud-mutating tier was run (DEC-012: development-only; MAN-005 WAIVED). No `firebase deploy`, no development-cloud client run, no real-project write.

## Result matrix

| Tier | Result | Counts / notes |
|---|---|---|
| Fresh Gradle matrix (`--rerun-tasks`: Debug/Release unit, iOS simulator unit, assembleDebug/Release, iOS Debug+Release framework link) | PASS, BUILD SUCCESSFUL | 121/121 tasks executed (log `f16807298a6fc747`), exit 0 |
| Android Debug unit (`TEST-*.xml`, 29 files) | PASS | 250 tests, 0 failures/errors/skips |
| Android Release unit (29 files) | PASS | 250 tests, 0 failures/errors/skips |
| iOS simulator unit (34 files) | PASS | 312 tests, 0 failures/errors/skips; includes `SwipeToDeleteContainerIosTest` 1/1 (`restoredRowAfterDeleteAndUndoRendersAndCanBeDeletedAgain`) |
| Rules/config/storage/query (`npm test`) | PASS | config 4/4 + Rules/storage/query 60/60 = 64/64 |
| Security emulator client matrix | PASS | 112/112 assertions, scoped cleanup verified; three intentional-failure injections each returned expected exit 1 with scoped cleanup `remaining=0` (fixture failure, partial Auth, pending Storage) |
| Security fail-closed safety (sequential) | PASS | 4/4 |
| Backend local syntax/unit (`npm run check`) | PASS | 8/8 |
| Backend emulator integration (`npm run test:emulator`) | PASS | 13/13 against owned local emulators |
| Native lease safety tests | PASS | 6/6 |
| Native Android instrumentation in owned lease (complete inventory excluding the two opt-in fixture classes) | PASS | `OK (100 tests)` = prior 90 + 10 FB-710 cases; no failure/crash |
| Native Apple emulator selfchecks | PASS | 11/11 launches, no `FAIL` |
| Actual Koin native contract | PASS | 16 checkpoints per platform, bidirectional realtime and photo bytes, Apple offline cached-read/pending-write/reconnect recovery |
| Lease disposal | PASS | prewrite zero baseline in both namespaces; only the task-owned process group terminated; `disposed=true`, `portsClosed=true`; no listener on 8080/9099/9199/4400/4500/9150 afterwards |
| Ordinary Gradle build (`--rerun-tasks`, default flags: assembleDebug/Release/DebugAndroidTest, both iOS framework links) | PASS | 127/127 tasks executed, exit 0 |
| Android ordinary DI instrumentation (`RepositoryDiInstrumentedTest`, `AndroidAuthDiInstrumentedTest`) | PASS | `OK (3 tests)` |
| **FB-710 regression on Android AVD** (`SwipeToDeleteContainerInstrumentedTest` 8 + `SwipeUndoScreensInstrumentedTest` 2) | PASS | `OK (10 tests)`, run on the ordinary debug build after reinstall, and again inside the 100-test native tier |
| **FB-710 regression on iOS simulator** (`SwipeToDeleteContainerIosTest`) | PASS | 1/1 inside the 312-test `iosSimulatorArm64Test` run |
| iOS default-graph probe (`default-ios.py`) | PASS | Firebase list/item graph, `emulator=false`, Room definition absent, Auth factory uncreated |
| Ordinary Xcode Debug / Release builds | PASS | both `BUILD SUCCEEDED` |
| Package/dependency scan (`packages.py`) | PASS | `Room=absent AndroidX-SQLite=absent bundled-SQLite=absent Firebase-persistence=retained emulator=false` for Android Debug, Android Release, iOS Debug, iOS Release; all four dependency configurations, sources, DEX/native entries, Apple symbols/files, both frameworks scanned |
| Restoration (`restore.py`) | PASS | ordinary Debug Android APK and ordinary iOS Debug binaries installed and byte-matched; `com.fluxit.test` absent; generated `ENABLED=false`, `USE_FIREBASE_REPOSITORIES=true`; task ports closed |
| `git diff --check` | PASS | exit 0; end state `git status --short` equals start state |

Native tier actual server residue captured before disposing the owned process (ephemeral fixture disposal, not per-path cleanup; same counts as FB-706 r2): configured namespace roots 7 / lists 15 / items 27 / cleanup jobs 0 / accounts 92 / photos 0; `demo-fluxit` roots 52 / lists 56 / items 55 / cleanup jobs 0 / accounts 0 / photos 6. Residue is synthetic and local; raw inventories stay local.

## Hashes (sha256 prefixes of retained local artifacts)

Local copies are in `/tmp/fluxit-fb708-logs` (may be purged by host cleanup); only hashes are committed.

| Artifact | sha256 prefix |
|---|---|
| Product/test files | full hashes in the procedure; all five equal FB-710-RESULTS and unchanged at end |
| `gradle-matrix.log` / `ordinary-gradle.log` | `f16807298a6fc747` / `d3fd86409c8da80b` |
| `rules.log` / `security-matrix.log` / `security-safety.log` | `bb6caa8ace04499b` / `ad228e979e52efd0` / `de3cdf4bafa29d64` |
| `functions-check.log` / `functions-emulator.log` | `403fa96fc2ee241b` / `ccfb9aaf7a66b932` |
| `isolated-native.log` (passing run) | `d178617b403c7e68` |
| native `lease.json` / `initial-inventory.json` / `residual-inventory.json` | `3ca40f77f5b23481` / `75ece3eab8385b9e` / `5b8bbae85375e5ce` |
| `android-di.log` / `android-swipe.log` | `556f006c6c48360d` / `7793f6395ab1d5cc` |
| `packages.json` / `restoration.json` | `31093e9fa702a310` / `9bf6f3d9e81d084c` |
| ordinary Debug APK / Release unsigned APK | `4cdf42c0cdf3fc38` / `d2f5d6c899646dd0` |
| ordinary iOS Debug `FluxIt` binary | `b709bdc02d1d97c6` |
| committed `isolated_native.py` / patched scratch copy | `e74721b630ae91da` / `440030fe5936d268` |

## Retained unsuccessful or adjusted attempts

1. First native run attempt (`isolated-native-attempt0-setup-error.log`, `0a22ce57f76f63f1`): my scratch mirror lacked the top-level symlinks (shell word-splitting error in the setup loop), so the wrapper stopped at its first config read with `FileNotFoundError` before starting any emulator, process, lease write or device I/O. Setup error, not a product or tooling failure; the mirror was fixed and the full run executed from scratch.
2. A first ordinary Gradle run used cached outputs (`ordinary-gradle-cached.log`, `ff1253ff0a6428dc`); it was superseded by the `--rerun-tasks` run above, after which the APKs were re-installed and the instrumented checks re-run.
3. The committed native wrapper's strict `OK (90 tests)` guard is stale after FB-710 (actual 100). Handled through the scratch copy described in the procedure; committed script untouched.
4. The FB-706 Node 22 path had been purged from `/tmp`; handled as described in the procedure (no repo change).

## Not run / unverified and bounds

- Development-cloud client-security tier (FB-706's reviewed exact-synthetic runner) and any development-cloud native tier: not run this session; FB-706 evidence for it stands only for the FB-706 commit and is not re-attested here. no Rules, functions/backend or Firebase config file changed since then (`git diff --stat 9281019 2c3bd88` shows only the FB-710 SwipeToDelete source/tests, the iosTest `compose.uiTest` dependency in `composeApp/build.gradle.kts`, one Android DI test adjustment and docs/verification scripts), so the emulator Rules tiers above are the fresh evidence.
- FB-709 native lifecycle fixture: not re-executed (retained reviewed evidence; product code of that path is unchanged by FB-710).
- **FB-710 follow-ups that remain unverified/OPEN:** NB1 (failed swipe-delete leaves the row dismissed until retry/re-entry) and NB2 (`enabled` read at settle, in-animation race skips `onDelete`) were not exercised; NB3 (no test for enabled-toggle mid-swipe or failed delete; Dashboard `requireOffset` crash not reproduced in any harness) remains; NB4 (gesture feel; iOS live swipe-delete + Undo never driven by an agent, UI automation unavailable) remains covered only by the compose UI test and the user's blanket MAN-008 attestation. FB-707-NB1 (blanket attestation without per-row observations or build fingerprint) and FB-707-NB3 (stale wording in `FB-706-MANUAL.md` header) are also untouched by this run.
- Not exercised by any tier: real Android picker/PHPicker, literal uninstall/reinstall, real radio/host-network cut, literal sign-out UI gestures, iOS background/memory pressure, physical iOS, FB-601-NB1 aggregate-integrity redesign, production smoke.

## Recommended next action

Orchestrator: record this evidence in the FB-708 gate (decide how to treat the missing development-cloud client tier and the stale strict-count guard), then send the exact evidence set to `firebase-reviewer`. The user was asked to confirm the tested build equals commit `2c3bd88`; this run itself is anchored to that commit's exact bytes (product/test hashes above).
