import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Follow the CLI ports rather than maintaining a third set of defaults.
export const EMULATOR_PORTS = JSON.parse(readFileSync(join(repoRoot, 'firebase.json'), 'utf8')).emulators;

export const PROJECT_ID = 'demo-fluxit';
export const ALICE = 'alice-uid';
export const BOB = 'bob-uid';

export function createTestEnvironment() {
  return initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(join(repoRoot, 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: EMULATOR_PORTS.firestore.port,
    },
    storage: {
      rules: readFileSync(join(repoRoot, 'storage.rules'), 'utf8'),
      host: '127.0.0.1',
      port: EMULATOR_PORTS.storage.port,
    },
  });
}
