# FB-608 / MAN-007 development Rules and indexes deployment

Reviewed asset commit: `6db83d997b7c2322e86501df874ec6d01dcdfb13` (`FB-603` DONE).

Run only after `FB-602` and `FB-603` are `DONE`, from their reviewed `epic/firebase` checkout. Environment: **development**. This publishes the checked-in Firestore Rules/indexes and Storage Rules. It does not deploy the existing scheduled cleanup function.

## 1. Verify the target locally

Use Node.js 22 and the installed Firebase CLI under `firebase/node_modules`. In a terminal at the repository root, run this Bash block:

```bash
(
  set -euo pipefail
  cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
  if [ -x /tmp/fluxit-node22/node_modules/node/bin/node ]; then
    export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
  fi
  node -e 'if (process.versions.node.split(".")[0] !== "22") throw new Error("Select Node.js 22 before proceeding")'
  fluxit_deploy_project=$(node -e 'const fs = require("node:fs"); const id = JSON.parse(fs.readFileSync("composeApp/google-services.json", "utf8")).project_info.project_id; if (typeof id !== "string" || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(id) || id.startsWith("demo-")) throw new Error("Invalid live project ID in local mobile config"); process.stdout.write(id)')
  printf 'Configured project: %s\n' "$fluxit_deploy_project"
  git branch --show-current
  git rev-parse --short HEAD
)
```

Confirm the displayed identifier matches the intended development project in Firebase Console. If it is production or unfamiliar, stop. Keep the identifier local; it comes from gitignored mobile configuration per `DEC-002d`.

## 2. Deploy the reviewed Rules and indexes

Only after checking the development target, run:

```bash
(
  set -euo pipefail
  cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
  if [ -x /tmp/fluxit-node22/node_modules/node/bin/node ]; then
    export PATH="/tmp/fluxit-node22/node_modules/node/bin:$PATH"
  fi
  node -e 'if (process.versions.node.split(".")[0] !== "22") throw new Error("Select Node.js 22 before proceeding")'
  fluxit_deploy_project=$(node -e 'const fs = require("node:fs"); const id = JSON.parse(fs.readFileSync("composeApp/google-services.json", "utf8")).project_info.project_id; if (typeof id !== "string" || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(id) || id.startsWith("demo-")) throw new Error("Invalid live project ID in local mobile config"); process.stdout.write(id)')
  git diff --quiet 6db83d997b7c2322e86501df874ec6d01dcdfb13 -- firebase.json firestore.rules firestore.indexes.json storage.rules || {
    printf '%s\n' 'STOP: deployment assets differ from the reviewed commit'; exit 1;
  }
  firebase/node_modules/.bin/firebase --config firebase.json --project "$fluxit_deploy_project" deploy --only firestore,storage
)
```

The pinned asset comparison must pass; it detects staged/unstaged asset edits and changes in later commits, while allowing tracker/procedure-only commits. If it fails, stop and report the changed filenames for review. Run interactively. If the CLI proposes deleting an index or modifying an unexpected database or bucket, decline and report the sanitized proposal. Do not add `--force` or a noninteractive approval flag. If login is required, use the normal Firebase CLI login locally; never paste tokens or credential files into chat.

CLI deployment overwrites Console Rules with the checked-in files. Expected targets are the configured `(default)` Firestore database and the configured development Storage bucket. Existing Phase 5 single-field overrides remain checked in; no new composite index is needed by the audited query inventory.

## 3. Confirm published versions and ready indexes

In Firebase Console for the same development project:

- Firestore → Rules: confirm the last published timestamp corresponds to this deployment; record the timestamp/version if displayed.
- Storage → Rules: confirm the same for the configured development bucket.
- Firestore → Indexes → Single field: confirm ascending `COLLECTION_GROUP` indexes for `listCleanupJobs.claimedAt`, `lists.deletedAt`, `items.deletedAt`, and `items.photoRef` are ready/enabled, with no building state/error. The composite list may be empty.

Do not invoke the cleanup function or Scheduler job for this gate. `FB-604` owns the deployed-development allowed-owner/denied-cross-user document and photo suite after this evidence is accepted. Local emulator query tests do not prove deployed index readiness.

## Safe evidence

Reply with the reviewed git commit, deployment success or sanitized error, Firestore and Storage published timestamps/version if displayed, and the readiness of those four collection-group indexes. State that the environment is development. Do not share full CLI logs, project identifiers, credentials, tokens, service-account keys, passwords, signing secrets, Firestore documents, Storage paths from app data, or user data.

Firebase CLI target semantics: https://firebase.google.com/docs/cli#partial_deploys
