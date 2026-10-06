"""Restore ordinary apps and verify installed bytes; no app launch or backend I/O."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import socket
import subprocess
import tempfile
p=argparse.ArgumentParser()
p.add_argument('--adb',required=True);p.add_argument('--device',required=True)
p.add_argument('--app',required=True);p.add_argument('--output',required=True)
a=p.parse_args();root=Path(__file__).resolve().parents[2]
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
def command(values):return subprocess.check_output(values,text=True,stderr=subprocess.STDOUT).strip()
adb=[a.adb,'-s','emulator-5554'];app=Path(a.app)
bundle=plistlib.loads((app/'Info.plist').read_bytes())['CFBundleIdentifier']
apk=root/'composeApp/build/outputs/apk/debug/composeApp-debug.apk'
command(adb+['shell','am','force-stop','com.fluxit'])
command(adb+['install','-r',str(apk)])
command(adb+['uninstall','com.fluxit.test'])
installed=command(adb+['shell','pm','path','com.fluxit'])
assert installed.startswith('package:') and installed.count('package:')==1
local=Path(tempfile.mkdtemp(prefix='fluxit-fb706-installed-'))/'base.apk'
command(adb+['pull',installed.removeprefix('package:'),str(local)])
assert sha(local)==sha(apk),'installed Android bytes mismatch'
assert 'com.fluxit.test' not in command(adb+['shell','pm','list','packages','com.fluxit.test'])
command(['xcrun','simctl','install',a.device,str(app)])
container=Path(command(['xcrun','simctl','get_app_container',a.device,bundle,'app']))
files=[app/'FluxIt']+list(app.glob('*.debug.dylib'))
for f in files:assert sha(f)==sha(container/f.name),'installed Apple bytes mismatch'
config=(root/'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin/com/fluxit/config/FirebaseEmulatorConfig.kt').read_text()
assert 'ENABLED: Boolean = false' in config and 'USE_FIREBASE_REPOSITORIES: Boolean = true' in config
for port in [8080,9099,9199,4400,4500,9150]:
    with socket.socket() as s:
        s.settimeout(1);assert s.connect_ex(('127.0.0.1',port))!=0,'task endpoint remains'
report={'result':'PASS','ordinaryAndroidInstalledSha256':sha(apk),'ordinaryAppleInstalledBinaries':{f.name:sha(f) for f in files},'testApkAbsent':True,'emulatorEnabled':False,'firebaseRepositories':True,'taskPortsClosed':True,'humanFreshInstallOrLaunchClaim':False}
Path(a.output).write_text(json.dumps(report,indent=2)+'\n')
print('PASS ordinary Android+iOS Debug installed hashes match; instrumentation APK absent; emulator=false; Firebase graph=true; task ports closed; no human gesture claim')
