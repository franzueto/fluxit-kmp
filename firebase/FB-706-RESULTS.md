# FB-706 developer results

Final automated verification passed on 2026-10-01 against reviewed product commit `9281019e8e22fc98ee2d4a0ebd75760dc6c438d6`, with the uncommitted FB-706 verification diff. This is developer evidence for independent review, not self-approval or a canonical transition. No product, Rules, backend, mobile configuration, agent/workflow or historical evidence change was made. The only existing test adjustment updates Android's actual Auth DI expectation to the reviewed `SessionAuthRepository` wrapper. New scripts and receipts belong to `final-verification`; historical scripts/reports remain frozen.

DEC-012 keeps closure development-only and explicitly waives production provisioning. The permanent physical-iOS waiver remains. PLAN-012 requires human evidence or an explicit named waiver through FB-707 / MAN-008 for the [remaining literal checks](FB-706-MANUAL.md); no such result or waiver is inferred here.

## Fresh executed matrix

| Tier | Final result | Practical coverage |
|---|---|---|
| Android Debug shared/platform unit | 250/250; failures/errors/skips 0 | Fresh `--rerun-tasks` including lifecycle and bounded download tests |
| Android Release shared/platform unit | 250/250; failures/errors/skips 0 | Same current source |
| iOS simulator unit | 311/311; failures/errors/skips 0 | Real iOS decoder/transform plus common lifecycle/gate tests |
| Rules/config/storage/query | 64/64 | Config4 + Rules/storage/query60; no Rules modification |
| Security fail-closed safety | 4/4 | Final sequential run; an earlier concurrency error is retained below |
| Security emulator client matrix | 112/112 | Exact scoped fixtures; remaining 0 |
| Security intentional-failure cleanup | 3/3 expected outcomes | After-upload, partial-Auth and pending-Storage injection each returned expected exit1/category and verified scoped cleanup remaining0 |
| Development cloud client security | 112/112 | Fresh reviewed exact-synthetic runner after read-only IAM preflight; owners2 / document paths16 / photo paths13 / remaining0; ignored manifest removed |
| Backend local syntax/unit | 8/8 | `npm run check` |
| Backend emulator integration | 13/13 | Normal counters, cursor/delete jobs and photo maintenance against owned local emulators; no cloud backend inference |
| Android full native instrumentation | 90/90; failures/errors/skips 0 | Complete ordinary test inventory excluding only the two opt-in fixture classes; actual Koin DI, Auth, list/item/cross-client, photo/availability/metadata/offline/errors and decoder checks |
| Apple emulator selfchecks | 11/11 launches; 138 explicit assertions plus fatal-listener no-crash checkpoint | Auth22, restoration prepare2/verify3, list20, item31, cross-client17, photo22, interrupted replace12, publish5/subscribe4 and dashboard fatal-session/no-crash check; console selfchecks, not XCTest |
| Actual Koin native contracts | 16 checkpoints per platform | Same-account Android↔Apple realtime edits, equal photo bytes in both directions, replace/delete and Apple cached read/pending write/reconnect acknowledgment |
| Ordinary default routing / DI | Android3/3 and Apple graph probe PASS | Auth/List/Item session wrappers and no Room definition; Apple raw Auth factory remains uncreated; no credential/repository I/O |
| Lease safety tests | 6/6 | Remote endpoints, nonempty baseline, unarmed/exported lease, foreign process group and missing app/source proofs refused |
| Ordinary build/package/dependency matrix | PASS Debug and Release Android+iOS | Fresh Android APKs/frameworks and Xcode apps; all four dependency graphs scanned; Room/AndroidX SQLite/bundled SQLite/retired photo paths/new and historical parity diagnostics absent; native Firebase/system persistence retained |
| Final restoration | PASS | Ordinary Debug installed APK/Apple binaries match hashes; task instrumentation APK absent; generated emulator=false/Firebase-repositories=true; owned task ports closed |

Exact commands, flags, device/package paths and sequencing are in [the procedure](FB-706-PROCEDURE.md). Fresh ordinary Gradle matrix: `/tmp/fluxit-fb706-ordinary-gradle.log`, BUILD SUCCESSFUL, 121/121 tasks executed. Final ordinary Xcode logs: `/tmp/fluxit-fb706-ordinary-xcode-debug.log` and `/tmp/fluxit-fb706-ordinary-xcode-release.log`, BUILD SUCCEEDED. Dependency/package/restoration receipts: `/tmp/fluxit-fb706-dependencies.log`, `/tmp/fluxit-fb706-packages.json`, `/tmp/fluxit-fb706-restoration.json`. JUnit XMLs, raw native/build/security/backend logs, binaries and local inventories are individually hashed in [sanitized evidence](final-verification/evidence.json). The evidence verifier compares the exact changed/untracked task inventory against declared ownership plus the orchestrator-owned tracker, verifies protected files/current source and optionally rehashes all retained local receipts.

## Native fixture disposition and safety provenance

PLAN-011 native r2 used a fresh task-owned unexported Auth/Firestore/Storage process group. Before any SDK fixture write it refused existing endpoint listeners, persisted the exact process/group/endpoints/configured and demo namespaces/buckets/suite lease and app/source hashes, and verified actual roots, collection-group lists/items/cleanup jobs, accounts and photos zero in both namespaces. The audited SDK fixture routes were emulator-gated. No import/export, shared dataset, prefix ownership admission, global reset or inferred-owner deletion occurred.

Final r2 log: `/tmp/fluxit-fb706-isolated-native-r2.log`; lease/initial/residual inventories and child logs: `/var/folders/2q/1rw05q3s6ds294x7k6sd9ng40000gn/T/fluxit-fb706-isolated-native-ekyb_cy6`. Its actual residue was:

| Namespace | Roots | Lists | Items | Cleanup jobs | Accounts | Photos |
|---|---:|---:|---:|---:|---:|---:|
| Configured native | 7 | 15 | 27 | 0 | 92 | 0 |
| `demo-fluxit` secondary SDK clients | 52 | 56 | 55 | 0 | 0 | 6 |

These observations were captured before terminating only the owned child process group; every leased port then closed and the lease recorded `disposed=true`, `portsClosed=true`. This is **ephemeral fixture disposal**, not per-path deletion or an assertion that server residue was zero before shutdown. The random-ID native tier does not claim exact predeclared per-document ownership. Exact manifests/scoped cleanup still apply to development cloud/shared endpoints and the reviewed FB-709 proof. Raw lease/account/path observations contain synthetic data and stay local; only sanitized counts/hashes are committed.

## Retained reviewed evidence and bounds

Product source and native fixture behavior remain unchanged at reviewed `9281019`. FB-709's independently approved native lifecycle proof is retained, **not newly executed by FB-706**: actual Koin A→B/same-user cached-document absence, discarded/non-resubmitted queued writes, terminal live upload/download cancellation, cleanup failure/retry, and actual Android/iOS OS-process restart with independent server-baseline checks. Its source fingerprint remains `97999a9eca523976d3f829d07bc66c6f45119d02d8e36119334f13b56c8f74c7`; its frozen exact4-owner/12-document cleanup ended with actual roots/lists/items/accounts/photos0 in both namespaces. Fresh FB-706 units and wider native scenarios complement that proof without needlessly repeating the expensive unchanged lifecycle fixture.

The fresh JavaScript development security tier does not prove native development-cloud realtime/offline/photo/credential behavior, aged orphan cloud deletion, cloud backend cursor workloads, production deployment/provisioning or any real radio gesture. Existing reviewed native-cloud bounds remain. SDK network-disable, independent fresh client, scripted process launch and simulated replacement interruption are not literal radio, uninstall/reinstall, picker or user sign-out gestures. iOS selfchecks are simulator console checks; real-device signing, Keychain/hardware/memory-pressure background behavior remain permanently waived. Existing FB-601-NB1 malicious-owner aggregate-integrity redesign remains future-before-production; normal-client counters passed, and no stronger owner-security claim is made.

## Retained unsuccessful attempts

1. `/tmp/fluxit-fb706-security-safety.log` contains 1 pass / 3 failures. Safety tests were mistakenly run concurrently with the security matrix, which owns the same ignored manifest path; the manifest appeared during the negative checks. This was an orchestration error, not a demonstrated product/Rules failure. The security matrix itself passed and cleaned its fixtures. The final strictly sequential safety run `/tmp/fluxit-fb706-security-safety-final.log` passed 4/4; both logs remain hashed.
2. Native r1 `/tmp/fluxit-fb706-isolated-native-r1.log` stopped after the Android suite actually completed `OK (90 tests)` with no failures. The new wrapper still used the historical expected count88; it rejected completion rather than loosen the guard. It captured actual residue, disposed only its owned process and verified closed ports. The corrected strict90 guard was then executed in fresh r2, which passed every stage. The initial lease/raw logs/residual observations remain hashed; r1's wrapper is not counted as a full native matrix PASS.

3. The first local evidence replay rejected the generator journal because it had been hashed before its final output was appended. This was a self-referential receipt-selection error. The active generator/verifier journals are excluded from their own receipt set; the failed replay is retained in `/tmp/fluxit-fb706-evidence-verification-r1.log`. Final replay independently rehashes the immutable test/build/native receipts.

No failed attempt was removed or relabeled. There were no native product failures in final r2 and no product-policy workaround or production timing change.

## Remaining human owner and next action

The ten manual rows M01–M10 are all unrun by a human in FB-706. They cover ordinary UI/search/navigation, real picker/cancel/replacement/rejected input, literal reinstall photo availability, returning/background sessions, network/radio feedback/recovery, live UI realtime, undo, A→B warning/sign-out/retry and loading/error/pending controls. Optional accessibility/localization observations add no migration gate. The exact related automated coverage and fidelity limits are in the checklist. DEC-012 and the physical-iOS waiver do not waive these rows.

First recommended action: independent `firebase_reviewer` review of this exact final source/evidence/ownership inventory. The orchestrator/handoff can then reconcile FB-706 and request MAN-008 evidence or explicit narrow waivers in FB-707. No commit or canonical task/status change was made by the developer.
