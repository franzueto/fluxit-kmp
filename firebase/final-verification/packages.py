"""FB-706: inspect fresh dependency reports and ordinary Android/Apple packages.

Reject retired AndroidX Room/SQLite artifacts, preserving Firebase's own persistence
and the operating system SQLite APIs/libraries. No network or device writes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--dependencies', required=True)
parser.add_argument('--debug-app', required=True)
parser.add_argument('--release-app', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
retired = ['FluxItDatabase', 'RoomListRepository', 'RoomItemRepository',
           'BundledSQLiteDriver', 'androidx_room', 'androidx_sqlite',
           'androidx.room', 'androidx.sqlite', 'decodeImageFile', 'PhotoContent$Loadable', 'PhotoContent.Loadable', 'PhotoContentLoadable']
diagnostics = ['IosAuthIntegrationCheck', 'IosDefaultGraphCheck',
               'IosFirebaseRegressionCheck', 'RepositoryRegressionScenario', 'SessionTrace', 'IosSessionCleanupCheck', 'SessionCleanupScenario', 'SessionCleanupInstrumentedTest', 'FB-709 stage']


def reject(text, terms, label):
    found = [term for term in terms if term in text]
    assert not found, f'{label}: retired or test-only artifact found: {found}'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


report = Path(args.dependencies).read_text()
assert 'BUILD SUCCESSFUL' in report and 'FAILED' not in report
reject(report, ['androidx.room:', 'androidx.sqlite:', 'com.google.devtools.ksp:'], 'dependencies')
for configuration in ['debugRuntimeClasspath', 'releaseRuntimeClasspath',
                      'iosArm64CompileKlibraries', 'iosSimulatorArm64CompileKlibraries']:
    assert re.search(r'^' + configuration + r'\b', report, re.MULTILINE), configuration
assert 'com.google.firebase:firebase-firestore:' in report

sources = list((root / 'composeApp/src').rglob('*.kt'))
for path in sources:
    reject(path.read_text(), ['import androidx.room', 'import androidx.sqlite',
                             'Room.databaseBuilder', 'Room.inMemoryDatabaseBuilder', 'decodeImageFile', 'PhotoContent.Loadable', 'BitmapFactory.decodeFile', 'dataWithContentsOfFile'], str(path.relative_to(root)))
for path in [root / 'composeApp/build.gradle.kts', root / 'build.gradle.kts',
             root / 'gradle/libs.versions.toml']:
    reject(path.read_text(), ['libs.room', 'libs.sqlite', 'libs.plugins.ksp',
                             'androidx.room', 'androidx.sqlite', 'com.google.devtools.ksp',
                             'kspAndroid', 'kspIos', 'schemaDirectory'], path.name)
assert not list((root / 'composeApp/schemas').rglob('*.json'))
assert not list((root / 'composeApp/build/generated').rglob('*FluxItDatabase*'))
assert not (root / 'composeApp/build/generated/ksp').exists()

packages = {}
for variant in ['debug', 'release']:
    apk = root / f'composeApp/build/outputs/apk/{variant}/composeApp-{variant}{"-unsigned" if variant == "release" else ""}.apk'
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        native = [name for name in names if name.startswith('lib/')]
        assert not any('sqlite' in name.lower() for name in native), native
        dex = [name for name in names if re.fullmatch(r'classes\d*\.dex', name)]
        assert dex
        for name in dex:
            content = archive.read(name).decode('latin1')
            reject(content, retired + ['Landroidx/room/', 'Landroidx/sqlite/',
                                      'FirebaseRegressionInstrumentedTest'] + diagnostics, str(apk))
        # Firestore's required system SQLite API remains; this is not bundled AndroidX SQLite.
        system_sqlite = any(b'Landroid/database/sqlite/' in archive.read(name) for name in dex)
        assert system_sqlite
    packages['android-' + variant] = {'sha256': digest(apk), 'dexFiles': dex,
                                      'nativeEntries': native, 'firebaseSystemSQLiteReferences': system_sqlite}

for variant, app_path in [('debug', args.debug_app), ('release', args.release_app)]:
    app = Path(app_path)
    binaries = [app / 'FluxIt'] + list(app.glob('*.debug.dylib'))
    assert binaries[0].exists()
    found_firebase = False
    system_linkage = []
    for binary in binaries:
        symbols = subprocess.run(['nm', '-a', str(binary)], capture_output=True, text=True, check=True).stdout
        reject(symbols, retired + diagnostics, str(binary))
        found_firebase |= 'FIRFirestore' in symbols
        linked = subprocess.run(['otool', '-L', str(binary)], capture_output=True, text=True, check=True).stdout
        system_linkage.extend(line.strip() for line in linked.splitlines() if '/usr/lib/libsqlite3.dylib' in line)
    assert found_firebase, f'{variant}: missing native Firebase Firestore'
    assert not any('sqlite' in p.name.lower() for p in app.rglob('*') if p.is_file())
    packages['ios-' + variant] = {'binaries': {b.name: digest(b) for b in binaries},
                                 'nativeFirestorePresent': found_firebase,
                                 'systemSQLiteLinkage': system_linkage}

for variant in ['debug', 'release']:
    framework = root / f'composeApp/build/bin/iosSimulatorArm64/{variant}Framework/ComposeApp.framework'
    header = (framework / 'Headers/ComposeApp.h').read_text()
    declarations = '\n'.join(line for line in header.splitlines()
                             if line.startswith('@interface ') or line.startswith('@protocol '))
    reject(declarations, retired + diagnostics, 'ordinary ' + variant + ' framework header')
    symbols = subprocess.run(['nm', '-a', str(framework / 'ComposeApp')],
                             capture_output=True, text=True, check=True).stdout
    reject(symbols, retired + diagnostics, 'ordinary ' + variant + ' framework symbols')

config = (root / 'composeApp/build/generated/fluxit/firebaseEmulatorConfig/kotlin/com/fluxit/config/FirebaseEmulatorConfig.kt').read_text()
assert 'ENABLED: Boolean = false' in config
assert 'USE_FIREBASE_REPOSITORIES: Boolean = true' in config
Path(args.output).write_text(json.dumps({'dependenciesSha256': digest(Path(args.dependencies)),
                                       'packages': packages}, indent=2) + '\n')
print('PASS FB-706 sources/dependencies/Android-Debug-Release/iOS-Debug-Release Room=absent AndroidX-SQLite=absent bundled-SQLite=absent Firebase-persistence=retained emulator=false')
