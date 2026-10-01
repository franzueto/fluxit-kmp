"""Inspect/clean exact synthetic fixtures in FB-704's initially empty LOCAL emulators.

Never resets an emulator or uses a cloud endpoint. Abort before writes if any account,
document hierarchy or photo path is outside this task's known photo-check namespace.
The state file is local-only; stdout contains counts, never identifiers or server bodies.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=['preflight', 'cleanup'], required=True)
parser.add_argument('--state', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
config = json.loads((root / 'composeApp/google-services.json').read_text())['project_info']
project, bucket = config['project_id'], config['storage_bucket']
namespace_hash = hashlib.sha256((project + '\0' + bucket).encode()).hexdigest()
state_path = Path(args.state)
auth = f'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/{urllib.parse.quote(project, safe="")}'
firestore = f'http://127.0.0.1:8080/v1/projects/{urllib.parse.quote(project, safe="")}/databases/(default)/documents'
storage = f'http://127.0.0.1:9199/v0/b/{urllib.parse.quote(bucket, safe="")}/o'


def request(url, body=None, method=None, missing_ok=False):
    parsed = urllib.parse.urlparse(url)
    assert parsed.scheme == 'http' and parsed.hostname == '127.0.0.1'
    assert parsed.port in {9099, 8080, 9199}
    req = urllib.request.Request(url, None if body is None else json.dumps(body).encode(),
                                 {'Authorization': 'Bearer owner', 'Content-Type': 'application/json'}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise RuntimeError(f'local-emulator-http-{error.code}') from None


def rows(url, key):
    result = request(url)
    assert not result.get('nextPageToken'), 'unexpected pagination: no deletion attempted'
    return result.get(key, [])


accounts = rows(auth + '/accounts:batchGet?maxResults=1000', 'users')
users = rows(firestore + '/users?showMissing=true&pageSize=1000', 'documents')
photos = rows(storage + '?maxResults=1000', 'items')
counts = {'accounts': len(accounts), 'users': len(users), 'photos': len(photos)}
if args.mode == 'preflight':
    assert counts == {'accounts': 0, 'users': 0, 'photos': 0}, 'task emulator namespace must start empty'
    state_path.write_text(json.dumps({'namespaceSha256': namespace_hash, 'initial': counts}) + '\n')
    print('PASS FB-704 local-fixture-preflight accounts=0 users=0 photos=0')
else:
    state = json.loads(state_path.read_text())
    assert state['namespaceSha256'] == namespace_hash
    assert state['initial'] == {'accounts': 0, 'users': 0, 'photos': 0}
    allowed_prefixes = ['fb304-photo-', 'fb307-photo-', 'fb305-a-', 'fb305-b-', 'fb307-interrupted-', 'fb703-']
    for account in accounts:
        email = account.get('email', '')
        assert email == 'fb307-crossdevice@example.com' or any(email.startswith(p) for p in allowed_prefixes), 'foreign account'
        assert email.endswith(('@example.com', '@example.test', '@example.invalid')), 'foreign account domain'
    uids = {a['localId'] for a in accounts} | set(state.get('validatedOwners', []))
    document_paths = []

    def collect(path, allowed_collections):
        # listCollectionIds includes descendants of missing parent documents.
        result = request(firestore + '/' + path + ':listCollectionIds', {'pageSize': 1000})
        assert not result.get('nextPageToken')
        collections = result.get('collectionIds', [])
        assert set(collections) <= set(allowed_collections), 'foreign document collection'
        for collection in collections:
            for row in rows(firestore + '/' + path + '/' + collection + '?showMissing=true&pageSize=1000', 'documents'):
                child = row['name'].split('/documents/', 1)[1]
                assert child.startswith(path + '/' + collection + '/')
                assert len(child.split('/')) == len(path.split('/')) + 2
                collect(child, ['items'] if collection == 'lists' else [])
        if request(firestore + '/' + path, missing_ok=True) is not None:
            document_paths.append(path)

    for user in users:
        path = user['name'].split('/documents/', 1)[1]
        assert path.startswith('users/') and len(path.split('/')) == 2
        assert path.split('/')[1] in uids, 'foreign document owner'
        collect(path, ['lists'])
    for photo in photos:
        parts = photo['name'].split('/')
        assert len(parts) == 5 and parts[0] == 'users' and parts[1] in uids and parts[2] == 'items', 'foreign photo path'
    # All paths/owners have been validated before the first write.
    # Keep the exact trusted namespace locally so an interrupted cleanup is resumable
    # even when Auth deletion succeeded but a late test write recreated a list.
    state['validatedOwners'] = sorted(uids)
    state['validatedDocumentPaths'] = document_paths
    state_path.write_text(json.dumps(state) + '\n')
    for photo in photos:
        request(storage + '/' + urllib.parse.quote(photo['name'], safe=''), method='DELETE')
    for path in document_paths:  # descendants were collected before parents
        request(firestore + '/' + path, method='DELETE')
    for account in accounts:
        request(auth + '/accounts:delete', {'localId': account['localId']})
    user_refs = rows(firestore + '/users?showMissing=true&pageSize=1000', 'documents')
    remaining = {'accounts': len(rows(auth + '/accounts:batchGet?maxResults=1000', 'users')),
                 'users': sum(request(firestore + '/' + u['name'].split('/documents/', 1)[1],
                                      missing_ok=True) is not None for u in user_refs),
                 'photos': len(rows(storage + '?maxResults=1000', 'items'))}
    for collection in ['lists', 'items']:
        result = request(firestore + ':runQuery', {'structuredQuery': {
            'from': [{'collectionId': collection, 'allDescendants': True}]}})
        remaining[collection] = sum('document' in row for row in result)
    assert remaining == {'accounts': 0, 'users': 0, 'photos': 0, 'lists': 0, 'items': 0}, 'fixture cleanup incomplete'
    state['deleted'] = {'accounts': len(accounts), 'documents': len(document_paths), 'photos': len(photos)}
    state['remaining'] = remaining
    state['missingAncestorReferences'] = len(user_refs) - remaining['users']
    state_path.write_text(json.dumps(state) + '\n')
    print(f'PASS FB-704 local-fixture-cleanup accounts={len(accounts)} documents={len(document_paths)} photos={len(photos)} remainingAccounts=0 remainingUsers=0 remainingLists=0 remainingItems=0 remainingPhotos=0 missingAncestorReferences={state["missingAncestorReferences"]}')
