// Local OAuth stays in memory. Never print server bodies, URLs, or tokens.
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const cliApi = require('firebase-tools/lib/api.js');

export async function cliToken() {
  const configHome = process.env.XDG_CONFIG_HOME || join(homedir(), '.config');
  const saved = JSON.parse(readFileSync(join(configHome, 'configstore/firebase-tools.json'), 'utf8'));
  const tokens = saved.tokens;
  if (!tokens?.refresh_token) throw new Error('CLI_LOGIN_REQUIRED');
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', signal: AbortSignal.timeout(20000),
    body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: tokens.refresh_token,
      client_id: cliApi.clientId(), client_secret: cliApi.clientSecret() }),
  });
  if (!response.ok) throw new Error(`OAUTH_HTTP_${response.status}`);
  const result = await response.json();
  if (!result.access_token) throw new Error('OAUTH_TOKEN_MISSING');
  return result.access_token;
}

export async function adminRequest(token, url, { method = 'GET', body, missingOK = false } = {}) {
  const response = await fetch(url, { method, signal: AbortSignal.timeout(30000),
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  if (missingOK && response.status === 404) return null;
  if (!response.ok) throw new Error(`ADMIN_HTTP_${response.status}`);
  if (response.status === 204) return null;
  const value = await response.text();
  return value ? JSON.parse(value) : null;
}

export async function preflight(config) {
  const token = await cliToken();
  const needed = ['datastore.entities.get', 'datastore.entities.list', 'datastore.entities.create',
    'datastore.entities.update', 'datastore.entities.delete', 'firebaseauth.users.get', 'firebaseauth.users.create', 'firebaseauth.users.update', 'firebaseauth.users.delete'];
  const result = await adminRequest(token, `https://cloudresourcemanager.googleapis.com/v1/projects/${config.projectId}:testIamPermissions`,
    { method: 'POST', body: { permissions: needed } });
  if (!needed.every((p) => result.permissions?.includes(p))) throw new Error('ADMIN_PROJECT_PERMISSIONS_MISSING');
  return token;
}
