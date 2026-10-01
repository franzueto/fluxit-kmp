# FB-705 developer results — 2026-10-01

Implementation report; independent review/handoff are still required. No canonical
task state, `next_task`, manual action state or decision was changed by the developer.
Base: `epic/firebase`, `a86e2a4` (reviewed FB-704 rollback point). The orchestrator's
tracker changes were preserved.

## Delivered scope

- `../README.md`: replaced offline-only/Room claims with actual Firebase/auth/cloud
  bindings, build/setup, Email/Password/recovery, current offline/cold-start/photo
  limits, cache/privacy behavior, 30-day backend eligibility, clean-cut reinstall
  data loss/no-import and supported simulator scope.
- `README.md`: runnable local/ordinary and opt-in native instructions, matching
  configured/default versus secondary demo namespaces, fixed ports/overrides,
  distinct Rules/backend emulator sets, explicit cloud project targeting,
  current deployment/evidence bounds and practical SPM/Swift interop explanation.
- `FB-705-PROCEDURE.md` and this file: reproducible read-only checks and honest
  executed/unrun results.

Fresh clones obtain `composeApp/google-services.json` for `com.fluxit` and
`iosApp/GoogleService-Info.plist` for `com.fluxit.FluxIt` from Console app settings;
configs stay ignored in every environment. No identifiers/credentials/config
contents were added. Every displayed Firebase CLI call has an explicit project;
cloud operations refer to the reviewed development procedures with explicit
project flags. `.firebaserc` remains `demo-fluxit`.

FB-007-NB2 documentation now avoids a blanket CocoaPods impossibility claim;
FB-102-NB3 covers manually started Auth emulator and the `src/debug` cleartext
overlay. FB-103-NB2/NB3 distinguish developer-executed historical console evidence
from reviewer corroboration, and give the current Python nonzero/timeout/report
wrapper without claiming XCTest parity or retroactive reviewer reproduction.
Only the orchestrator can dispose those canonical follow-up rows.

## Exact checks and results

All commands completed with exit 0. Reproduction details and the doc checker source
are in [FB-705 procedure](FB-705-PROCEDURE.md).

| Working directory | Command | Actual result |
|---|---|---|
| Repository root | `python3 /tmp/fluxit-fb705-doc-check.py` | PASS: 18 relative links/anchors, 14 shell blocks (`bash -n`, not executed), targeting/scripts/app IDs/overlays/ignore policy |
| `firebase/` | `npm run check` | Config/index/default-port tests 4/4, failures/skips 0 |
| `functions/` | `npm run check` | JavaScript syntax/build and unit tests 8/8, failures/skips 0; exact 30-day/retry/reference/generation semantics checked |
| Repository root | `python3 firebase/parity/ios-checks.py --help` | Existing `--app`/`--device` flags confirmed; exits before simulator/network use |
| Repository root | `python3 firebase/room-removal/native.py --help` | Existing app/device/ADB/Android-device flags confirmed; no runtime execution |
| Repository root | `python3 firebase/photo-removal/fixtures.py --help` | Existing preflight/cleanup/state flags confirmed; no fixture reads/deletes |
| Repository root | `node --version`; `npm --version` | Node `v24.16.0`, npm `11.13.0`; local syntax/unit checks only. Docs specify Node 22 for Functions/cloud security |
| Repository root | `git diff --exit-code HEAD -- composeApp functions iosApp gradle.properties gradle/libs.versions.toml firestore.rules storage.rules firestore.indexes.json firebase.json .firebaserc .gitignore` | PASS: protected product/config/Rules/backend paths unchanged |
| Repository root | `git diff --check` | PASS, no whitespace errors |
| Repository root | `git status --short` | Developer-owned docs above plus orchestrator-owned tracker; no other change |

Source inspection validated property/Swift compile gates, default SDK versus
secondary demo targeting, pinned SPM 12.19.2 and Android BoM 34.4.0, app IDs and
resource references, actual session gate/Auth sign-out/cache code, image preparation,
metadata flags, backend schedule/cascade/journal and runner teardown limitations.
Primary Firebase/Kotlin documentation was browsed for changing Console/API/interop
instructions; supporting links are next to the relevant README claims. No Firebase
Console/project write, deployment, Admin shortcut, global emulator reset or dependency
installation was performed.

## Unrun tiers and residual risks

No Gradle/Xcode build, mobile launch, native integration, JS Rules/security emulator,
backend integration emulator, cloud security/deployment, literal reinstall, photo
picker gesture, radio gesture or production smoke was run for this documentation
slice. Existing historical evidence is referenced, not claimed as new execution.
No fresh developer performed the full setup in this task. Physical iOS testing is
permanently outside scope (DEC-004), not a requested future manual action.

The audit found DEC-003a/AuthRepository's required persistent cache clearing is
unimplemented: current sign-out is Auth-only plus user-scoped UI/ViewModel teardown.
The orchestrator recorded PLAN-010/FB-709 before FB-706. This task documents the
current gap, without accepting retention or changing product code. Pending-write
removal is inherent to the selected `terminate()`/`clearPersistence()` policy;
logical cache removal is not secure overwriting or a forensic erasure guarantee.

Recommended FB-709 focus: quiesce user-scoped listeners/jobs before SDK teardown,
remove cached documents and pending writes, recreate/reconfigure Firestore instances
rather than reuse captured terminated singletons, and expose cleanup failure/retry
without claiming successful sign-out. SessionGate's sign-out methods currently
ignore `AuthResult`; both platforms need real-cache A→B/same-user checks, cancellation/
interruption/retry and post-cleanup reconnect tests. Photo bridge task cancellation
and in-memory bytes also need explicit review. Warn about unsynced changes under the
already approved policy; do not add an indefinite network-wait requirement. Update
the READMEs to the verified final behavior when FB-709 lands.

MAN-005/production provisioning, exact aggregate architecture FB-601-NB1 and existing
cloud-index/aged-photo/manual fidelity limits remain unresolved under DEC-011.
Current practical Rules do not establish exact aggregate integrity against a
malicious owner. The documentation does not grant production cutover authorization.

## First recommended next action

Send this four-document diff through independent `firebase_reviewer`, then handoff.
After FB-705 completion, implement/review FB-709 before selecting FB-706 final suites.
No commit or self-approval was performed.
