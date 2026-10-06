# FB-604 sanitized verification results

Developer evidence only; independent review and canonical task transitions remain
with the orchestrator. No manual PASS, self-approval or commit is recorded here.

Date: 2026-09-30 America/Guatemala (final live completion observed at
2026-10-01 03:55:17 UTC). Source: branch `epic/firebase`, base HEAD `6888c5b`,
FB-604 uncommitted deliverables. Deployment asset comparison to reviewed
`6db83d997b7c2322e86501df874ec6d01dcdfb13` passed. Runtime tools: Node 22.23.3,
Firebase JavaScript SDK 11.10.0, Firebase CLI 14.27.0, Firebase Admin SDK 14.5.0.

Final security source-set SHA256: `09d9e140e3a05bebca77b1dea3ad3a1fe421bcf711d6d8620b0b5b474eae9e12`. Compute by sorting direct
files under `firebase/security`, then hashing each relative filename, one NUL
byte, and its file bytes, concatenated. This identifies the final code tested;
no runtime source changed after the final live run.

## Results

| Target | Result | Provenance |
|---|---|---|
| Deployed development client security suite | **112/112 PASS**, exit 0 | Agent-executed real authenticated JavaScript client SDK calls against the local-config development target; owner and anonymous allowed/denied matrix |
| Deployed exact teardown | **PASS; remaining 0** | 2 synthetic Auth owners, 16 exact Firestore document paths, 13 exact Storage photo paths verified absent; ignored manifest removed |
| Final demo-emulator security suite | **112/112 PASS** | Agent-executed through Auth/Firestore/Storage emulators; same assertion source |
| Pending Storage upload failure | **Expected exit 1; cleanup PASS** | Final teardown source canceled outstanding upload with `storage/canceled`, awaited settlement, verified 2 owners / 16 document paths / 1 photo path absent |
| Post-upload failure | **Expected exit 1; cleanup PASS** | Earlier refinement check: 2 owners / 16 document paths / 1 photo path, remaining 0 |
| Partial Auth provisioning failure | **Expected exit 1; cleanup PASS** | Earlier refinement check: absent run-owned UID recreated for owner verification, 2 owners / 16 document paths / 0 photo paths absent |
| Execution safety gates | **4/4 PASS**, exit 0 | Serial final Node test run; explicit development/cleanup flags, emulator prerequisites and refused live emulator routing |
| Native iOS Storage self-check | **22/22 PASS**, exit 0 | Existing real Swift/Kotlin FB-305 check, emulator-only ad-hoc signed Debug app on iOS 26.5 simulator; durable runner independently rerun by developer |
| Native iOS Firestore item self-check | **31/31 PASS**, exit 0 | Existing real Swift/Kotlin FB-205 item/query behavior, same emulator-only simulator app |
| Xcode builds | **BUILD SUCCEEDED**, exit 0 | Concrete simulator Debug builds, first unsigned then ad-hoc signed; emulator/repository flags command-local |
| Static checks | **PASS** | Four procedure Bash blocks `bash -n`; JavaScript syntax checks; Python compilation; `git diff --check`; ignored-manifest check; unchanged Rules/index comparison |

Final live stdout ends exactly:

```text
PASS development client-security-suite 112 assertions
PASS exact-fixture-cleanup-verified owners=2 documentPaths=16 photoPaths=13 remaining=0
```

The suite includes genuine denied fresh-profile creation before owner documents
exist, then denied existing-profile sets, so profile create and update cannot be
confused. Each denial requires the exact authorization error. Owner/server
results use the same endpoints. No project identifier, real user data, password,
raw token, credential file or actual fixture identity is included in this report.

## Commands

From repository root, the final live invocation was:

```bash
/tmp/fluxit-node22/node_modules/node/bin/node --test firebase/security/safety.test.js && /tmp/fluxit-node22/node_modules/node/bin/node firebase/security/run.js --development --execute > /tmp/fluxit-fb604-live-reviewed.log 2>&1
```

Final emulator normal/cancellation verification:

```bash
XDG_CONFIG_HOME=/tmp/fluxit-fb604-emulator-config PATH=/tmp/fluxit-node22/node_modules/node/bin:$PATH firebase/node_modules/.bin/firebase --config firebase.json --project demo-fluxit emulators:exec --only auth,firestore,storage "node firebase/security/run.js --emulator && node firebase/security/run.js --emulator --verify-pending-storage-failure" > /tmp/fluxit-fb604-disposal-emulator.log 2>&1
```

This command's exit 1 is intentional: the first suite passed 112/112 and its
teardown, then the second injected `INJECTED_PENDING_STORAGE_FAILURE`, settled
`storage/canceled` and verified teardown. It is not a failed security assertion.
Earlier variants used `--verify-cleanup-failure` and
`--verify-partial-auth-failure`; those likewise intentionally returned exit 1
with complete verified cleanup.

Read-only preflight:
`/tmp/fluxit-node22/node_modules/node/bin/node firebase/security/preflight.js`
returned `PASS development-admin-read-only-preflight`, exit 0.

Native final invocation used config-sourced explicit `--project`, local emulator
CLI configuration, and `python3 firebase/security/native-ios.py --app <local
Debug-iphonesimulator/FluxIt.app> --device <booted-simulator>`. Both placeholders
were substituted locally. See the complete reproducible build/emulator command
in [the security procedure](FB-604-SECURITY.md). Actual Xcode signing options were
`CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=
PROVISIONING_PROFILE_SPECIFIER=` with a concrete `platform=iOS Simulator,id=...`
destination; both `ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled` and
`ORG_GRADLE_PROJECT_fluxit.firebase.repositories.enabled` were `true` only in
that command's environment. Tracked defaults remain `false`.

## Diagnosed attempts and retained bounds

- Initial default-sandbox network access failed before any fixture; approved
  escalated checks succeeded. An initial requirement for bucket Admin IAM was
  unsatisfied. The chosen authenticated-owner Storage route then passed, using
  existing Admin Firestore/Auth access for only exact synthetic fixtures.
- Initial simulator access failed under sandbox; approved escalation worked.
  Initial native capture attempts timed out without a host final report;
  PTY/unbuffered capture plus ad-hoc signing produced complete reports. No gate
  was weakened and no product file changed.
- One safety-test invocation overlapped a live manifest and correctly failed its
  no-outstanding-fixtures prerequisite. The final serial invocation passed 4/4.
- Native Android SDK checks were not rerun this task. Neither mobile platform
  executed against cloud; existing iOS self-checks still refuse non-emulator
  configuration. JavaScript cloud queries and native iOS emulator queries are
  distinct evidence tiers.
- Backend collection-group index runtime was not exercised against cloud because
  an unscoped Admin query could touch existing app data. MAN-007's index-readiness
  attestation and FB-608-NB1's deployment-revision bound remain unchanged.
- FB-601-NB1 exact aggregate-counter integrity, FB-602-NB1 invalid-byte taxonomy,
  and FB-504-NB2 aged-photo cleanup bounds remain. Rules validate declared MIME
  and size; these tests do not claim image decoding or aged-photo deletion.
- The local iOS hardened-Rules Storage runtime gap in FB-602-NB2 now has direct
  emulator evidence; the orchestrator/reviewer determine its disposition.

No further user action is needed for the achieved development matrix. The first
recommended action is independent read-only FB-604 review, followed by handoff.
