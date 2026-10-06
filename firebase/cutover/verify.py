"""Fail closed on incomplete FB-702 reports or shipped diagnostics after restoring defaults."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--logs', default='/tmp')
parser.add_argument('--debug-app', required=True)
parser.add_argument('--release-app', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
logs = Path(args.logs)

def report(name):
    return (logs / ('fluxit-fb702-' + name + '.log')).read_text(errors='replace')

assert 'BUILD SUCCESSFUL' in report('default-gradle')
for name in ['default-debug-xcode', 'default-release-xcode', 'parity-xcode', 'probe-xcode']:
    assert '** BUILD SUCCEEDED **' in report(name), name
assert 'OK (1 test)' in report('default-android-di')
assert 'FAILURES!!!' not in report('default-android-di')
assert 'FB-702 iOS default-graph PASS Firebase-list-item emulator=false Room-initialized=false auth-initialized=false' in report('default-ios-di')
subprocess.run(['python3', str(root / 'firebase/parity/report.py'),
    '--android', str(logs / 'fluxit-fb702-android-full.log'),
    '--apple', str(logs / 'fluxit-fb702-ios-checks.log'),
    '--native', str(logs / 'fluxit-fb702-native.log')], check=True)
for task, count in [('testDebugUnitTest', 232), ('testReleaseUnitTest', 232), ('iosSimulatorArm64Test', 295)]:
    suites = [ET.parse(p).getroot() for p in (root / 'composeApp/build/test-results' / task).glob('TEST-*.xml')]
    assert sum(int(s.get('tests', 0)) for s in suites) == count, task
    assert all(int(s.get(k, 0)) == 0 for s in suites for k in ['failures', 'errors', 'skipped']), task
config = '\n'.join(p.read_text() for p in
    (root / 'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin').rglob('*.kt'))
assert 'ENABLED: Boolean = false' in config
assert 'USE_FIREBASE_REPOSITORIES: Boolean = true' in config
for app in [Path(args.debug_app), Path(args.release_app)]:
    binaries = [app / 'FluxIt'] + ([app / 'FluxIt.debug.dylib'] if (app / 'FluxIt.debug.dylib').exists() else [])
    strings = '\n'.join(subprocess.check_output(['strings', str(p)], text=True) for p in binaries)
    for term in ['IosAuthIntegrationCheck', 'IosDefaultGraphCheck', 'IosFirebaseRoomParityCheck', 'SessionTrace', 'FluxItSessionGate']:
        assert term not in strings, term
for configuration in ['debug', 'release']:
    header = (root / f'composeApp/build/bin/iosSimulatorArm64/{configuration}Framework/ComposeApp.framework/Headers/ComposeApp.h').read_text()
    # Historical KDoc references can mention a moved class without exporting its type.
    declarations = '\n'.join(re.findall(r'^@(?:interface|protocol)\s+(\w+)', header, re.MULTILINE))
    for term in ['IosAuthIntegrationCheck', 'IosDefaultGraphCheck', 'IosFirebaseRoomParityCheck', 'SessionTrace']:
        assert term not in declarations, term
for configuration, apk in [('debug', 'composeApp-debug.apk'), ('release', 'composeApp-release-unsigned.apk')]:
    with zipfile.ZipFile(root / 'composeApp/build/outputs/apk' / configuration / apk) as archive:
        dex = b''.join(archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
        for term in [b'SessionTrace', b'FluxItSessionGate', b'FirebaseRoomParityInstrumentedTest']:
            assert term not in dex, term
print('PASS FB-702 reports shared=232/232/295 native=88/138+crash parity=16/platform default-graphs=Firebase Room-uninitialized emulator=false ordinary-diagnostics=absent')
