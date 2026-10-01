"""PLAN-011 fresh, unexported, task-owned local native suite lease.
No data deletion/reset, import/export, cloud requests or inferred prefix ownership.
Random fixture residue is captured then disposed with ONLY this spawned process.
Exact per-path ownership is not claimed for this isolated ephemeral tier.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from lease import all_zero,validate_armed

p = argparse.ArgumentParser()
p.add_argument('--app',required=True)
p.add_argument('--adb',required=True)
p.add_argument('--device',required=True)
p.add_argument('--android-device',default='emulator-5554')
a = p.parse_args()
root=Path(__file__).resolve().parents[2]
logs=Path(tempfile.mkdtemp(prefix='fluxit-fb706-isolated-native-'))
print('LOCAL diagnostic directory: '+str(logs),flush=True)
ports=[8080,9099,9199,4400,4500,9150]
for port in ports:
    with socket.socket() as s:
        s.settimeout(1)
        assert s.connect_ex(('127.0.0.1',port)) != 0,'existing endpoint refused'
config=json.loads((root/'composeApp/google-services.json').read_text())['project_info']
project,bucket=config['project_id'],config['storage_bucket']
apple_config=plistlib.loads((root/'iosApp/GoogleService-Info.plist').read_bytes())
assert apple_config['PROJECT_ID']==project
source=json.loads((root/'firebase.json').read_text())
source['firestore']['rules']=str(root/source['firestore']['rules'])
source['firestore']['indexes']=str(root/source['firestore']['indexes'])
source['storage']['rules']=str(root/source['storage']['rules'])
source.pop('functions',None)
source['emulators']={k:v for k,v in source['emulators'].items() if k in ['auth','firestore','storage']}
source['emulators']['ui']={'enabled':False}
for service in ['auth','firestore','storage']:source['emulators'][service]['host']='127.0.0.1'
local_config=logs/'firebase.json';local_config.write_text(json.dumps(source)+'\n')
assert 'ENABLED: Boolean = true' in (root/'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin/com/fluxit/config/FirebaseEmulatorConfig.kt').read_text()
sha=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
app=Path(a.app);bundle=plistlib.loads((app/'Info.plist').read_bytes())['CFBundleIdentifier']
assert bundle==apple_config['BUNDLE_ID']=='com.fluxit.FluxIt'
apks=[root/'composeApp/build/outputs/apk/debug/composeApp-debug.apk',root/'composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk']
assert all(path.is_file() for path in apks)
# Record the audited exact fixture/source/build lease before the first native write.
fixture_sources=sorted(list((root/'composeApp/src/androidInstrumentedTest').rglob('*.kt'))+list((root/'composeApp/src/firebaseParityAndroid').rglob('*.kt'))+list((root/'composeApp/src/firebaseParityIos').rglob('*.kt'))+list((root/'composeApp/src/iosMain/kotlin/com/fluxit/firebase').rglob('*Check.kt')))
lease={'task':'FB-706','policy':'PLAN-011','createdUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'unexported':True,'initialAllZero':False,'endpoints':{'auth':'127.0.0.1:9099','firestore':'127.0.0.1:8080','storage':'127.0.0.1:9199'},'namespaces':[project,'demo-fluxit'],'buckets':[bucket,'demo-fluxit.appspot.com'],'localConfigSha256':sha(local_config),'artifacts':{str(path):sha(path) for path in apks+[app/'FluxIt']+list(app.glob('*.debug.dylib'))},'fixtureSources':{str(path.relative_to(root)):sha(path) for path in fixture_sources},'suites':['Android complete instrumentation excluding two opt-in fixtures','Apple 11 emulator-gated selfchecks','16-contract same-account bidirectional realtime/photos/offline'],'dataDisposition':'actual residual inventory then task-process disposal; no per-path cleanup claim'}
lease_path=logs/'lease.json'
def save():lease_path.write_text(json.dumps(lease,indent=2)+'\n');lease_path.chmod(0o600)
save()
env={**os.environ,'PATH':'/tmp/fluxit-node22/node_modules/node/bin:'+os.environ['PATH'],'JAVA_HOME':'/Users/franzueto/Library/Java/JavaVirtualMachines/azul-23.0.2/Contents/Home','XDG_CONFIG_HOME':str(logs/'cli-config'),'FIRESTORE_EMULATOR_HOST':'127.0.0.1:8080','FIREBASE_AUTH_EMULATOR_HOST':'127.0.0.1:9099','FIREBASE_STORAGE_EMULATOR_HOST':'127.0.0.1:9199'}
cmd=[str(root/'firebase/node_modules/.bin/firebase'),'--config',str(local_config),'--project',project,'emulators:start','--only','auth,firestore,storage']
assert not any(v.startswith('--import') or v.startswith('--export') for v in cmd)
proc=None

def request(url,body=None):
    parsed=urllib.parse.urlparse(url)
    assert parsed.scheme=='http' and parsed.hostname=='127.0.0.1' and parsed.port in [8080,9099,9199]
    req=urllib.request.Request(url,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer owner','Content-Type':'application/json'},method='GET' if body is None else 'POST')
    try:
        with urllib.request.urlopen(req,timeout=20) as r:
            raw=r.read();return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        if error.code==404:return {}
        raise RuntimeError('local-inventory-http-'+str(error.code)) from None

def inventory():
    result={}
    for namespace in lease['namespaces']:
        base=f'http://127.0.0.1:8080/v1/projects/{urllib.parse.quote(namespace,safe="")}/databases/(default)/documents'
        roots=request(base+'/users?showMissing=true&pageSize=1000');assert not roots.get('nextPageToken')
        groups={}
        for collection in ['lists','items','listCleanupJobs']:
            rows=request(base+':runQuery',{'structuredQuery':{'from':[{'collectionId':collection,'allDescendants':True}]}})
            groups[collection]=[row['document']['name'] for row in rows if 'document' in row]
        accounts=request(f'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/{urllib.parse.quote(namespace,safe="")}/accounts:batchGet?maxResults=1000');assert not accounts.get('nextPageToken')
        result[namespace]={'roots':[r['name'] for r in roots.get('documents',[])],**groups,'accounts':[{'uid':r['localId'],'email':r.get('email')} for r in accounts.get('users',[])]}
    result['photos']={}
    for name in lease['buckets']:
        rows=request('http://127.0.0.1:9199/v0/b/'+urllib.parse.quote(name,safe='')+'/o?maxResults=1000');assert not rows.get('nextPageToken')
        result['photos'][name]=[r['name'] for r in rows.get('items',[])]
    return result

def zero(result):
    return all_zero(result)

def run(command,name,timeout=900):
    with (logs/name).open('w') as stream:
        result=subprocess.run(command,cwd=root,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout)
    assert result.returncode==0,'suite-command-failed-'+name
    return (logs/name).read_text(errors='replace')

try:
    with (logs/'emulators.log').open('w') as output:
        proc=subprocess.Popen(cmd,cwd=root,env=env,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    lease['pid']=proc.pid;lease['processGroup']=os.getpgid(proc.pid);save()
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        assert proc.poll() is None,'task-emulator-start-failed'
        try:
            baseline=inventory()
            break
        except (OSError,RuntimeError):time.sleep(1)
    else:raise RuntimeError('task-emulator-readiness-timeout')
    assert zero(baseline),'nonempty-task-baseline-refused'
    (logs/'initial-inventory.json').write_text(json.dumps(baseline,indent=2)+'\n')
    lease['initialAllZero']=True;lease['initialInventorySha256']=sha(logs/'initial-inventory.json');save()
    validate_armed(lease)
    print('PASS prewrite owned-process lease: configured+demo roots/lists/items/cleanupjobs/accounts/photos=0; no import/export; app/source hashes armed',flush=True)
    run([a.adb,'-s',a.android_device,'install','-r',str(apks[0])],'android-install.log')
    run([a.adb,'-s',a.android_device,'install','-r',str(apks[1])],'android-test-install.log')
    android=run([a.adb,'-s',a.android_device,'shell','am','instrument','-w','-r','-e','notClass','com.fluxit.parity.FirebaseRegressionInstrumentedTest,com.fluxit.parity.SessionCleanupInstrumentedTest','com.fluxit.test/androidx.test.runner.AndroidJUnitRunner'],'android-full.log')
    assert 'OK (90 tests)' in android and 'FAILURES!!!' not in android and 'Process crashed' not in android,'incomplete-android-suite'
    print('PASS Android complete instrumentation=90 including actual default DI',flush=True)
    apple=run(['python3',str(root/'firebase/final-verification/ios_checks.py'),'--app',a.app,'--device',a.device,'--lease',str(lease_path)],'apple-checks.log')
    assert apple.count('PASS native-iOS-emulator')==11 and '\nFAIL' not in apple,'incomplete-apple-suite'
    print('PASS Apple emulator selfchecks=11',flush=True)
    native=run(['python3',str(root/'firebase/final-verification/native_contract.py'),'--app',a.app,'--device',a.device,'--adb',a.adb,'--android-device',a.android_device,'--lease',str(lease_path)],'native-contract.log')
    for line in ['PASS Android real-app-DI Firebase regression=16','PASS Apple real-app-DI Firebase regression=16','PASS bidirectional-native-photo','PASS Apple-native-offline']:assert line in native,'incomplete-contract-suite'
    print('PASS actual Koin native contract=16/platform realtime/photo=bidirectional offline=recovered',flush=True)
finally:
    subprocess.run([a.adb,'-s',a.android_device,'shell','am','force-stop','com.fluxit'],capture_output=True)
    subprocess.run(['xcrun','simctl','terminate',a.device,bundle],capture_output=True)
    if proc is not None and proc.poll() is None:
        try:
            residual=inventory();(logs/'residual-inventory.json').write_text(json.dumps(residual,indent=2)+'\n')
            lease['residualInventorySha256']=sha(logs/'residual-inventory.json');save()
        finally:
            # Even an inventory failure must not leave this owned child running.
            os.killpg(proc.pid,signal.SIGINT)
            try:proc.wait(timeout=45)
            except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGTERM);proc.wait(timeout=15)
        for port in ports:
            with socket.socket() as s:
                s.settimeout(1);assert s.connect_ex(('127.0.0.1',port)) != 0,'task-port-still-open'
        lease['disposed']=True;lease['portsClosed']=True;save()
        print('PASS actual residual inventory captured; task-only ephemeral process disposed and ports closed; no data deletion/reset or inferred ownership',flush=True)
