# FB-705 documentation verification procedure

Run from the repository root. These checks validate documentation/source contracts;
they do not start emulators, provision a project or exercise a mobile app. The
READMEs contain setup/run procedures; all runnable simulator placeholders must be
replaced locally. Historical deployment/native evidence remains in its task files.

## Source and state audit

Read `AGENTS.md`, workflow, canonical status, FB-705/dependency/P7/decision/manual/
follow-up rows, the P7 plan, latest FB-704 handoff and current Git diff. Compare
README claims with `composeApp/build.gradle.kts`, `gradle.properties`, catalog,
platform DI/auth/photo implementations, Swift bridges/bootstrap, debug manifest/
network XML, `Config.xcconfig`/Xcode project, `firebase.json`/`.firebaserc`, package
scripts and cleanup/cascade sources. Inspect native runner arguments/parsing/
emulator guards and scoped fixture helper before documenting their limits.

## Reproduce documentation checks

Save this read-only checker as `/tmp/fluxit-fb705-doc-check.py`, then run
`python3 /tmp/fluxit-fb705-doc-check.py` from the repository root:

```python
from pathlib import Path
import json, re, subprocess, unicodedata
root = Path.cwd()
docs = [root / 'README.md', root / 'firebase/README.md']
links = blocks = 0
for doc in docs:
    text = doc.read_text()
    for label, ref in re.findall(r'\[([^\]]+)\]\(([^\s)]+)\)', text):
        if ref.startswith(('https://', 'http://')):
            continue
        target, _, fragment = ref.partition('#')
        path = (doc.parent / target).resolve() if target else doc
        assert path.is_file(), f'missing link {doc.name}: {ref}'
        if fragment:
            headings = re.findall(r'^#{1,6} (.+)$', path.read_text(), re.M)
            def slug(value):
                return ''.join(c for c in value.lower().replace(' ', '-') if c in '-_' or c.isalnum())
            assert fragment in [slug(h) for h in headings], f'missing anchor {ref}'
        links += 1
    for code in re.findall(r'```(?:sh|bash)\n(.*?)```', text, re.S):
        result = subprocess.run(['bash', '-n'], input=code, text=True, capture_output=True)
        assert result.returncode == 0, result.stderr
        blocks += 1
    assert 'offline-only' not in text and 'regardless of how the pods are wired' not in text
    for command in re.findall(r'firebase/node_modules/\.bin/firebase[^\n]*(?:\\\n[^\n]*)*', text):
        assert '--project' in command, 'CLI command without explicit project'
package=json.loads((root/'firebase/package.json').read_text())
backend=json.loads((root/'functions/package.json').read_text())
assert '--project demo-fluxit' in package['scripts']['test']
assert '--only auth,firestore,storage' in package['scripts']['test']
assert '--only functions,pubsub,firestore,storage' in backend['scripts']['test:emulator']
assert backend['engines']['node']=='22'
assert json.loads((root/'.firebaserc').read_text())['projects']['default']=='demo-fluxit'
assert 'applicationId = "com.fluxit"' in (root/'composeApp/build.gradle.kts').read_text()
assert 'BUNDLE_ID=com.fluxit.FluxIt' in (root/'iosApp/Configuration/Config.xcconfig').read_text()
assert (root/'composeApp/src/debug/AndroidManifest.xml').is_file()
assert (root/'composeApp/src/debug/res/xml/network_security_config.xml').is_file()
for path in ['composeApp/google-services.json', 'iosApp/GoogleService-Info.plist']:
    result=subprocess.run(['git','check-ignore','--quiet',path])
    assert result.returncode==0
print(f'PASS docs relative-links/anchors={links} shell-blocks={blocks} targeting/scripts/app-IDs/overlays/ignore-policy')
```

The checker validates relative Markdown files/heading anchors, shell syntax with
`bash -n` (no command execution), explicit CLI targeting, npm emulator sets/runtime,
app IDs, overlay paths and mobile config ignore policy. It does not download or
parse ignored mobile configs and does not establish a successful fresh clone build.

## Exact commands used for command and contract checks

Each group is separate; run the root commands from the repository root:

```sh
python3 /tmp/fluxit-fb705-doc-check.py
python3 firebase/parity/ios-checks.py --help
python3 firebase/room-removal/native.py --help
python3 firebase/photo-removal/fixtures.py --help
node --version
npm --version
git diff --check
```

In `firebase/`:

```sh
npm run check
```

In `functions/`:

```sh
npm run check
```

No install command was executed in this task; existing lockfile dependencies were
present. Node 22 remains the documented runtime for Functions/cloud security; the
actual local checks used the Node version reported in RESULTS. `--help` exits before
runner installation/network/fixture code. Cloud commands and mobile builds in the
READMEs are source-checked instructions, not executed FB-705 evidence.

## Scope and secret checks

```sh
git diff --exit-code HEAD -- composeApp functions iosApp gradle.properties gradle/libs.versions.toml firestore.rules storage.rules firestore.indexes.json firebase.json .firebaserc .gitignore
git status --short
git diff --check
```

Review changed documentation for real project identifiers, private key blocks,
passwords, tokens, emails/user data, config file contents and raw fixture manifests.
`com.fluxit`/`com.fluxit.FluxIt`, `demo-fluxit`, property names and variable
placeholders are intentional public app/emulator names, not credentials.

## External documentation checked

Primary Firebase/Kotlin pages linked in the READMEs were checked on 2026-10-01
for Console config downloads, provider setup, offline defaults, Auth emulator,
CLI targeting, native interop and logical cache clearing. No Console action was
performed. Cache clearing includes pending writes and does not securely overwrite
bytes; the existing DEC-003a policy is retained. See [results](FB-705-RESULTS.md).
