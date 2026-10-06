# FB-702 developer results — pending independent review

Executed 2026-10-01 on the existing Android emulator and iPhone 17 / iOS 26.5
simulator. This switches production *source bindings* against development configuration
(DEC-011); no cloud deployment, production provisioning or manual PASS is implied.

Both actual Koin modules now select Firebase unconditionally. Explicit
`-Pfluxit.firebase.repositories.enabled=false` cannot select Room. The generated
repository boolean is constant true compatibility metadata; emulator routing remains
false in ordinary builds. Room definitions/packages/dependencies are retained for
FB-703, and local photo removal is FB-704. Neither task was implemented.

The Auth integration fixture moved into opt-in `firebaseParityIos`, with its Swift
hooks compiled only under `FLUXIT_PARITY`. Existing launch/emulator runtime gates
remain. SessionTrace and all production event calls/raw-UID formatting were removed;
session transitions, cancellation and ViewModel-store disposal still pass their tests.

## Fresh results

| Check | Result | Local report |
| --- | --- | --- |
| Default Android Debug / Release builds | PASS, retired property explicitly false | `/tmp/fluxit-fb702-default-gradle.log` |
| Shared Debug / Release / iOS tests | 232 / 232 / 295; zero failures/errors/skips | XML under `composeApp/build/test-results/`, same Gradle log |
| Actual ordinary Android app graph | Firebase list/item, Room factory uncreated; 1/1 | `/tmp/fluxit-fb702-default-android-di.log` |
| Actual iOS startup graph, default-routing diagnostic | Firebase list/item; emulator=false; Room/Auth repository factories uncreated | `/tmp/fluxit-fb702-default-ios-di.log` |
| Ordinary iOS Debug / Release simulator builds | Both PASS, ad-hoc signing | `/tmp/fluxit-fb702-default-debug-xcode.log`, `/tmp/fluxit-fb702-default-release-xcode.log` |
| Post-cutover Android native regressions | 88/88 | `/tmp/fluxit-fb702-android-full.log` |
| Post-cutover Apple native regressions | 138 assertions, zero failures, explicit listener-crash PASS | `/tmp/fluxit-fb702-ios-checks.log` |
| Two-platform Room/Firebase trace | 16 checkpoints/platform | `/tmp/fluxit-fb702-native.log` |
| Android ↔ Apple realtime/photos/offline | Bidirectional realtime, equal PNG bytes/replacement/two deletes; visible pending mutations/undo and server acknowledgement | Same native report |
| Scoped native fixture teardown | 8 documents/1 account deleted; accounts/lists/photos/recovery photos remaining zero | Same native report |
| Ordinary diagnostic exclusion | Auth/parity/default-graph/SessionTrace absent from iOS Debug/Release executable symbols and exported type declarations; new parity/SessionTrace absent from Android Debug/Release app DEX | `/tmp/fluxit-fb702-verification.log` |

The Apple 138 assertions include the moved Auth checks (22 + restoration 2/3), list
20, item 31, cross-client 17, Storage 22, interrupted replacement 12, photo publish
5/subscribe 4. The crash check uses an explicit PASS sentinel. Both emulator builds
also used the retired property explicitly false. No adapter or Rules change was needed.

Exact build/test/runtime/rollback commands are in
[FB-702-PROCEDURE.md](FB-702-PROCEDURE.md). The iOS default probe is explicitly an
opt-in diagnostic binary using the *same startup helper* as MainViewController;
ordinary binaries separately exclude it. Its empty SwiftUI route prevents auth UI
composition/restoration, and it performs no Auth or repository operation. Swift
checks the default Firestore host and SSL. Android uses the running ordinary app's
GlobalContext graph. No alternate binding module supplies either proof.

After the native checks, ordinary Android APK/test APK and iOS app installs were
restored; ordinary framework/header regeneration passed. Final generated flags are
emulator=false and repository metadata=true. The task-owned emulator CLI shut down
normally, discarding remaining legacy synthetic selfcheck fixtures. Raw local logs
and ignored mobile identifiers were not added to Git.

The machine-enforced report check exited zero:

```text
PASS FB-702 reports shared=232/232/295 native=88/138+crash parity=16/platform default-graphs=Firebase Room-uninitialized emulator=false ordinary-diagnostics=absent
```

An initial header-exclusion assertion matched historical KDoc references to the moved
Auth class. The check was corrected to inspect actual Objective-C interface/protocol
declarations, alongside executable-symbol checks; the fixture is absent from ordinary
binaries. Release lint emitted Kotlin metadata-version diagnostics, while the overall
Gradle build/tests succeeded; dependency/toolchain changes were outside this task.

`git diff --check`, Python compilation and procedure Bash syntax checks passed.
Protected Rules/indexes/emulator/mobile config, Room data/schema/dependency catalog,
native adapters and historical FB-701 evidence compare unchanged to the rollback SHA.

## Provenance and remaining bounds

[cutover/evidence.json](cutover/evidence.json) fingerprints 41 runtime source paths,
including explicit deletion markers, current FB-702 sources and unchanged FB-701
runtime sources. Combined SHA-256:
`6aebf9a08fe7ef601271fc786d325f5b4a13b6c92bccb29d5ca116abd7e1c390`.
Tracker/prose/evidence outputs are excluded. FB-701 reports/fingerprints are frozen
historical evidence at `a3b90bc2a6f38336e35d44a515fb424ecc026df0`; compare those hashes
to that commit, not the intentionally changed/moved FB-702 working-tree sources.
This full SHA is the immediate Room-binding rollback; earlier pre-P7 source rollback
remains `462bac331698f0a10beaa253919a510ce30c54ab`.

Unrun: physical-device/manual picker/radio gestures, native cloud/production release,
production-index enforcement and aged cloud-photo cleanup. Rules/backend suites were
not repeated because their source/config is unchanged; reviewed FB-701 evidence remains
historical, not a fresh FB-702 run. MAN-003 remains permanently WAIVED. Source cutover
does not prove Room/SQLite package removal or close the P7 gate.

FB-601-NB1 remains OPEN: normal-client counters pass, but malicious-owner exact
aggregate integrity needs the architecture decision before production-environment
cutover FB-707. MAN-005/provisioning is not inferred or waived here.

Next: independent read-only FB-702 review, then handoff and orchestrator completion/
commit. Recommend resolving FB-103-NB1 from the moved-fixture/nonshipping evidence.
Stop after FB-702 DONE; do not select or implement FB-703. This developer changed no
canonical task state, made no commit and did not self-approve.
