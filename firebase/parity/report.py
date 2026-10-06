"""Fail closed when fresh native reports are incomplete or contain test failures."""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--android', required=True)
parser.add_argument('--apple', required=True)
parser.add_argument('--native', required=True)
args = parser.parse_args()
android = Path(args.android).read_text(errors='replace')
apple = Path(args.apple).read_text(errors='replace')
native = Path(args.native).read_text(errors='replace')
assert 'OK (88 tests)' in android and 'FAILURES!!!' not in android and 'Process crashed' not in android
expected = {
    'FluxItAuthSelfCheck': 22, 'FluxItAuthRestorePrepare': 2, 'FluxItAuthRestoreVerify': 3,
    'FluxItFirestoreListSelfCheck': 20, 'FluxItFirestoreItemSelfCheck': 31,
    'FluxItCrossClientSelfCheck': 17, 'FluxItPhotoStorageSelfCheck': 22,
    'FluxItPhotoStorageInterruptedReplaceCheck': 12,
    'FluxItPhotoStorageCrossDevicePublish': 5, 'FluxItPhotoStorageCrossDeviceSubscribe': 4,
    'FluxItDashboardListenerCrashSelfCheck': 0,
}
found = re.findall(r'^PASS native-iOS-emulator (\w+) assertions=(\d+) failures=0$', apple, re.MULTILINE)
assert len(found) == len(expected) and {name: int(count) for name, count in found} == expected
assert not re.search(r'^FAIL', apple, re.MULTILINE)
for result in [
    'PASS Android real-app-DI Room/Firebase parity=16 checkpoints realtime=Apple-edits',
    'PASS Apple real-app-DI Room/Firebase parity=16 checkpoints realtime=Android-edits',
    'PASS bidirectional-native-photo bytes=equal replaced=1 deleted=2',
    'PASS Apple-native-offline cached-read pending-write reconnect-server-ack',
    'PASS run-owned-emulator-teardown documents=8 accounts=1 remainingAccounts=0 remainingLists=0 remainingPhotos=0 recoveryPhotos=0',
]:
    assert result in native, 'missing final native result'
print('PASS final-native-report Android=88 Apple=138+listener-crash parity=16/platform realtime=bidirectional photos=bidirectional offline=recovered fixtures=0')
