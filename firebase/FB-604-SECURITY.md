# FB-604 development security verification

The runner exercises deployed Rules through two real, authenticated JavaScript
Firebase client SDK sessions and an anonymous session. It creates only fresh
synthetic accounts and fixture paths beneath those accounts. It verifies exact
`permission-denied` or `storage/unauthorized` responses; network errors fail.
Admin access is used only for read-only IAM preflight, synthetic Auth provisioning
and recovery, and exact Firestore fixture teardown. Storage cleanup uses each
synthetic owner's authenticated client. Neither Rules nor the cleanup scheduler
is changed or invoked.

## Execute against the reviewed development deployment

Use Node 22 and the existing local Firebase CLI login. Both `firebase/` and
`functions/` dependencies must be installed from their lockfiles. The gitignored
`composeApp/google-services.json` supplies the project, bucket and client API key.
Confirm locally that this is the same **development** configuration accepted in
MAN-007. Never execute against production. Do not paste identifiers, credentials,
raw CLI/native logs, document contents, or fixture manifests into chat or Git.

From the repository root:

```bash
(
  set -euo pipefail
  cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
  export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
  node firebase/security/preflight.js
  node firebase/security/run.js --development --execute
)
```

Preflight refreshes the existing CLI login in memory and checks Firestore
get/list/create/update/delete and Auth get/create/update/delete permissions. It does
not read existing app documents, create a fixture or require Storage Admin IAM.
An absent CLI login, insufficient project IAM, emulator environment variable,
non-Node-22 runtime, outstanding manifest, or drift from the reviewed deployment
asset commit stops execution. The CLI login cache is read but never rewritten.
The runner requires explicit development execution flags; it does not deploy.

The first client check uploads, reads and deletes a tiny Storage sentinel and
verifies absence before creating document fixtures. Passwords are random and stay
in memory. Each new UID is collision-checked before a local recovery manifest is
armed. The manifest is `firebase.fb604-run.json`, gitignored by `firebase.*.json`,
written with mode 0600. It contains synthetic identities and planned paths plus
local project metadata, and contains no passwords or tokens.

The final result must include both the suite count and:

```text
PASS exact-fixture-cleanup-verified owners=2 documentPaths=16 photoPaths=13 remaining=0
```

A nonzero exit, incomplete teardown or missing cleanup line is a failure. Report
only the environment, date, git revision, assertion count, failing check category
and sanitized error code, and cleanup counts. The manifest is removed only after
verified teardown. Photo bytes are synthetic; MIME checks concern the declared
metadata, since Rules cannot validate image decoding.

## Interrupted execution and bounded recovery

Do not start another run while the manifest exists. From the same checkout and
local development configuration:

```bash
(
  set -euo pipefail
  cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
  export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
  node firebase/security/run.js --development --cleanup
)
```

Recovery validates the manifest environment/project/bucket, run UUID, exact UID
and email conventions, and photo ownership. It looks up only those two synthetic
UIDs and verifies matching emails before changing anything. It resets a matching
synthetic account's password in memory to obtain a fresh client login, or recreates
this run's absent synthetic UID for absence verification, deletes
and verifies each recorded photo, then deletes exact planned Firestore children
before parents and verifies absence. Auth accounts are deleted last and verified
absent. It never lists the bucket or recursively deletes a database subtree.
Original test-client Firestore writes are terminated and all original client
apps are disposed before teardown. Storage provider disposal cancels and aborts
requests and prevents new work; the runner also awaits the underlying operation
promises before fresh recovery clients verify fixture absence.

If a photo cannot be removed or its absence cannot be verified, the synthetic
account and manifest remain for recovery; that is a failed run, not a pass.
If unexpectedly permissive Rules accepted a malformed photo path whose owner
cannot subsequently delete it, stop and have an authorized development bucket
administrator remove **only the exact synthetic path recorded in this local
manifest** and verify absence. Do not request broader IAM, delete unrelated
objects, or run the scheduled cleanup function. Retry `--cleanup` afterward.
Do not share the manifest. The orchestrator routes any required user action.

## Local rerun and failure-path verification

Use only the demo project for the standalone JavaScript harness:

```bash
(
  set -euo pipefail
  cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
  export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
  export XDG_CONFIG_HOME=/tmp/fluxit-fb604-emulator-config
  firebase/node_modules/.bin/firebase --config firebase.json --project demo-fluxit emulators:exec --only auth,firestore,storage "node firebase/security/run.js --emulator"
)
```

Run the same emulator command with `--emulator --verify-cleanup-failure` to
inject failure immediately after an owner upload. Expected: script exit 1,
`INJECTED_FIXTURE_FAILURE`, verified cleanup with `remaining=0`, and no manifest.
This is a teardown test and is not a successful acceptance run. The emulator-only
`--verify-partial-auth-failure` variant stops after creating the first account;
`--verify-pending-storage-failure` starts an upload and stops before awaiting it.
Both must exit 1 and report verified zero leftovers. The latter also reports the
settled upload cancellation code before cleanup verification. The original
full Rules/config/query matrix remains `cd firebase && npm test`.

## Coverage and evidence bounds

- Both owners can create/read their profile, list and item. Owner edits,
  completion/counters, item/list soft delete and restore, and item hard delete
  succeed. Client profile/list hard deletion stays denied.
- Both cross-user directions and anonymous clients are denied profile/list/item
  read, create, update, delete, and list/item queries. Exact schema, immutable
  fields, required parent counter movement, foreign photo references and private
  cleanup-job protections are checked.
- Deployed owner list/item queries and the native adapters' two-equality
  `clearCompleted` query with limit 400 return expected server results, including
  exclusion after item deletion.
- Owner JPEG/PNG/WebP metadata and exactly 5 MiB uploads/read/delete succeed.
  Both photo cross-user directions, anonymous access, overwrite, empty/oversize
  content, unsupported/missing MIME and malformed paths are denied. Owner content
  remains unchanged after denial attempts.

This suite proves live development client/Rules behavior using JavaScript SDKs.
It does not prove native Android/Apple cloud runtime or backend collection-group
index runtime/readiness; the latter deployment evidence remains MAN-007 user
attestation. It does not prove exact aggregate counter integrity beyond the
reviewed FB-601-NB1 bounds, nor byte-level image decoding or aged-photo cleanup.
Native emulator self-checks are a separate evidence tier. Keep their existing
emulator-build and launch-argument gates intact.

Official semantics: [Admin and credentials](https://firebase.google.com/docs/admin/setup),
[Firestore IAM](https://cloud.google.com/firestore/docs/security/iam),
[Storage Rules conditions](https://firebase.google.com/docs/storage/security/rules-conditions).

## Native iOS emulator rerun

This keeps all native self-checks emulator-only. Use a booted simulator, a
concrete destination, and ad-hoc signing for Firebase Auth Keychain access.
The flags apply to this command only and do not change `gradle.properties`.
Build first, then run only local Auth/Firestore/Storage emulators. The emulator
project comes from local config to match the native app's bundled client config;
all three native endpoints are redirected to localhost before use.

```bash
(
  set -euo pipefail
  cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
  export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
  fluxit_fb604_device=$(xcrun simctl list devices booted -j | node -e 'let s="";process.stdin.on("data",d=>s+=d);process.stdin.on("end",()=>{const devices=Object.values(JSON.parse(s).devices).flat().filter(d=>d.state==="Booted");if(devices.length!==1)throw new Error("Exactly one booted simulator required");process.stdout.write(devices[0].udid)})')
  env 'ORG_GRADLE_PROJECT_fluxit.firebase.emulator.enabled=true' 'ORG_GRADLE_PROJECT_fluxit.firebase.repositories.enabled=true' \
    xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -derivedDataPath /tmp/fluxit-fb604-ios -destination "platform=iOS Simulator,id=$fluxit_fb604_device" \
    CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build \
    > /tmp/fluxit-fb604-ios-build.log 2>&1
  fluxit_fb604_project=$(node -e 'const fs=require("node:fs");process.stdout.write(JSON.parse(fs.readFileSync("composeApp/google-services.json")).project_info.project_id)')
  export XDG_CONFIG_HOME=/tmp/fluxit-fb604-native-config
  firebase/node_modules/.bin/firebase --config firebase.json --project "$fluxit_fb604_project" emulators:exec --only auth,firestore,storage \
    "python3 firebase/security/native-ios.py --app /tmp/fluxit-fb604-ios/Build/Products/Debug-iphonesimulator/FluxIt.app --device $fluxit_fb604_device" \
    > /tmp/fluxit-fb604-native-emulators.log 2>&1
)
```

The runner verifies the generated emulator build gate, uses only the existing
Storage/item launch arguments, terminates the app between checks and on exit,
and requires a complete report with zero failures. Detailed native console logs
stay in an automatically created local temporary directory. Swift print output
uses PTY/unbuffered capture so a completed report is observable. Native fixtures
are local emulator data, discarded when the process stops. Normal packaged builds
must be rebuilt without these command-local flags before ordinary app use.
