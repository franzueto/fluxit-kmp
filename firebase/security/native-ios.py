"""Run existing double-gated iOS selfchecks; stdout contains status/counts only.
Detailed logs are local /tmp artifacts and must not be shared or committed.
Start only Auth/Firestore/Storage emulators before invoking this script.
"""
import argparse
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--app', required=True)
parser.add_argument('--device', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
if not all(os.environ.get(key) for key in ['FIRESTORE_EMULATOR_HOST', 'FIREBASE_AUTH_EMULATOR_HOST', 'FIREBASE_STORAGE_EMULATOR_HOST']):
    sys.exit('FAIL native-iOS-emulator: start-local-emulators-first')
config_files = list((root / 'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin').rglob('*.kt'))
if not any('public const val ENABLED: Boolean = true' in path.read_text() for path in config_files):
    sys.exit('FAIL native-iOS-emulator: build-config-emulator-gate-disabled')
app = Path(args.app)
bundle = plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleIdentifier']
logs = Path(tempfile.mkdtemp(prefix='fluxit-fb604-native-'))
env = {**os.environ, 'SIMCTL_CHILD_NSUnbufferedIO': 'YES'}

def command(*values, check=True):
    return subprocess.run(['xcrun', 'simctl', *values], capture_output=True, check=check, timeout=60, env=env)

try:
    command('install', args.device, str(app))
    for argument, tag in [('-FluxItPhotoStorageSelfCheck', 'FB-305'), ('-FluxItFirestoreItemSelfCheck', 'FB-205')]:
        command('terminate', args.device, bundle, check=False)
        output = logs / (tag + '.log')
        with output.open('w') as stream:
            process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--console-pty', '--terminate-running-process',
                args.device, bundle, argument], stdout=stream, stderr=subprocess.STDOUT, env=env)
            try:
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    text = output.read_text(errors='replace')
                    if tag + ' END' in text:
                        passes = len(re.findall(r'^PASS  ', text, re.MULTILINE))
                        failures = len(re.findall(r'^FAIL  ', text, re.MULTILINE))
                        good = 'ALL CHECKS PASSED' in text and 'CHECK(S) FAILED' not in text and 'THREW' not in text and failures == 0 and passes > 0
                        print(('PASS' if good else 'FAIL') + f' native-iOS-emulator {tag} assertions={passes} failures={failures}', flush=True)
                        if not good:
                            sys.exit(1)
                        break
                    if process.poll() is not None:
                        sys.exit('FAIL native-iOS-emulator: app-exited-without-report')
                    time.sleep(1)
                else:
                    sys.exit('FAIL native-iOS-emulator: report-timeout')
            finally:
                command('terminate', args.device, bundle, check=False)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=15)
except (subprocess.SubprocessError, OSError, KeyError, ValueError):
    sys.exit('FAIL native-iOS-emulator: local-runner-access-or-build-error')
