// Run after building web: node scripts/shots/device-library-smoke.mjs
// Exercises the real SPA with isolated API fixtures; never contacts a device.
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { buildFixtures } from './fixtures.mjs';

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
  page.setDefaultTimeout(10000);
  const fx = buildFixtures();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript((user) => localStorage.setItem('hmdm.admin.user', JSON.stringify(user)), fx.user);
  const apps = [
    { pkg: 'com.test.existing', label: 'Existing app' },
    { pkg: 'com.test.alpha', label: 'Alpha', versionName: '9.2', versionCode: 902 },
    { pkg: 'com.test.beta', label: 'Beta' },
    { pkg: 'com.test.gamma', label: 'Gamma' },
    { pkg: 'com.test.system', label: 'System app', system: true },
  ];
  const existing = { id: 1, name: 'Existing library name', pkg: apps[0].pkg, type: 'app', version: '7', url: 'https://test/existing.apk' };
  const library = [existing];
  const writes = [];
  const unexpectedWrites = [];
  let failBeta = true, failLibrary = false, libraryReads = 0;
  await page.route('**/rest/**', async (route) => {
    const req = route.request(), p = new URL(req.url()).pathname;
    let data = [];
    let status = 'OK';
    if (p.includes('/applications/search')) {
      libraryReads++;
      if (failLibrary) status = 'ERROR';
      const pkg = p.split('/search/')[1];
      data = pkg ? library.filter((app) => app.pkg === decodeURIComponent(pkg)) : library;
    } else if (p.endsWith('/applications/android') && req.method() === 'PUT') {
      const body = req.postDataJSON();
      writes.push(body);
      if (body.pkg === 'com.test.beta' && failBeta) status = 'ERROR';
      else { data = { ...body, id: library.length + 1 }; library.push(data); }
    } else if (/\/devices\/number\//.test(p)) data = fx.devices[0];
    else if (p.endsWith('/apps/latest')) data = { detail: JSON.stringify({ apps }), completedAt: Date.now() };
    else if (p.endsWith('/state')) data = fx.state;
    else if (p.endsWith('/capabilities')) data = fx.capabilities;
    else if (p.endsWith('/telemetry')) data = fx.telemetry;
    else if (p.endsWith('/devices/search')) data = { configurations: fx.configurations, devices: { items: fx.devices, totalItemsCount: fx.devices.length } };
    if (req.method() !== 'GET' && !p.endsWith('/applications/android')) unexpectedWrites.push(p);
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status, data, message: status === 'ERROR' ? 'Fixture failure' : null }) });
  });
  const url = `http://127.0.0.1:${server.address().port}/devices/${fx.devices[0].number}?tab=apps`;
  await page.goto(url);
  const panel = page.locator('.device-apps');
  await panel.getByRole('link', { name: 'In Library', exact: true }).waitFor();
  assert.equal(await panel.getByRole('checkbox', { name: 'Select Existing app', exact: true }).isDisabled(), true);
  const readsBeforeSearch = libraryReads;
  await panel.getByRole('textbox', { name: 'Search apps' }).fill('Alpha');
  const alphaActions = panel.getByRole('button', { name: 'Actions for Alpha', exact: true });
  assert.equal(await page.getByRole('menu').count(), 0);
  await alphaActions.focus();
  await page.keyboard.press('ArrowDown');
  await page.getByRole('menuitem', { name: 'Add to Library', exact: true }).waitFor();
  await page.keyboard.press('Escape');
  assert.equal(await page.getByRole('menu').count(), 0);
  assert.equal(await alphaActions.evaluate((el) => el === document.activeElement), true);
  await alphaActions.click();
  await alphaActions.click();
  assert.equal(await page.getByRole('menu').count(), 0);
  await alphaActions.click();
  await page.getByRole('menuitem', { name: 'Uninstall', exact: true }).click();
  await page.getByRole('dialog', { name: 'Confirm uninstall' }).waitFor();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await alphaActions.click();
  await panel.getByRole('heading', { name: 'Installed apps' }).click();
  assert.equal(await page.getByRole('menu').count(), 0);
  await alphaActions.click();
  const menuBox = await page.getByRole('menu').boundingBox();
  assert.ok(menuBox.y >= 0 && menuBox.y + menuBox.height <= 1000, 'menu stays in viewport');
  await page.getByRole('menuitem', { name: 'Add to Library', exact: true }).click();
  await panel.getByRole('link', { name: 'In Library', exact: true }).waitFor();
  assert.deepEqual(writes[0], { name: 'Alpha', pkg: 'com.test.alpha', type: 'app', version: '0', versionCode: 0 });
  assert.equal(libraryReads, readsBeforeSearch + 1, 'typing does not refetch the library');
  await panel.getByRole('textbox', { name: 'Search apps' }).fill('');
  await panel.getByRole('checkbox', { name: /Select all matching apps/ }).check();
  await panel.getByRole('button', { name: 'Add selected to Library (2)', exact: true }).click();
  await panel.getByRole('button', { name: 'Add selected to Library (1)', exact: true }).waitFor();
  assert.equal(await panel.getByRole('checkbox', { name: 'Select Beta', exact: true }).isChecked(), true);
  assert.equal(await panel.getByRole('checkbox', { name: 'Select Gamma', exact: true }).isDisabled(), true);
  failBeta = false;
  await panel.getByRole('button', { name: 'Add selected to Library (1)', exact: true }).click();
  await panel.getByRole('button', { name: 'Add selected to Library (0)', exact: true }).waitFor();
  await panel.getByRole('checkbox', { name: 'Show system apps' }).check();
  await panel.getByRole('checkbox', { name: 'Select System app', exact: true }).check();
  // Another admin registered this app after the initial load.
  library.push({ id: 10, name: 'System library name', pkg: 'com.test.system', type: 'app' });
  await panel.getByRole('button', { name: 'Add selected to Library (1)', exact: true }).click();
  await panel.getByRole('button', { name: 'Add selected to Library (0)', exact: true }).waitFor();
  assert.equal(writes.length, 4, 'existing packages never get overwritten');
  assert.deepEqual(library[0], existing);
  assert.deepEqual(unexpectedWrites, [], 'no device commands, APK downloads or uploads');
  failLibrary = true;
  await page.reload();
  await panel.getByRole('button', { name: 'Retry library' }).waitFor();
  await panel.getByRole('button', { name: 'Actions for Alpha', exact: true }).click();
  assert.equal(await page.getByRole('menuitem', { name: 'Add to Library', exact: true }).isDisabled(), true);
  await page.keyboard.press('Escape');
  failLibrary = false;
  await panel.getByRole('button', { name: 'Retry library' }).click();
  await panel.getByRole('link', { name: 'In Library', exact: true }).first().waitFor();
  assert.equal(await panel.getByRole('link', { name: 'In Library', exact: true }).count(), 4);
  await panel.getByRole('link', { name: 'Open Library', exact: true }).click();
  await page.getByText('Alpha', { exact: true }).waitFor();
  const alphaCard = page.locator('.app-card').filter({ has: page.getByText('Alpha', { exact: true }) });
  await alphaCard.getByText('Package only', { exact: true }).waitFor();
  await page.getByText('Existing library name', { exact: true }).waitFor();
  assert.deepEqual(errors, []);
  console.log('PASS: row menu toggle, keyboard/Escape, outside click, uninstall confirmation, single/bulk metadata registration, retry, duplicates, reload, no device commands or browser errors');
} finally {
  if (browser) await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
