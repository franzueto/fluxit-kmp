import { liveConfig } from './config.js';
import { preflight } from './admin.js';
try { await preflight(liveConfig()); console.log('PASS development-admin-read-only-preflight'); }
catch (e) { console.log(`BLOCKED development-admin-read-only-preflight ${/^[A-Z_]+(?:_[0-9]+)?$/.test(e.message) ? e.message : 'LOCAL_OR_NETWORK_ACCESS'}`); process.exitCode = 1; }
