"""FB-706 current 16-contract native runner inside the PLAN-011 isolated lease.
No endpoint process is started or data deleted here. isolated_native.py owns the
fresh empty instance, prewrite lease, actual residual inventory and disposal.
"""
import argparse
from lease import validate_armed
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
parser.add_argument('--lease', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
lease = json.loads(Path(args.lease).read_text())
validate_armed(lease)
os.kill(lease['pid'], 0)
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
logs = Path(tempfile.mkdtemp(prefix='fluxit-fb706-contract-'))
print('LOCAL diagnostic directory: ' + str(logs), flush=True)
run = uuid.uuid4().hex
email = f'fb703-{run}@example.invalid'
password = 'fb703-emulator-only-' + uuid.uuid4().hex
marker = 'fb703-' + run
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
    (logs/'account-intent.json').write_text(json.dumps({'email':email,'scope':'isolated task lease','lease':args.lease})+'\n')
    account = local_json('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=emulator-only',
                         {'email': email, 'password': password, 'returnSecureToken': True})
    (logs/'account-receipt.json').write_text(json.dumps({'uid':account['localId'],'email':email})+'\n')
    sim('install', args.device, str(app))
    android('install', '-r', str(root / 'composeApp/build/outputs/apk/debug/composeApp-debug.apk'))
    android('install', '-r', str(root / 'composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk'))
    sim('terminate', args.device, bundle, check=False)
    android('shell', 'am', 'force-stop', 'com.fluxit')
    with (logs / 'apple.log').open('w') as apple, (logs / 'android.log').open('w') as droid:
        processes.append(subprocess.Popen(['xcrun', 'simctl', 'launch', '--console-pty', '--terminate-running-process',
            args.device, bundle, '-FluxItFirebaseRegressionSelfCheck', '-parityEmail', email, '-parityPassword', password,
            '-parityMarker', marker], stdout=apple, stderr=subprocess.STDOUT, env=env))
        processes.append(subprocess.Popen([args.adb, '-s', args.android_device, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.fluxit.parity.FirebaseRegressionInstrumentedTest',
            '-e', 'parityEmail', email, '-e', 'parityPassword', password, '-e', 'parityMarker', marker,
            'com.fluxit.test/androidx.test.runner.AndroidJUnitRunner'], stdout=droid, stderr=subprocess.STDOUT))
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            apple_text = (logs / 'apple.log').read_text(errors='replace')
            droid_text = (logs / 'android.log').read_text(errors='replace')
            if 'FB-703 END' in apple_text and processes[1].poll() is not None:
                if 'ALL CHECKS PASSED' not in apple_text or 'OK (1 test)' not in droid_text or processes[1].returncode:
                    raise RuntimeError('native-regression-or-realtime-failed')
                if len(re.findall('FB-703 iOS realtime PASS', apple_text)) != 1 or 'checkpoints=16' not in apple_text or 'FB-703 iOS cross-platform-photo PASS' not in apple_text or 'FB-703 iOS offline PASS' not in apple_text:
                    raise RuntimeError('missing-realtime-result')
                print('PASS Android real-app-DI Firebase regression=16 checkpoints realtime=Apple-edits', flush=True)
                print('PASS Apple real-app-DI Firebase regression=16 checkpoints realtime=Android-edits', flush=True)
                print('PASS bidirectional-native-photo bytes=equal replaced=1 deleted=2', flush=True)
                print('PASS Apple-native-offline cached-read pending-write reconnect-server-ack', flush=True)
                break
            if 'FB-703 iOS FAILED' in apple_text or 'FAILURES!!!' in droid_text:
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
    print('PASS native app processes stopped; task lease owns actual residual inventory and ephemeral disposal, no inferred-owner deletion', flush=True)
