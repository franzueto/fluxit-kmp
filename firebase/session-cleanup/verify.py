"""Replay FB-709 source/evidence integrity without network or device mutations.
Local logs and SDK sources are not committed; --local additionally re-hashes retained
receipts. This does not rerun native tests, grant review approval or change status.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('evidence.json')

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def fingerprint(files):
    return hashlib.sha256(''.join(path+'\0'+digest+'\n' for path,digest in sorted(files.items())).encode()).hexdigest()
def verify(report, local=False):
    assert report['task'] == 'FB-709' and report['review'] == 'NOT_SELF_APPROVED'
    assert report['sourceFingerprint'] == fingerprint(report['sourceFiles'])
    for path, expected in report['sourceFiles'].items():
        actual = (ROOT/path).resolve()
        assert actual.is_relative_to(ROOT) and actual.is_file(), path
        assert sha(actual) == expected, 'source changed: '+path
    assert set(report['ownedPaths']) == set(report['sourceFiles']) | {'firebase/session-cleanup/evidence.json'}
    changed = subprocess.check_output(['git','diff','--name-only'],cwd=ROOT,text=True).splitlines()
    untracked = subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    assert set(changed + untracked) - {'FIREBASE_MIGRATION_STATUS.md'} == set(report['ownedPaths']), 'task inventory drift'
    for path, expected in report['protectedFileHashesAtSourceFreeze'].items():
        assert sha(ROOT/path) == expected, 'protected file changed after source freeze: '+path
    assert not any(p.startswith(('functions/', 'firebase/firestore.rules', 'firebase/storage.rules')) or p in ['firestore.rules','storage.rules','composeApp/google-services.json','iosApp/GoogleService-Info.plist','AGENTS.md','FIREBASE_MIGRATION_PLAN.md','FIREBASE_MIGRATION_WORKFLOW.md'] for p in changed)
    for tier in report['unitTests'].values():
        assert tier['tests'] > 0 and tier['failures'] == tier['errors'] == tier['skipped'] == 0
    assert report['native']['result'] == 'PASS'
    assert report['native']['androidJUnitPhases'] == 3 and report['native']['appleConsolePhases'] == 3
    assert report['native']['osProcessRestart'] and report['native']['liveUploadAndDownloadTerminalCancellation']
    assert report['native']['remaining'] == {'roots':0,'lists':0,'items':0,'accounts':0,'photos':0}
    assert report['packages']['result'] == 'PASS' and report['restoration']['result'] == 'PASS'
    if local:
        for receipt in report['localReceipts']:
            assert sha(Path(receipt['path'])) == receipt['sha256'], 'local receipt changed: '+receipt['label']
    return True

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--local', action='store_true')
    parser.add_argument('--selftest', action='store_true')
    args = parser.parse_args()
    report = json.loads(REPORT.read_text())
    verify(report,args.local)
    if args.selftest:
        from copy import deepcopy
        bad = deepcopy(report); first = next(iter(bad['sourceFiles']))
        bad['sourceFiles'][first] = '0'*64
        try: verify(bad)
        except AssertionError: pass
        else: raise AssertionError('source tampering was accepted')
        bad = deepcopy(report); bad['native']['result'] = 'FAIL'
        try: verify(bad)
        except AssertionError: pass
        else: raise AssertionError('failed native evidence was accepted')
        print('PASS evidence verifier negative source/native probes=2')
    print('PASS FB-709 fingerprint/inventory/recorded evidence'+(' plus retained local receipt hashes' if args.local else ' (native execution not repeated)'))
