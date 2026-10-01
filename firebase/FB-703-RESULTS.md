# FB-703 developer results — pending independent review

Executed 2026-10-01 UTC on `epic/firebase`, initial HEAD
`5e33b5b51e9a0cad8b7baa0ef80aea61bfe08bed`. Canonical tracker belongs to the
orchestrator; this developer made no task-state change, commit or self-approval.

Removed Room entities, DAOs, database/constructor, repositories, Android/iOS builders,
Koin database definitions, Room/KSP plugins/catalog/dependencies/configurations and schema.
Removed the retired repository selector and its two tests. The UUID helper used by
`newPhotoId()` moved unchanged to `data/Ids.kt`; cloud photo adapters/behavior remain
intact for FB-704. Shared docs no longer describe active Room bindings.

Converted opt-in Room comparison fixtures into Firebase regression fixtures with the
same operations and 16 fixed expected observable checkpoints, preserving realtime,
offline and cloud photo checks. Their updated Swift launch hook and new strict-loopback
runner contain no Room branch. Historical FB-701/FB-702 runners/evidence are unchanged
and must be used with their archived source snapshots. Fixture **compilation** passed
here; live converted-fixture execution remains unrun in FB-703.

## Fresh results

| Check | Result | Local evidence |
| --- | --- | --- |
| Clean Gradle Android Debug/Release APKs, test APK, iOS Release framework and shared suites | PASS, `--rerun-tasks`, 149/149 tasks executed | `/tmp/fluxit-fb703-clean-gradle.log` |
| Android Debug/Release shared tests | 230/230 each, zero failures/errors/skips | JUnit XML under `composeApp/build/test-results/` |
| iOS simulator tests | 293/293, zero failures/errors/skips | Same XML directory |
| Ordinary iOS Debug/Release apps | Both `BUILD SUCCEEDED`, fresh derived data, ad-hoc signing | `/tmp/fluxit-fb703-default-{debug,release}-xcode.log` |
| Actual ordinary Android Koin graph | Firebase list/item, retired database definition absent; 1/1 | `/tmp/fluxit-fb703-default-android-di.log` |
| Actual iOS startup helper / ordinary routing diagnostic | Firebase list/item, retired database definition absent, Auth repository uncreated, default SDK host/SSL; no repository I/O | `/tmp/fluxit-fb703-default-ios-di.log` |
| Converted Android/Apple opt-in fixture compilation | PASS | `/tmp/fluxit-fb703-regression-compile.log`, `/tmp/fluxit-fb703-probe-xcode.log` |
| All resolved dependencies | Room/AndroidX SQLite/KSP absent; Android Debug/Release and both iOS compile graphs inspected | `/tmp/fluxit-fb703-dependencies.log` |
| Ordinary Android Debug/Release packages | Every DEX has no retired classes/AndroidX Room/SQLite; eight JNI entries per variant, only graphics-path/datastore-counter libraries, no SQLite library | `room-removal/packages.json` |
| Ordinary Apple Debug/Release binaries/frameworks | No Room/AndroidX SQLite symbols/types or converted test diagnostics; native Firestore present; no bundled SQLite file | Same package evidence; `nm`, `otool`, exported declarations |
| Verifier negative cases | Room dependency, bundled SQLite dependency, incomplete report each rejected exit 1; no evidence output | Three `/tmp/fluxit-fb703-negative-*.log` inputs |
| Restore/syntax/protected files | Ordinary app/test installs/frameworks restored; final emulator=false, compatibility=true; Python in-memory compilation/Bash syntax/diff check PASS; Rules/index/config/native adapters/photos/user agent settings unchanged from baseline | Restore logs and Git comparison |

Counts fell from FB-702's 232/232/295 to 230/230/293 solely because
`RepositoryBindingTest`'s two obsolete Room-selector tests were removed. These are
fresh clean executions, not reused FB-702 reports. The static expected trace was added
only to the opt-in regression sources after the clean shared run; final Android and
Apple fixture compilation includes that trace. It does not affect ordinary main/shared
suite sources.

Exact commands and bounds are in [FB-703-PROCEDURE.md](FB-703-PROCEDURE.md).
Machine verification returned:

```text
PASS FB-703 sources/dependencies/Android-Debug-Release/iOS-Debug-Release Room=absent AndroidX-SQLite=absent bundled-SQLite=absent Firebase-persistence=retained emulator=false
```

PLAN-009 preserves the required persistence: Android Firebase system SQLite references
remain and Apple native Firebase/LevelDB remains. No claim of blanket SQLite API/system
library removal is made. Apple `otool` found no direct sqlite3.dylib linkage in these
Debug/Release app binaries. Ordinary diagnostic exclusion is separate from the opt-in
iOS runtime probe.

Initial attempts had routine local limitations: sandbox denied the Gradle cache and
CoreSimulator; approved normal local access succeeded. Android Studio JDK25 configuration
failed with `25.0.3`; the fresh successful run used installed Zulu23. An initial Xcode
attempt was explicitly interrupted (exit75) before completion to serialize shared
framework writes; subsequent successful builds are the evidence above. Default Python
bytecode-cache output was sandbox-denied; in-memory compilation passed. Release lint
again emitted the existing Kotlin metadata-version diagnostics; the overall clean
Gradle build/tests and release packaging succeeded. None of these preliminary attempts
is counted as a successful acceptance check.

## Provenance, risks and next action

[room-removal/evidence.json](room-removal/evidence.json) fingerprints 164 source/resource/
config/script paths, including explicit removed-path markers. Combined SHA-256:
`16d018b9c6281c3c1b61c4cde8ea0847f43a43e40fe2427db9bc564e2d5fc89b`.
It also records the exact changed-path inventory, JUnit XML hashes and successful local
report hashes. [room-removal/fingerprint.py](room-removal/fingerprint.py) `--check`
reproduces this source inventory/hash. Package/dependency hashes are separate. Tracker,
prose/evidence/generated outputs and ignored mobile config contents are excluded from
the source fingerprint. Historical fingerprints remain frozen against their own commits.

Unrun: live converted 16-checkpoint/realtime/offline/photo regressions, full native
emulator suite, cloud-native/security/backend reruns, manual picker/radio gestures,
physical-device build/runtime, production release/deployment/index checks and aged cloud
photo cleanup. Firebase adapters/Rules/backend were unchanged; prior results remain
historical rather than fresh claims. MAN-003 stays permanently waived. FB-601-NB1 exact
malicious-owner counter architecture and MAN-005 production readiness remain open.

No deployment, live account/data change, privileged credential, ignored mobile config
or production/manual PASS was introduced. This source-only removal does not migrate or
erase existing on-device legacy database files. FB-704 owns obsolete local photo/path
cleanup and fresh two-platform photo regressions; FB-705 and later tasks were untouched.

First recommended action: independent read-only FB-703 review, then handoff and
orchestrator completion before selecting FB-704. For FB-704 use the Room-free
`room-removal/native.py` fixture runner rather than historical `parity/native.py`.
