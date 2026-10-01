"""Inspect ordinary FB-709 packages; no network or device writes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--debug-app', required=True)
p.add_argument('--release-app', required=True)
p.add_argument('--output', required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
retired = ['FluxItDatabase','RoomListRepository','RoomItemRepository','BundledSQLiteDriver','Landroidx/room/','Landroidx/sqlite/','androidx_room','androidx_sqlite']
diagnostics = ['IosDefaultGraphCheck','IosFirebaseRegressionCheck','RepositoryRegressionScenario','SessionTrace','IosSessionCleanupCheck','SessionCleanupScenario','SessionCleanupInstrumentedTest','FB-709 stage']
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def inspect(raw,label):
    text = raw.decode('latin1')
    found = [t for t in retired + diagnostics if t in text]
    assert not found, label+': '+str(found)
    return text
packages = {}
for variant in ['debug','release']:
    apk = root/f'composeApp/build/outputs/apk/{variant}/composeApp-{variant}{"-unsigned" if variant == "release" else ""}.apk'
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        dex = [n for n in names if re.fullmatch(r'classes\d*\.dex',n)]
        assert dex
        texts = [inspect(archive.read(n),str(apk)) for n in dex]
        native = [n for n in names if n.startswith('lib/')]
        assert not any('sqlite' in n.lower() for n in native)
        assert any('Landroid/database/sqlite/' in t for t in texts), 'Firebase system SQLite must remain'
    packages['android-'+variant] = {'sha256':digest(apk),'diagnosticsAbsent':True,'roomAbsent':True,'firebaseSystemSQLitePresent':True,'dexFiles':len(dex)}
for variant, path in [('debug',a.debug_app),('release',a.release_app)]:
    app = Path(path)
    binaries = [app/'FluxIt']+list(app.glob('*.debug.dylib'))
    assert binaries[0].exists()
    texts = [inspect(binary.read_bytes(),str(binary)) for binary in binaries]
    assert any('FIRFirestore' in text for text in texts)
    packages['ios-'+variant] = {'binaries':{b.name:digest(b) for b in binaries},'diagnosticsAbsent':True,'roomAbsent':True,'nativeFirestorePresent':True}
config = root/'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin/com/fluxit/config/FirebaseEmulatorConfig.kt'
assert 'ENABLED: Boolean = false' in config.read_text()
report = {'result':'PASS','generatedEmulatorEnabled':False,'packages':packages}
Path(a.output).write_text(json.dumps(report,indent=2)+'\n')
print('PASS ordinary Debug/Release Android+iOS packages: new/historical parity diagnostics and Room absent; native Firestore persistence preserved; emulator gate=false')
