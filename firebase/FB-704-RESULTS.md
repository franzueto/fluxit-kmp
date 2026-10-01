# FB-704 developer results

Implementation and fresh verification complete; independent review is pending.
Baseline: `d85f68a87a70539df64104701b117039cbda3298` (FB-703).
Executed 2026-10-01 UTC; no deployment or production/manual PASS.

## Change

Removed the obsolete `PhotoContent.Loadable` branch and `decodeImageFile` expect/actual
APIs, including Android `BitmapFactory.decodeFile` and Apple file-loading imports.
Item detail renders downloaded bytes through the existing byte decoder. Removed stale
local-stub documentation from current source comments.

Preserved Firebase Storage adapter runtime bodies, contracts, photo preparation/resize,
picker byte reads, Firebase Rules/configuration, native sessions and backend behavior.
Added two real byte-decoder tests on each platform: valid PNG dimensions and corrupt/
empty input handling. Historical FB-701/702/703 evidence remains unchanged.

## Fresh evidence

| Check | Result |
| --- | --- |
| Clean Gradle build/test | PASS; 149/149 tasks executed with `--rerun-tasks` |
| Shared/platform unit tests | Android Debug230, Release230, Apple295; failures/errors/skips0 |
| Ordinary Android and Apple Debug/Release builds | PASS; fresh Apple derived data, simulator ad-hoc signing |
| Real two-platform Firebase contract | PASS;16 checkpoints/platform, bidirectional realtime/photos, byte equality, replacement/deletion, offline pending/reconnect acknowledgment |
| Targeted Android photo/availability/decoder tests | PASS12, including2 new real decoder tests |
| Targeted Apple photo assertions | PASS43: Storage22, interrupted replacement12, publish5/subscribe4 |
| Cleanup request-construction tests | PASS4 mocked DELETE/POST/GET/loopback-only tests |
| Final exact fixture teardown | PASS;3 residual documents deleted; actual roots/lists/items/accounts/photos0; missing ancestor references0 |
| Ordinary flags/packages restored and installed | PASS; generated emulator=false |
| Repository/dependency/package inspection | PASS; obsolete photo paths, Room, AndroidX SQLite/bundled SQLite and diagnostic fixtures absent |

Apple unit count increased293→295 for the new decoder tests; Android decoder tests run
in instrumentation. The reused native runner separately removed its8 documents/1 account
and verified owned photos/recovery photos0. Fresh results are not inferred from historical
test reports. Existing nonfatal release lint metadata diagnostics remain.

Inspection covers current Kotlin sources, dependency output, every Debug/Release Android
DEX and packaged native library, both Apple app executables/debug dylibs, ordinary static
framework symbols and exported declarations. Firebase-required native persistence remains
under PLAN-009: Android system SQLite APIs and Apple Firebase/LevelDB dependencies are
retained; direct Apple `sqlite3.dylib` linkage was not observed. The byte decoder remains.

## Cleanup failure and correction

Two preliminary cleanup attempts failed and are recorded as failures. The helper passed
the method as urllib Request's fourth positional argument (`origin_req_host`), so intended
Firestore DELETEs became GETs. Three real synthetic photo-fixture lists remained, rather
than missing ancestor placeholders. Auth POST deletions had succeeded.

After quiescing both apps, retained trusted owner provenance and exact fixture-content/
descendant checks supported deleting only the three known paths. The helper now uses
`method=method`; four mocked tests prove request construction reaches transport correctly.
The corrected rerun separately verifies actual parent existence, live descendant collection
groups, accounts and objects: all0, with missing references0. Earlier delete counts were not
retained and are not invented. No global reset or cloud request occurred. Identity/path
manifests remain local only; committed evidence contains counts and hashes. Task-owned
emulators stopped normally, and ordinary packages/flags were restored.

## Reproduction and bounds

Exact commands, logs and cleanup explanation: [FB-704-PROCEDURE.md](FB-704-PROCEDURE.md).
Changed paths, source/test/log hashes and results: [evidence.json](photo-removal/evidence.json).
Artifact/dependency hashes: [packages.json](photo-removal/packages.json).

Source inventory158 paths; combined SHA-256:
`367ae3d3ff59d06ec450ebd6e487a1950c7e2607fa7ee30a197e7c57ff73283c`.
Run `python3 firebase/photo-removal/fingerprint.py --check` to verify it.

No literal reinstall, manual picker/radio gestures, full native/security/backend suite,
cloud/production deployment or physical-device runtime was executed. Android fresh named
client and Apple separate-process checks are the executed availability fidelity.
Physical-device runtime is permanently waived. FB-601-NB1 and MAN-005 remain open.
Broader README changes/final phase suite belong to FB-705/706 and were not performed.

Next action: independent FB-704 review, then handoff/status reconciliation by the
orchestrator. Stop after FB-704 DONE as requested; do not select FB-705.
