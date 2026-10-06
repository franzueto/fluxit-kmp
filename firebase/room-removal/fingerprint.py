"""Reproduce the FB-703 source fingerprint, including explicit deletion markers."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
baseline = '5e33b5b51e9a0cad8b7baa0ef80aea61bfe08bed'
paths = set(subprocess.check_output(
    ['git', 'ls-tree', '-r', '--name-only', baseline, '--', 'composeApp/src',
     'composeApp/schemas', 'iosApp/iosApp'], cwd=root, text=True).splitlines())
for directory in ['composeApp/src', 'iosApp/iosApp', 'firebase/room-removal']:
    paths.update(str(p.relative_to(root)) for p in (root / directory).rglob('*')
                 if p.is_file() and p.suffix in {'.kt', '.swift', '.py'})
paths.update(['build.gradle.kts', 'composeApp/build.gradle.kts', 'gradle/libs.versions.toml',
              'settings.gradle.kts', 'gradle.properties', 'firebase.json', '.firebaserc',
              'firestore.rules', 'storage.rules', 'firestore.indexes.json',
              'iosApp/iosApp.xcodeproj/project.pbxproj'])
sources = {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
           if (root / name).exists() else 'DELETED' for name in sorted(paths)}
combined = hashlib.sha256(''.join(name + '\0' + digest + '\n'
                                 for name, digest in sources.items()).encode()).hexdigest()
evidence_path = root / 'firebase/room-removal/evidence.json'
if args.check:
    evidence = json.loads(evidence_path.read_text())
    assert sources == evidence['sources'], 'source inventory/hash mismatch'
    assert combined == evidence['sourceSha256'], 'combined source hash mismatch'
    print(f'PASS FB-703 source fingerprint paths={len(sources)} sha256={combined}')
else:
    print(json.dumps({'sources': sources, 'sourceSha256': combined}, indent=2))
