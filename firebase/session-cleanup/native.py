"""FB-709 isolated local native cache lifecycle evidence. No cloud requests or global reset.
Predeclares exact synthetic owners, document paths and photo paths before writing.
Diagnostic logs/manifests (including synthetic passwords) stay local.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from ownership import validate

p = argparse.ArgumentParser()
p.add_argument('--app', required=True)
p.add_argument('--device', required=True)
p.add_argument('--adb', required=True)
p.add_argument('--android-device', default='emulator-5554')
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
for key, value in [('FIREBASE_AUTH_EMULATOR_HOST','127.0.0.1:9099'),('FIRESTORE_EMULATOR_HOST','127.0.0.1:8080'),('FIREBASE_STORAGE_EMULATOR_HOST','127.0.0.1:9199')]:
    assert os.environ.get(key) == value, 'explicit local routing required'
assert 'ENABLED: Boolean = true' in (root/'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin/com/fluxit/config/FirebaseEmulatorConfig.kt').read_text()
config = json.loads((root/'composeApp/google-services.json').read_text())['project_info']
project, bucket = config['project_id'], config['storage_bucket']
base = f'http://127.0.0.1:8080/v1/projects/{urllib.parse.quote(project,safe="")}/databases/(default)/documents'
auth = f'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/{urllib.parse.quote(project,safe="")}'
storage = f'http://127.0.0.1:9199/v0/b/{urllib.parse.quote(bucket,safe="")}/o'
logs = Path(tempfile.mkdtemp(prefix='fluxit-fb709-native-'))
print('LOCAL diagnostic directory: '+str(logs), flush=True)

def request(url, body=None, method=None, missing=False):
    parsed = urllib.parse.urlparse(url)
    assert parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port in {9099,8080,9199}
    req = urllib.request.Request(url, None if body is None else json.dumps(body).encode(), {'Authorization':'Bearer owner','Content-Type':'application/json'}, method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r:
            raw = r.read(); return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        if missing and e.code == 404: return None
        raise RuntimeError(f'local-http-{e.code}') from None

def rows(url, key):
    r = request(url); assert not r.get('nextPageToken'), 'pagination requires bounded handling'; return r.get(key,[])

def group(name, namespace=base):
    result = request(namespace+':runQuery', {'structuredQuery': {'from':[{'collectionId':name,'allDescendants':True}]}})
    return [r['document'] for r in result if 'document' in r]

# Inventory actual roots AND descendants in both native adapter namespaces before writes.
for ns in [base, 'http://127.0.0.1:8080/v1/projects/demo-fluxit/databases/(default)/documents']:
    assert not rows(ns+'/users?showMissing=true&pageSize=1000','documents')
    assert not group('lists',ns) and not group('items',ns)
assert not rows(auth+'/accounts:batchGet?maxResults=1000','users')
assert not rows(storage+'?maxResults=1000','items')
print('PASS preflight roots/lists/items/accounts/photos=0',flush=True)
run = uuid.uuid4().hex[:18]
password = 'fb709-local-'+uuid.uuid4().hex
owners = []
for platform in ['android','apple']:
    for identity in ['A','B']:
        uid = 'fb709-'+run+'-'+platform+'-'+identity
        owners.append({'uid':uid,'email':uid.lower()+'@example.invalid','platform':platform,'identity':identity})
paths = [path for owner in owners for path in [f'users/{owner["uid"]}',f'users/{owner["uid"]}/lists/fb709-list',f'users/{owner["uid"]}/lists/fb709-list/items/fb709-item']]
photos = [f'users/{o["uid"]}/items/fb709-item/{name}' for o in owners for name in ['fb709-upload','cancelled-upload']]
manifest = {'namespaceSha256':hashlib.sha256((project+'\0'+bucket).encode()).hexdigest(),'owners':owners,'documents':paths,'photos':photos,'password':password,'initial':'all-zero'}
manifest_path = logs/'trusted-manifest.json'; manifest_path.write_text(json.dumps(manifest,indent=2)+'\n'); manifest_path.chmod(0o600)
created = []
processes = []
bundle = plistlib.loads((Path(a.app)/'Info.plist').read_bytes())['CFBundleIdentifier']
env = {**os.environ,'SIMCTL_CHILD_NSUnbufferedIO':'YES'}

def sim(*args, check=True): return subprocess.run(['xcrun','simctl',*args],capture_output=True,check=check,timeout=60,env=env)
def android(*args,check=True): return subprocess.run([a.adb,'-s',a.android_device,*args],capture_output=True,check=check,timeout=60)
def field(v):
    if v is None: return {'nullValue':None}
    if isinstance(v,bool): return {'booleanValue':v}
    if isinstance(v,int): return {'integerValue':str(v)}
    return {'stringValue':v}

try:
    for owner in owners:
        result = request(auth+'/accounts', {'localId':owner['uid'],'email':owner['email'],'password':password,'returnSecureToken':True})
        created.append({'localId':result['localId'],'email':owner['email']})
        assert result['localId'] == owner['uid'], 'Admin fixture creation must preserve predeclared UID'
        now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')
        request(base+'/users/'+owner['uid'], {'fields':{'schemaVersion':field(1)}}, method='PATCH')
        list_fields = {'name':owner['identity']+'-server','icon':'CART','color':'PRIMARY_BLUE','deletedAt':None,'totalItems':1,'completedItems':0,'schemaVersion':1}
        item_fields = {'listId':'fb709-list','title':'item-server','description':None,'isCompleted':False,'photoRef':None,'deletedAt':None,'schemaVersion':1}
        for path, values in [(f'users/{owner["uid"]}/lists/fb709-list',list_fields),(f'users/{owner["uid"]}/lists/fb709-list/items/fb709-item',item_fields)]:
            fields = {k:field(v) for k,v in values.items()}
            fields.update({k:{'timestampValue':now} for k in ['createdAt','updatedAt']})
            request(base+'/'+path, {'fields':fields}, method='PATCH')
    sim('install',a.device,a.app)
    android('install','-r',str(root/'composeApp/build/outputs/apk/debug/composeApp-debug.apk'))
    android('install','-r',str(root/'composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk'))
    sim('terminate',a.device,bundle,check=False)
    android('shell','am','force-stop','com.fluxit')
    def credentials(platform):
        oa,ob=[o for o in owners if o['platform']==platform]
        return {'emailA':oa['email'],'emailB':ob['email'],'password':password,'uidA':oa['uid'],'uidB':ob['uid']}
    for phase in ['run','prepare','recover']:
        # Each phase starts a NEW OS process; the same sandbox/SDK files survive.
        sim('terminate',a.device,bundle,check=False); android('shell','am','force-stop','com.fluxit',check=False)
        apple_args = [s for k,v in {**credentials('apple'),'phase':phase}.items() for s in ['-'+k,v]]
        droid_args = [s for k,v in {**credentials('android'),'phase':phase}.items() for s in ['-e',k,v]]
        with (logs/(phase+'-apple.log')).open('w') as ap, (logs/(phase+'-android.log')).open('w') as dr:
            processes.append(subprocess.Popen(['xcrun','simctl','launch','--console-pty','--terminate-running-process',a.device,bundle,'-FluxItSessionCleanupCheck',*apple_args],stdout=ap,stderr=subprocess.STDOUT,env=env))
            processes.append(subprocess.Popen([a.adb,'-s',a.android_device,'shell','am','instrument','-w','-r','-e','class','com.fluxit.parity.SessionCleanupInstrumentedTest',*droid_args,'com.fluxit.test/androidx.test.runner.AndroidJUnitRunner'],stdout=dr,stderr=subprocess.STDOUT))
            deadline = time.monotonic()+180
            while time.monotonic()<deadline:
                apple = (logs/(phase+'-apple.log')).read_text(errors='replace'); droid = (logs/(phase+'-android.log')).read_text(errors='replace')
                if 'FB-709 END' in apple and processes[-1].poll() is not None:
                    expected = {'run':'cache-A-B-sameuser PASS','prepare':'restart PREPARED','recover':'restart RECOVERED'}[phase]
                    assert 'FB-709 Apple '+expected in apple and 'FB-709 Android '+expected in droid and 'OK (1 test)' in droid
                    print('PASS Android+Apple native '+phase+' '+expected,flush=True)
                    break
                if 'FB-709 Apple FAILED' in apple or 'FAILURES!!!' in droid: raise RuntimeError('native-fixture-failed-'+phase)
                time.sleep(1)
            else: raise RuntimeError('native-fixture-timeout-'+phase)
        if phase == 'recover':
            # Independent emulator server reads reject even a briefly replayed queued write.
            for owner in owners:
                if owner['identity'] == 'A':
                    doc = request(base+'/users/'+owner['uid']+'/lists/fb709-list')
                    assert doc['fields']['name']['stringValue'] == 'restart-server'
            print('PASS independent-server restart queued-writes-never-replayed=2',flush=True)

finally:
    sim('terminate',a.device,bundle,check=False); android('shell','am','force-stop','com.fluxit',check=False)
    for proc in processes:
        if proc.poll() is None: proc.terminate()
        try: proc.wait(timeout=15)
        except subprocess.TimeoutExpired: proc.kill(); proc.wait(timeout=15)
    # Inspect all actual data before any deletion; reject foreign paths/accounts.
    accounts = rows(auth+'/accounts:batchGet?maxResults=1000','users')
    assert all(any(c['localId']==r['localId'] and c['email'].casefold()==r.get('email','').casefold() for c in created) for r in accounts)
    roots = rows(base+'/users?showMissing=true&pageSize=1000','documents')
    docs = roots+group('lists')+group('items')
    actual_paths = [d['name'].split('/documents/',1)[1] for d in docs]
    assert set(actual_paths)<=set(paths)
    objects = rows(storage+'?maxResults=1000','items')
    assert all(o['name'] in photos for o in objects)
    validate(manifest, hashlib.sha256((project+'\0'+bucket).encode()).hexdigest(), accounts, actual_paths, [o['name'] for o in objects])
    (logs/'cleanup-observations.json').write_text(json.dumps({'accounts':accounts,'documents':actual_paths,'photos':[o['name'] for o in objects]},indent=2)+'\n')
    for obj in objects: request(storage+'/'+urllib.parse.quote(obj['name'],safe=''),method='DELETE',missing=True)
    for path in reversed(paths): request(base+'/'+path,method='DELETE',missing=True)
    for account in accounts: request(auth+'/accounts:delete',{'localId':account['localId']})
    for ns in [base,'http://127.0.0.1:8080/v1/projects/demo-fluxit/databases/(default)/documents']:
        assert not rows(ns+'/users?showMissing=true&pageSize=1000','documents') and not group('lists',ns) and not group('items',ns)
    assert not rows(auth+'/accounts:batchGet?maxResults=1000','users') and not rows(storage+'?maxResults=1000','items')
    (logs/'cleanup.json').write_text(json.dumps({'documents':len(actual_paths),'accounts':len(accounts),'photos':len(objects),'remaining':{'roots':0,'lists':0,'items':0,'accounts':0,'photos':0},'manifestSha256':hashlib.sha256(manifest_path.read_bytes()).hexdigest()})+'\n')
    print(f'PASS scoped cleanup docs={len(actual_paths)} accounts={len(accounts)} photos={len(objects)} actual roots/lists/items/accounts/photos=0 both namespaces',flush=True)
