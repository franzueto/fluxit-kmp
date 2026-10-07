import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { root } from './config.js';
const emulatorKeys = ['FIRESTORE_EMULATOR_HOST', 'FIREBASE_AUTH_EMULATOR_HOST', 'FIREBASE_STORAGE_EMULATOR_HOST', 'STORAGE_EMULATOR_HOST'];
for (const [name, args, code, extra] of [
  ['live execution needs explicit confirmation flags', ['--development'], 'EXPLICIT_DEVELOPMENT_EXECUTION_REQUIRED'],
  ['cleanup requires explicit development environment', ['--cleanup'], 'EXPLICIT_DEVELOPMENT_EXECUTION_REQUIRED'],
  ['emulator mode refuses absent emulators', ['--emulator'], 'EMULATORS_REQUIRED'],
  ['live mode refuses emulator routing before auth', ['--development', '--execute'], 'LIVE_EMULATOR_ENV_REFUSED', { FIRESTORE_EMULATOR_HOST: '127.0.0.1:1' }],
]) {
  test(name, () => {
    const env = { ...process.env }; emulatorKeys.forEach((key) => delete env[key]); Object.assign(env, extra);
    assert.equal(existsSync(resolve(root, 'firebase.secsuite-run.json')), false, 'Finish outstanding fixtures before safety tests');
    const result = spawnSync(process.execPath, [resolve(root, 'firebase/security/run.js'), ...args], { env, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 1); assert.match(result.stdout, new RegExp(code));
    assert.equal(existsSync(resolve(root, 'firebase.secsuite-run.json')), false);
    assert.equal(result.stderr, '');
  });
}
