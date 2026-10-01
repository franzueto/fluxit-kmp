"""Run only the opt-in default-config graph probe; no auth or repository I/O."""
import argparse
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--app', required=True)
parser.add_argument('--device', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
config = '\n'.join(p.read_text() for p in
    (root / 'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin').rglob('*.kt'))
assert 'ENABLED: Boolean = false' in config
assert 'USE_FIREBASE_REPOSITORIES: Boolean = true' in config
app = Path(args.app)
bundle = plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleIdentifier']
output = Path(tempfile.mkdtemp(prefix='fluxit-fb703-default-ios-')) / 'probe.log'
env = {**os.environ, 'SIMCTL_CHILD_NSUnbufferedIO': 'YES'}
subprocess.run(['xcrun', 'simctl', 'install', args.device, str(app)], check=True, capture_output=True)
with output.open('w') as stream:
    process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--console-pty',
        '--terminate-running-process', args.device, bundle, '-FluxItDefaultGraphCheck'],
        stdout=stream, stderr=subprocess.STDOUT, env=env)
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            text = output.read_text(errors='replace')
            expected = 'FB-703 iOS default-graph PASS Firebase-list-item emulator=false Room-definition=absent auth-initialized=false'
            if expected in text:
                print(expected)
                break
            if process.poll() is not None:
                raise RuntimeError('default graph app exited without report')
            time.sleep(0.2)
        else:
            raise RuntimeError('default graph report timeout')
    finally:
        subprocess.run(['xcrun', 'simctl', 'terminate', args.device, bundle], capture_output=True)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=10)
