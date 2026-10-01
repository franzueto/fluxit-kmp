// Run reviewed exact-manifest security checks; never deploy or reset data.
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
const root = resolve(import.meta.dirname,'../..');
for (const [flag, code] of [[null,null],['--verify-cleanup-failure','INJECTED_FIXTURE_FAILURE'],['--verify-partial-auth-failure','INJECTED_PARTIAL_AUTH_FAILURE'],['--verify-pending-storage-failure','INJECTED_PENDING_STORAGE_FAILURE']]) {
  const result = spawnSync(process.execPath,[resolve(root,'firebase/security/run.js'),'--emulator',...(flag?[flag]:[])],{cwd:root,encoding:'utf8',timeout:180000});
  // Preserve sanitized runner status in the caller's local log.
  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  const text=result.stdout??'';
  if (result.status !== (flag?1:0) || !text.includes('PASS exact-fixture-cleanup-verified') || !text.includes('remaining=0') || existsSync(resolve(root,'firebase.fb604-run.json')) || (flag?!text.includes(code):!text.includes('client-security-suite 112 assertions'))) throw Error('INCOMPLETE_SECURITY_OR_CLEANUP_RESULT');
  console.log(`PASS final security ${flag??'suite112'} expectedExit=${result.status} scopedCleanup=verified`);
}
