"""Read-only FB-706 source, ownership and sanitized evidence replay."""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def fingerprint(files):return hashlib.sha256(''.join(p+'\0'+d+'\n' for p,d in sorted(files.items())).encode()).hexdigest()
def verify(r,local=False):
    assert r['task']=='FB-706' and r['review']=='NOT_SELF_APPROVED'
    for section,key in [('sourceFiles','sourceFingerprint'),('verificationInputs','verificationFingerprint')]:
        assert fingerprint(r[section])==r[key]
        for p,d in r[section].items():
            file=(ROOT/p).resolve();assert file.is_relative_to(ROOT) and sha(file)==d,'source drift: '+p
    changed=subprocess.check_output(['git','diff','--name-only'],cwd=ROOT,text=True).splitlines()
    untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    assert set(changed+untracked)-{'FIREBASE_MIGRATION_STATUS.md'}==set(r['ownedPaths']),'task ownership drift'
    assert set(r['ownedPaths'])==set(r['sourceFiles'])|{'firebase/final-verification/evidence.json'}
    assert r['productChanges']==[] and r['productBaseline']=='9281019e8e22fc98ee2d4a0ebd75760dc6c438d6'
    for p,d in r['protectedFileHashes'].items():assert sha(ROOT/p)==d,'protected file drift: '+p
    assert all(v['tests']>0 and v['failures']==v['errors']==v['skipped']==0 for v in r['unitTests'].values())
    assert r['native']['result']=='PASS' and r['native']['androidTests']==90 and r['native']['appleLaunches']==11
    assert r['native']['contractCheckpointsPerPlatform']==16 and r['native']['initialActualZero'] and r['native']['ownedProcessDisposed'] and r['native']['portsClosed']
    assert r['native']['perPathCleanupClaim'] is False
    assert r['security']['emulatorAssertions']==r['security']['developmentCloudAssertions']==112
    assert r['security']['cloudRemaining']==0 and r['backend']=={'unitTests':8,'emulatorTests':13}
    assert r['packages']['result']==r['restoration']['result']=='PASS'
    assert r['manual']['humanChecksExecuted']==0 and r['manual']['remainingRows']==10 and r['manual']['owner']=='FB-707/MAN-008'
    if local:
        for item in r['localReceipts']:assert sha(Path(item['path']))==item['sha256'],'receipt drift: '+item['label']
    return True
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--local',action='store_true');p.add_argument('--selftest',action='store_true');a=p.parse_args()
    r=json.loads(Path(__file__).with_name('evidence.json').read_text());verify(r,a.local)
    if a.selftest:
        for mutate in [lambda x:x['native'].update(result='FAIL'),lambda x:x['native'].update(perPathCleanupClaim=True),lambda x:x['manual'].update(humanChecksExecuted=1)]:
            bad=deepcopy(r);mutate(bad)
            try:verify(bad)
            except AssertionError:pass
            else:raise AssertionError('negative evidence accepted')
        print('PASS negative native/cleanup/manual evidence probes=3')
    print('PASS FB-706 source/ownership/recorded evidence'+(' plus local receipt hashes' if a.local else '')+'; no test execution or approval inferred')
