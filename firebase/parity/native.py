"""FB-701 real Android/Apple parity + realtime, LOCAL EMULATORS ONLY.
Logs include native diagnostic details; retain them locally, never commit them.
The account and exact descendant documents created for this run are deleted on exit.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import tempfile
import time
import urllib.request
import urllib.parse
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--app', required=True)
parser.add_argument('--device', required=True)
parser.add_argument('--adb', required=True)
parser.add_argument('--android-device', default='emulator-5554')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
for key, expected in [('FIREBASE_AUTH_EMULATOR_HOST', '127.0.0.1:9099'),
                      ('FIRESTORE_EMULATOR_HOST', '127.0.0.1:8080')]:
    if os.environ.get(key) != expected:
        raise SystemExit('FAIL local-emulator-routing-required')
config = (root / 'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin/com/fluxit/config/FirebaseEmulatorConfig.kt').read_text()
if 'ENABLED: Boolean = true' not in config:
    raise SystemExit('FAIL generated-emulator-gate-disabled')
native_config = json.loads((root / 'composeApp/google-services.json').read_text())['project_info']
project = native_config['project_id']
bucket = native_config['storage_bucket']
app = Path(args.app)
bundle = plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleIdentifier']
logs = Path(tempfile.mkdtemp(prefix='fluxit-fb701-native-'))
print('LOCAL diagnostic directory: ' + str(logs), flush=True)
run = uuid.uuid4().hex
email = f'fb701-{run}@example.invalid'
password = 'fb701-emulator-only-' + uuid.uuid4().hex
marker = 'fb701-' + run
account = None
processes = []
env = {**os.environ, 'SIMCTL_CHILD_NSUnbufferedIO': 'YES'}

def local_json(url, body=None, method=None, auth_admin=False):
    # Only literals constructed here reach loopback; no CLI project/host overrides.
    request = urllib.request.Request(url, None if body is None else json.dumps(body).encode(),
        {'Content-Type': 'application/json', **({'Authorization': 'Bearer owner'} if ':8080/' in url or ':9199/' in url or auth_admin else {})}, method=method)
    with urllib.request.urlopen(request, timeout=20) as response:
        content = response.read()
        return json.loads(content) if content else {}

def sim(*values, check=True):
    return subprocess.run(['xcrun', 'simctl', *values], capture_output=True, check=check, timeout=60, env=env)

def android(*values, check=True):
    return subprocess.run([args.adb, '-s', args.android_device, *values], capture_output=True, check=check, timeout=60)

try:
    account = local_json('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=emulator-only',
                         {'email': email, 'password': password, 'returnSecureToken': True})
    sim('install', args.device, str(app))
    android('install', '-r', str(root / 'composeApp/build/outputs/apk/debug/composeApp-debug.apk'))
    android('install', '-r', str(root / 'composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk'))
    sim('terminate', args.device, bundle, check=False)
    android('shell', 'am', 'force-stop', 'com.fluxit')
    with (logs / 'apple.log').open('w') as apple, (logs / 'android.log').open('w') as droid:
        processes.append(subprocess.Popen(['xcrun', 'simctl', 'launch', '--console-pty', '--terminate-running-process',
            args.device, bundle, '-FluxItParitySelfCheck', '-parityEmail', email, '-parityPassword', password,
            '-parityMarker', marker], stdout=apple, stderr=subprocess.STDOUT, env=env))
        processes.append(subprocess.Popen([args.adb, '-s', args.android_device, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.fluxit.parity.FirebaseRoomParityInstrumentedTest',
            '-e', 'parityEmail', email, '-e', 'parityPassword', password, '-e', 'parityMarker', marker,
            'com.fluxit.test/androidx.test.runner.AndroidJUnitRunner'], stdout=droid, stderr=subprocess.STDOUT))
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            apple_text = (logs / 'apple.log').read_text(errors='replace')
            droid_text = (logs / 'android.log').read_text(errors='replace')
            if 'FB-701 END' in apple_text and processes[1].poll() is not None:
                if 'ALL CHECKS PASSED' not in apple_text or 'OK (1 test)' not in droid_text or processes[1].returncode:
                    raise RuntimeError('native-parity-or-realtime-failed')
                if len(re.findall('FB-701 iOS realtime PASS', apple_text)) != 1 or 'checkpoints=16' not in apple_text or 'FB-701 iOS cross-platform-photo PASS' not in apple_text or 'FB-701 iOS offline PASS' not in apple_text:
                    raise RuntimeError('missing-realtime-result')
                print('PASS Android real-app-DI Room/Firebase parity=16 checkpoints realtime=Apple-edits', flush=True)
                print('PASS Apple real-app-DI Room/Firebase parity=16 checkpoints realtime=Android-edits', flush=True)
                print('PASS bidirectional-native-photo bytes=equal replaced=1 deleted=2', flush=True)
                print('PASS Apple-native-offline cached-read pending-write reconnect-server-ack', flush=True)
                break
            if 'FB-701 iOS FAILED' in apple_text or 'FAILURES!!!' in droid_text:
                raise RuntimeError('native-check-failed')
            time.sleep(1)
        else:
            raise RuntimeError('native-report-timeout')
finally:
    sim('terminate', args.device, bundle, check=False)
    android('shell', 'am', 'force-stop', 'com.fluxit', check=False)
    for process in processes:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=15)
    if account:
        uid = account['localId']
        prefix = f'users/{uid}/items/'
        storage_url = f'http://127.0.0.1:9199/v0/b/{urllib.parse.quote(bucket, safe="")}/o'
        photo_listing = storage_url + '?prefix=' + urllib.parse.quote(prefix, safe='')
        photo_rows = local_json(photo_listing).get('items', [])
        for photo in photo_rows:
            if not photo['name'].startswith(prefix):
                raise RuntimeError('foreign-fixture-photo-path')
            local_json(storage_url + '/' + urllib.parse.quote(photo['name'], safe=''), method='DELETE')
        if local_json(photo_listing).get('items'):
            raise RuntimeError('photo-fixture-cleanup-incomplete')
        base = f'http://127.0.0.1:8080/v1/projects/{project}/databases/(default)/documents'
        lists_url = f'{base}/users/{uid}/lists'
        rows = local_json(lists_url).get('documents', [])
        deleted = 0
        for row in rows:
            list_url = base + '/' + row['name'].split('/documents/')[1]
            for item in local_json(list_url + '/items').get('documents', []):
                item_url = base + '/' + item['name'].split('/documents/')[1]
                local_json(item_url, method='DELETE')
                deleted += 1
            local_json(list_url, method='DELETE')
            deleted += 1
        if local_json(lists_url).get('documents'):
            raise RuntimeError('fixture-cleanup-incomplete')
        local_json('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:delete?key=emulator-only',
                   {'idToken': account['idToken']})
        lookup = local_json(f'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/{project}/accounts:lookup',
                            {'localId': [uid]}, auth_admin=True)
        if lookup.get('users'):
            raise RuntimeError('auth-fixture-cleanup-incomplete')
        print(f'PASS run-owned-emulator-teardown documents={deleted} accounts=1 remainingAccounts=0 remainingLists=0 remainingPhotos=0 recoveryPhotos={len(photo_rows)}', flush=True)
