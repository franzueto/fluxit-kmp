# FB-701 developer results — pending independent review

Executed 2026-10-01 on the Android emulator and iPhone 17 / iOS 26.5 simulator,
using official Android/Apple SDKs and local emulators with hardened checked-in
Rules. No production deployment, manual approval or physical-device PASS is claimed.
Rollback source: `462bac331698f0a10beaa253919a510ce30c54ab`.

Reproduction commands and flags are in [FB-701-PARITY.md](FB-701-PARITY.md).
Machine-readable sanitized counts and SHA-256 fingerprints of all 25 tested
source files are in [parity/evidence.json](parity/evidence.json). The combined
fingerprint is `f58c4cd2bbafcb92223d4c96f25f8b522aa5041ad000abe6db848f51358f177e`.
Canonical status is owned by the orchestrator and excluded from this fingerprint.

## Final-source results

These runs follow both product fixes: bounded same-item counter contention retry,
and pending server-timestamp estimates at the SDK snapshot boundary. Earlier
matrix counts are superseded, including the earlier 138-assertion Apple run before
the final retry predicate/offline changes.

| Check | Final result | Local report |
| --- | --- | --- |
| Android native SDK + Compose suite | 88 tests, zero failures | `/tmp/fluxit-fb701-android-full.log` |
| Apple native SDK suite | 138 assertions, zero failures; explicit dashboard listener crash check PASS | `/tmp/fluxit-fb701-ios-checks.log` |
| Room vs Firebase observable trace | 16 checkpoints on each platform | `/tmp/fluxit-fb701-native.log` |
| Same-account Android ↔ Apple | Both live listeners observe opposite-platform list/item edits and completion | Same native report |
| Native Storage | Equal PNG bytes in both directions, one replacement, two verified deletions | Same native report |
| Apple real SDK offline | Cached reads; visible queued list/item creation and individual observers; edit, tombstone/undo; reconnect server acknowledgement | Same native report |
| Android real SDK offline | Visible list/item writes and individual observers before reconnect, pending/cache metadata, server acknowledgement | Included in 88-test suite |
| Shared tests | Android Debug 232, Release 232, iOS 295; zero failures/errors/skips | XML under `composeApp/build/test-results/`; `/tmp/fluxit-fb701-final-unit-default.log` |
| Rules/config/queries | 64/64 | `/tmp/fluxit-fb701-rules.log` |
| Backend units | 8/8 | `/tmp/fluxit-fb701-backend-unit.log` |
| Fresh backend emulators | 13/13, including real emulated function invocation | `/tmp/fluxit-fb701-backend-emulator.log` |
| Default actual app DI | Room graph 1/1; Firebase graph separately included in flagged native suite | `/tmp/fluxit-fb701-default-di.log` |
| Ordinary builds | Android Debug + Release and iOS Debug successful | Final unit/default Xcode logs |

The Apple 138 assertions comprise Auth 22, restore preparation 2, restore verification
3, list 20, item 31, cross-client 17, Storage 22, interrupted replacement 12,
cross-device publish 5 and subscribe 4. The listener crash check has an explicit
PASS sentinel rather than assertion lines. The old cross-client pending-write SKIP
does not serve as offline evidence; the new native offline check supplies it.

The complete report parser was run exactly as follows and exited zero:

```bash
python3 firebase/parity/report.py --android /tmp/fluxit-fb701-android-full.log --apple /tmp/fluxit-fb701-ios-checks.log --native /tmp/fluxit-fb701-native.log
```

Its result was:

```text
PASS final-native-report Android=88 Apple=138+listener-crash parity=16/platform realtime=bidirectional photos=bidirectional offline=recovered fixtures=0
```

Native teardown deleted eight run-owned documents and one synthetic Auth account.
Exact-owner lookups verified zero accounts, lists or photos remaining; zero recovery
photos were required. Legacy synthetic selfcheck fixtures were discarded when
the task-owned native emulator process stopped. Fresh backend emulators exited and
shut down normally. Raw logs, local account details and ignored mobile identifiers
remain outside tracked evidence.

After native runs, this exact ordinary build/test command succeeded:

```bash
./gradlew :composeApp:testDebugUnitTest :composeApp:testReleaseUnitTest :composeApp:iosSimulatorArm64Test :composeApp:assembleRelease :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest > /tmp/fluxit-fb701-final-unit-default.log 2>&1
```

Ordinary Xcode used the procedure's default command without `ORG_GRADLE_PROJECT_*`
flags or `OTHER_SWIFT_FLAGS`. Generated emulator/repository flags are false/false.
Inspection confirmed the new parity types absent from the ordinary exported iOS
header, iOS executable/debug dylib and Android app DEX. Default APK/test APK and
ordinary iOS app were installed back onto the existing simulators. The default
Room DI check used the procedure's exact instrumentation class and passed 1/1.

Supplemental backend commands, with local Node 22 on `PATH`:

```bash
node --test functions/test/scaffold.test.js functions/test/cleanup.test.js functions/test/cascade.test.js > /tmp/fluxit-fb701-backend-unit.log 2>&1
env XDG_CONFIG_HOME=/tmp/fluxit-fb701-backend-config PATH=/tmp/fluxit-node22/node_modules/node/bin:$PATH firebase/node_modules/.bin/firebase --config firebase.json --project demo-fluxit emulators:exec --only functions,pubsub,firestore,storage 'node --test --test-concurrency=1 functions/test/emulator.test.js functions/test/cleanup.emulator.test.js functions/test/cascade.emulator.test.js functions/test/indexes.emulator.test.js' > /tmp/fluxit-fb701-backend-emulator.log 2>&1
```

`git diff --check`, Python compilation and procedure Bash syntax checks passed.
Rules, indexes and emulator configuration compare unchanged to the rollback SHA.

## Diagnosed failures and bounded corrections

- The legacy baseline rejected timestamp/malformed fixtures under hardened Rules.
  Valid writes now use server timestamps. Android historical malformed-read fixtures
  use a strictly loopback, fixed-demo emulator-admin seed; normal malformed client
  writes remain denied. Apple explicitly asserts permission-denied and exclusion.
- Normal concurrent same-item completion could receive permission-denied before
  the SDK retried stale counter deltas. Both adapters now allow at most three extra
  full transactions only after a server read proves relevant exact-item state changed.
  Fresh completion/delete/restore/hard-delete tests pass; a genuine stable counter
  Rules denial remains FORBIDDEN and leaves the item/counts unchanged. Existing
  clear-completed race outcomes and batch bounds remain covered; batch behavior
  was not silently converted into unbounded retries.
- Offline metadata alone had hidden a real defect: required pending timestamps
  decoded as null and excluded visible new list/item content. SDK estimates now
  preserve local visibility on both platforms, including individual observers and
  Apple tombstone/undo. Explicit stored nulls remain invalid. Final tests demand
  actual visible pending content and subsequent acknowledged server state.
- Fixtures no longer rely on a singleton's initial Auth state or arbitrary highest
  item ID/stale cache when repeating photo publication. The comparator waits for
  matching item content and parent counts independently. An early handwritten
  checkpoint count of 17 was corrected to the executable trace's actual 16.
- A backend attempt reused a demo namespace containing intentional Rules-test
  malformed journals and failed five of twelve tests. A fresh isolated emulator
  invocation produced 13/13, including the function invocation tier above.

## Bounds and next action

Feature content/order/counts and undo/retry are compared. UUIDs, server timestamps,
internal ranks, UID-scoped clean-cut adoption, pending/cache semantics and backend
30-day retention are intentional migration differences. Native offline evidence
uses SDK network toggling; physical radio gestures, manual picker/visual UI checks,
native cloud/production deployment and aged cloud-photo cleanup are unrun. MAN-003
remains permanently WAIVED for simulator-only scope. Emulator queries cannot prove
production composite-index enforcement (existing FB-603-NB1 bound).

FB-601-NB1 stays OPEN: normal-client counter consistency passes, but exact aggregate
integrity against malicious owner writes is not proven. The server-authoritative
architecture choice is still required before production-environment cutover FB-707;
development/source-binding work does not resolve that decision.

Next: independent read-only FB-701 review, then handoff and orchestrator commit.
For FB-702, use that reviewed commit as immediate rollback; select Firebase in both
actual production Koin modules while retaining Room types until FB-703. Move/remove
the shipped iOS Auth harness and bootstrap hooks (FB-103-NB1), and remove/silence
production SessionTrace raw-UID output. Any tracing retained for tests should be
explicit opt-in and contain no raw UID. This developer has not implemented FB-702
or changed canonical task status.
