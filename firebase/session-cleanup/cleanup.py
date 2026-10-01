"""Resume FB-709 cleanup from its exact local trusted manifest; no owner discovery by name."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
from ownership import validate
p=argparse.ArgumentParser();p.add_argument('--manifest',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[2]
c=json.loads((root/'composeApp/google-services.json').read_text())['project_info'];project,bucket=c['project_id'],c['storage_bucket']
m=json.loads(Path(a.manifest).read_text());assert m['initial']=='all-zero'
assert m['namespaceSha256']==hashlib.sha256((project+'\0'+bucket).encode()).hexdigest()
base=f'http://127.0.0.1:8080/v1/projects/{urllib.parse.quote(project,safe="")}/databases/(default)/documents'
auth=f'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/{urllib.parse.quote(project,safe="")}'
storage=f'http://127.0.0.1:9199/v0/b/{urllib.parse.quote(bucket,safe="")}/o'
def request(url,body=None,method=None):
 parsed=urllib.parse.urlparse(url);assert parsed.scheme=='http' and parsed.hostname=='127.0.0.1' and parsed.port in {9099,8080,9199}
 try:
  req=urllib.request.Request(url,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer owner','Content-Type':'application/json'},method=method)
  with urllib.request.urlopen(req,timeout=20) as r: raw=r.read();return json.loads(raw) if raw else {}
 except urllib.error.HTTPError as e:
  if method=='DELETE' and e.code==404:return {}
  raise

def rows(url,key):
 r=request(url);assert not r.get('nextPageToken');return r.get(key,[])
def group(name,ns=base):return [r['document'] for r in request(ns+':runQuery',{'structuredQuery':{'from':[{'collectionId':name,'allDescendants':True}]}}) if 'document' in r]
known={o['uid']:o['email'].casefold() for o in m['owners']}
accounts=rows(auth+'/accounts:batchGet?maxResults=1000','users')
assert all(u['localId'] in known and u.get('email','').casefold()==known[u['localId']] for u in accounts)
docs=rows(base+'/users?showMissing=true&pageSize=1000','documents')+group('lists')+group('items')
paths=[d['name'].split('/documents/',1)[1] for d in docs]
assert set(paths)<=set(m['documents'])
photos=rows(storage+'?maxResults=1000','items');assert all(o['name'] in m['photos'] for o in photos)
validate(m,hashlib.sha256((project+'\0'+bucket).encode()).hexdigest(),accounts,paths,[o['name'] for o in photos])
# Validate everything and save the actual snapshot BEFORE deletion.
observations=Path(a.manifest).with_name('cleanup-observations.json')
observations.write_text(json.dumps({'documents':paths,'accounts':[u['localId'] for u in accounts],'photos':[o['name'] for o in photos]},indent=2)+'\n')
for o in photos:request(storage+'/'+urllib.parse.quote(o['name'],safe=''),method='DELETE')
for path in reversed(m['documents']):request(base+'/'+path,method='DELETE')
for u in accounts:request(auth+'/accounts:delete',{'localId':u['localId']})
for ns in [base,'http://127.0.0.1:8080/v1/projects/demo-fluxit/databases/(default)/documents']:
 assert not rows(ns+'/users?showMissing=true&pageSize=1000','documents') and not group('lists',ns) and not group('items',ns)
assert not rows(auth+'/accounts:batchGet?maxResults=1000','users') and not rows(storage+'?maxResults=1000','items')
print(f'PASS resumed exact-manifest cleanup docs={len(paths)} accounts={len(accounts)} photos={len(photos)} actual roots/lists/items/accounts/photos=0 both namespaces')
