# FB-507 development deployment procedure

Environment: **development**, project `fluxit-dev`, bucket
`fluxit-dev.firebasestorage.app`. This procedure deploys only the reviewed
Firestore Rules/indexes and the `cleanupExpiredData` function. The repository's
`.firebaserc` defaults to emulator-only `demo-fluxit`, so every live CLI command
below names `fluxit-dev` explicitly. Run from the reviewed `epic/firebase`
checkout at or after commit `f070093`. Use Node.js 22 for the local CLI.

The scheduled function can delete documents and photos older than 30 days.
Confirm that `fluxit-dev` is the intended **development** project before any
deploy command. Do not run these commands against production. Do not share or
commit service-account keys, tokens, passwords, signing secrets, or user data.

## 1. Probe the Storage generation condition

`FB-502-NB1` exists because the local Storage emulator ignored
`ifGenerationMatch`. In Google Cloud Shell signed into the development project,
run this self-contained probe. It creates and removes one synthetic object under
`migration-probes/`; it never touches app photo paths. The stale-generation
delete must fail, the replacement must remain, and the current-generation
delete must succeed. This checks the live Cloud Storage condition; the Node
client's forwarding of `ifGenerationMatch` was separately inspected in source.

```bash
(
  set -euo pipefail
  project=fluxit-dev
  bucket=fluxit-dev.firebasestorage.app
  gcloud storage buckets describe "gs://$bucket" --project="$project" --format='value(name)' >/dev/null
  scratch=$(mktemp -d)
  trap 'rm -f "$scratch/old" "$scratch/new"; rmdir "$scratch"' EXIT
  object="gs://$bucket/migration-probes/generation-$(date +%s)-$$"
  printf old > "$scratch/old"
  printf new > "$scratch/new"
  gcloud storage cp "$scratch/old" "$object" --if-generation-match=0 --project="$project" --quiet
  old_gen=$(gcloud storage objects describe "$object" --project="$project" --format='value(generation)')
  gcloud storage cp "$scratch/new" "$object" --if-generation-match="$old_gen" --project="$project" --quiet
  new_gen=$(gcloud storage objects describe "$object" --project="$project" --format='value(generation)')
  test -n "$old_gen" && test -n "$new_gen" && test "$old_gen" != "$new_gen"
  if gcloud storage rm "$object" --if-generation-match="$old_gen" --project="$project" --quiet; then
    echo 'FAIL: stale-generation delete succeeded'; exit 1
  fi
  still_gen=$(gcloud storage objects describe "$object" --project="$project" --format='value(generation)')
  test "$still_gen" = "$new_gen"
  gcloud storage rm "$object" --if-generation-match="$new_gen" --project="$project" --quiet
  echo 'PASS: stale delete rejected; replacement preserved; probe removed'
)
```

If this stops before printing `PASS`, stop the deployment and report the step
and sanitized error. A probe object may remain under its unique
`migration-probes/` path if Cloud Shell exits early; do not use an unguarded
delete to clean it up.

## 2. Deploy Firestore Rules and indexes

From the repository root on the development machine:

```bash
cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
node --version
firebase/node_modules/.bin/firebase --project fluxit-dev deploy --only firestore
```

`node --version` should report `v22.x`. This command publishes the checked-in
`firestore.rules` and `firestore.indexes.json` together. Firebase CLI Rules
deployment overwrites Console Rules. If the CLI proposes deleting indexes or
modifying an unexpected database, **stop** and report that proposal before
continuing. In Firebase Console for `fluxit-dev`, wait for the collection-group
single-field indexes on `listCleanupJobs.claimedAt`, `lists.deletedAt`,
`items.deletedAt`, and `items.photoRef` to be ready. The local emulator cannot
prove live index readiness.

## 3. Deploy only the reviewed function

Only after step 2 succeeds and the indexes are ready:

```bash
cd /Users/franzueto/AndroidStudioProjects/FluxItKMP-Simple
firebase/node_modules/.bin/firebase --project fluxit-dev deploy --only functions:cleanupExpiredData
```

The reviewed export is a second-generation scheduled function in
`us-central1` at 03:00 UTC, with a Node.js 22 runtime. Confirm in the Firebase
or Google Cloud Console that `cleanupExpiredData` exists in **fluxit-dev** and
record its deployment time and revision/version identifier if displayed. A
deployment is not a proof of cleanup behavior; `FB-504` owns the development
end-to-end check and removal of the mobile purge path.

## Safe evidence to send back

Report only: (1) probe `PASS` or sanitized failure; (2) Firestore Rules/indexes
deploy success and index-ready state or sanitized blocker; (3) function deploy
success, project ID, region, runtime, and deployment time/revision if available.
Do not paste credentials, full logs, Firestore documents, Storage paths from app
data, or personal data.
