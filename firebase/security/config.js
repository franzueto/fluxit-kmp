import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export function liveConfig() {
  for (const name of ['FIRESTORE_EMULATOR_HOST', 'FIREBASE_AUTH_EMULATOR_HOST', 'FIREBASE_STORAGE_EMULATOR_HOST', 'STORAGE_EMULATOR_HOST']) {
    if (process.env[name]) throw new Error('LIVE_EMULATOR_ENV_REFUSED');
  }
  const saved = JSON.parse(readFileSync(resolve(root, 'composeApp/google-services.json'), 'utf8'));
  const projectId = saved.project_info?.project_id;
  const storageBucket = saved.project_info?.storage_bucket;
  const apiKey = saved.client?.[0]?.api_key?.[0]?.current_key;
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId ?? '') || projectId.startsWith('demo-') ||
      !/^[a-z0-9.-]+$/.test(storageBucket ?? '') || !apiKey) throw new Error('INVALID_LOCAL_CONFIG');
  return { projectId, storageBucket, apiKey };
}
