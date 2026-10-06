# FB-504 development cleanup proof

This is a **development-only** proof for `fluxit-dev`. Run it in Google Cloud
Shell under an account authorized to administer that project. It writes only a
new, uniquely named synthetic subtree under `users/fb504probe-*`. It does not
invoke the function or change the Scheduler job. The next normal 03:00 UTC
invocation will exercise the deployed function. Do not run the job manually:
`cleanupExpiredData` scans the entire development database and bucket.

Do not paste access tokens, full logs, real user documents, or app photo paths
into the migration tracker or chat. If any command fails, stop and report only
the command step and a sanitized error. Do not retry with broader IAM roles or
against another project.

## 1. Record deployment metadata before writing the fixture

In Cloud Shell, run these read-only commands. The function should be `ACTIVE`,
`nodejs22`, in `us-central1`, with a nonempty revision and update time. The
Scheduler job should be `ENABLED`, `0 3 * * *`, and `Etc/UTC` (or an equivalent
UTC time-zone label). Record these metadata values, without the job's HTTP
target or service-account details.

```bash
gcloud functions describe cleanupExpiredData --gen2 --region=us-central1 --project=fluxit-dev --format='json(name,state,buildConfig.runtime,serviceConfig.revision,updateTime)'
gcloud scheduler jobs describe firebase-schedule-cleanupExpiredData-us-central1 --location=us-central1 --project=fluxit-dev --format='json(state,schedule,timeZone,scheduleTime,lastAttemptTime,status)'
```

If the function or job is absent, paused, in an error state, or configured for
a different project, region, runtime, or schedule, stop before writing the
fixture. This closes the metadata bound tracked as `FB-507-NB1`; the Console
deployment report alone did not provide these exact fields.

## 2. Create a six-document synthetic fixture

Paste this entire block into Cloud Shell, excluding the Markdown fence. It
prints a unique probe UID: **save that UID** for the next two steps. The
OAuth token stays inside the shell variable and is not printed. The create API
fails if a document already exists, so this block cannot overwrite app data.
The `deletedAt` timestamp is 31 days old, past the shared 30-day cutoff.

```bash
(
  set -euo pipefail
  project=fluxit-dev
  test "$(gcloud projects describe "$project" --format='value(projectId)')" = "$project"
  uid="fb504probe-$(date -u +%Y%m%d%H%M%S)-$$"
  echo "FIXTURE_ID uid=$uid; save this even if a later step fails"
  old=$(date -u -d '31 days ago' +%Y-%m-%dT%H:%M:%SZ)
  token=$(gcloud auth print-access-token)
  base="https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents"
  create() {
    curl --fail-with-body --silent --show-error --request POST \
      --header "Authorization: Bearer $token" \
      --header "X-Goog-User-Project: $project" \
      --header 'Content-Type: application/json' \
      --data "$3" "$base/$1?documentId=$2" >/dev/null
  }
  create users "$uid" '{"fields":{"probe":{"stringValue":"FB-504"}}}'
  create "users/$uid/lists" expired \
    "{\"fields\":{\"deletedAt\":{\"timestampValue\":\"$old\"}}}"
  create "users/$uid/lists/expired/items" child \
    '{"fields":{"probe":{"stringValue":"cascade-child"}}}'
  create "users/$uid/lists" active \
    '{"fields":{"probe":{"stringValue":"retained-list"}}}'
  create "users/$uid/lists/active/items" old-item \
    "{\"fields\":{\"deletedAt\":{\"timestampValue\":\"$old\"}}}"
  create "users/$uid/lists/active/items" retained-item \
    '{"fields":{"probe":{"stringValue":"retained-item"}}}'
  echo "FIXTURE_READY uid=$uid at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
)
```

The expired list and its active child test list cascade; the expired item
under the active list tests standalone item cleanup. The active list and item
are negative controls that must survive. This fixture contains no photos.
Cloud Storage creation time cannot be backdated, so a same-day synthetic photo
cannot satisfy the backend's 30-day photo-age check. Photo cleanup remains
covered by the emulator/unit tests and the separate live Storage-generation
precondition probe; this procedure does not claim a live photo deletion.

## 3. Check after the next normal 03:00 UTC run

Wait until the job's `lastAttemptTime` is later than the `FIXTURE_READY` time.
Run the following read-only command after 03:00 UTC to get the actual attempt
time and latest target status. Check that the latest attempt succeeded (status
code `0` when present, or the Console's latest-run success indicator) before
running the document assertions. An absent `lastAttemptTime`, an older attempt,
or any failure leaves backend proof pending.

```bash
gcloud scheduler jobs describe firebase-schedule-cleanupExpiredData-us-central1 --location=us-central1 --project=fluxit-dev --format='json(state,schedule,timeZone,lastAttemptTime,status)'
```

Then run this block with the UID
printed above. It reads only the known synthetic paths and prints status codes,
not document contents. Expect `PASS`: the expired list, cascade child, and old
item are absent; the active list and retained item still exist.

```bash
(
  set -euo pipefail
  project=fluxit-dev
  uid=REPLACE_WITH_FIXTURE_UID
  [[ "$uid" =~ ^fb504probe-[0-9]{14}-[0-9]+$ ]] || { echo 'Invalid probe UID'; exit 1; }
  token=$(gcloud auth print-access-token)
  base="https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/users/$uid"
  code() {
    curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --header "Authorization: Bearer $token" \
      --header "X-Goog-User-Project: $project" "$base${1:+/$1}"
  }
  expect() {
    actual=$(code "$1")
    if test "$actual" != "$2"; then
      echo "FAIL: $3 returned HTTP $actual; expected $2"; exit 1
    fi
  }
  expect '' 200 'probe owner'
  expect lists/expired 404 'expired list'
  expect lists/expired/items/child 404 'cascade child'
  expect lists/active/items/old-item 404 'old item'
  expect lists/active 200 'active list'
  expect lists/active/items/retained-item 200 'active item'
  echo 'PASS: expired list, cascade child, and old item removed; active list and item retained'
)
```

The check must be paired with the post-fixture Scheduler attempt time and a
successful invocation, since missing documents alone would not identify which
backend run removed them. If any assertion fails, report `FAIL` and the first
failing path's category (expired list, cascade child, old item, active list, or
active item), without pasting document data. Do not remove the mobile purge on
a failed or unobserved run.

## 4. Remove the known synthetic leftovers

After recording the result and after any attempted cleanup invocation has
finished, replace the UID and run this block. It targets only the five fixture
documents, the one possible `listCleanupJobs/expired` claim, and the synthetic
user document; 404 is accepted because the backend may already have deleted
them. The fixture has no `photoRef`, so it cannot create photo-journal children
under that job. Children are explicitly deleted before their parents because
deleting a Firestore parent document does not delete its subcollections.

```bash
(
  set -euo pipefail
  project=fluxit-dev
  uid=REPLACE_WITH_FIXTURE_UID
  [[ "$uid" =~ ^fb504probe-[0-9]{14}-[0-9]+$ ]] || { echo 'Invalid probe UID'; exit 1; }
  token=$(gcloud auth print-access-token)
  base="https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/users/$uid"
  remove() {
    result=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --request DELETE --header "Authorization: Bearer $token" \
      --header "X-Goog-User-Project: $project" "$base/$1")
    test "$result" = 200 || test "$result" = 404
  }
  remove lists/expired/items/child
  remove lists/active/items/old-item
  remove lists/active/items/retained-item
  remove lists/expired
  remove lists/active
  remove listCleanupJobs/expired
  result=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --request DELETE --header "Authorization: Bearer $token" \
    --header "X-Goog-User-Project: $project" "$base")
  test "$result" = 200 || test "$result" = 404
  echo 'PROBE_REMOVED'
)
```

Report to the orchestrator: project `fluxit-dev`; function state, runtime,
region, revision, update time; Scheduler state, cron, time zone, and post-fixture
attempt time/status; fixture `PASS`/`FAIL`; and `PROBE_REMOVED` when complete.
The UID itself is synthetic metadata and need not be shared.
