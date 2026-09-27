// Exercise the real built SPA against isolated API fixtures. No deployment is contacted.
// Run: npm run build --prefix web && node scripts/shots/rollout-smoke.mjs
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
const dist = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../web/dist');
const server = http.createServer((req, res) => {
  const name = new URL(req.url, 'http://test').pathname;
  const file = path.join(dist, name.startsWith('/assets/') ? name : 'index.html');
  res.setHeader('content-type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  fs.createReadStream(file).pipe(res);
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('hmdm.admin.user', JSON.stringify({ id: 1, login: 'admin', superAdmin: true })));
  const release = { id: 1, packageName: 'com.mdmesh.agent', versionName: '1.0.1', versionCode: 1001, sha256: 'a'.repeat(64), signatureChecksum: 'certificate', url: 'https://test/files/agent-releases/one.apk' };
  const preview = [
    { deviceNumber: 'TEST-001', agentVersion: '1.0.0', agentVersionCode: 1000, status: 'waiting', lastSeen: Date.now(), identityVerified: true },
    { deviceNumber: 'TEST-002', agentVersion: '1.0.0', agentVersionCode: 1000, status: 'offline', lastSeen: 1, identityVerified: true },
  ];
  let active = null, created = null, retried = null;
  const counts = { total: 2, updated: 0, pending: 0, waiting: 0, offline: 1, busy: 0, verifying: 0, failed: 1, ineligible: 0 };
  await page.route('**/update/**', (route) => route.fulfill({ contentType: 'application/json', body: 'null' }));
  await page.route('**/rest/**', async (route) => {
    const req = route.request(), p = new URL(req.url()).pathname;
    let data = [];
    if (p.endsWith('/releases')) data = [release];
    else if (p.includes('/rollout/preview/')) data = preview;
    else if (p.endsWith('/rollout/active')) data = active;
    else if (p.endsWith('/rollout') && req.method() === 'POST') {
      created = req.postDataJSON();
      active = { id: 9, targetVersion: '1.0.1', apkVersionCode: 1001, stage: created.allDevices ? 'fleet' : 'canary',
        devices: preview.map((d, i) => ({ ...d, status: i === 0 ? 'failed' : 'offline', cohort: created.allDevices ? 'fleet' : 'canary', detail: i === 0 ? 'Download failed' : null })),
        progress: { canary: { ...counts, total: created.allDevices ? 0 : 2 }, fleet: created.allDevices ? counts : null } };
      data = active;
    } else if (p.endsWith('/retry')) { retried = req.postDataJSON(); data = active; }
    else if (p.endsWith('/cancel')) { active = null; data = null; }
    else if (p.endsWith('/public/auth/options')) data = { signup: false, recover: false };
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'OK', data }) });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/settings`);
  const panel = page.locator('section.panel').filter({ has: page.getByRole('heading', { name: 'Agent releases & rollout' }) });
  await panel.getByText('2 devices · 2 need an update', { exact: false }).waitFor();
  await panel.getByRole('combobox').nth(1).selectOption('all');
  page.once('dialog', (dialog) => dialog.accept());
  await panel.getByRole('button', { name: 'Deploy to all 2 devices' }).click();
  await panel.getByRole('button', { name: 'Retry', exact: true }).waitFor();
  assert.deepEqual(created, { releaseId: 1, allDevices: true, canaryDeviceNumbers: [] });
  assert.equal(await panel.getByRole('button', { name: 'Finish', exact: true }).isDisabled(), true);
  await panel.getByRole('button', { name: 'Retry', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('input[type=file]').disabled);
  assert.deepEqual(retried, { deviceNumber: 'TEST-001' });
  await panel.screenshot({ path: '/tmp/mdmesh-rollout-ui.png' });
  page.once('dialog', (dialog) => dialog.accept());
  await panel.getByRole('button', { name: 'Cancel rollout' }).click();
  await panel.getByRole('button', { name: 'Deploy to all 2 devices' }).waitFor();
  await panel.getByRole('combobox').nth(1).selectOption('canary');
  await panel.getByRole('checkbox', { name: 'Select TEST-001' }).check();
  await panel.getByRole('button', { name: 'Start canary (1)' }).click();
  await panel.getByRole('button', { name: 'Promote to fleet' }).waitFor();
  assert.deepEqual(created, { releaseId: 1, allDevices: false, canaryDeviceNumbers: ['TEST-001'] });
  assert.equal(await panel.getByRole('button', { name: 'Promote to fleet' }).isDisabled(), true);
  assert.deepEqual(errors, []);
  console.log('PASS: fleet/canary creation, retry, finish guard, cancellation, no browser errors');
} finally {
  if (browser) await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
