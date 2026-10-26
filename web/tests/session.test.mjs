import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

const source = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
  .replace('import.meta.env.VITE_API_BASE', 'undefined');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const ok = (data = {}) => new Response(JSON.stringify({ status: 'OK', data }), { status: 200 });
function client(fetch) {
  const exports = {};
  const events = [];
  vm.runInNewContext(compiled, { exports, fetch, navigator: {}, FormData, DOMException, Event,
    window: { dispatchEvent: event => events.push(event.type) } });
  return { ...exports, events };
}

test('concurrent expired requests share one rotation and each retry once', async () => {
  let renewed = false, rotations = 0, calls = 0;
  const { apiClient } = client(async url => {
    if (url.endsWith('/status')) return new Response('', { status: 401 });
    if (url.endsWith('/refresh')) { rotations++; await new Promise(r => setTimeout(r, 10)); renewed = true; return ok(); }
    calls++; return renewed ? ok('saved') : new Response('', { status: 403 });
  });
  assert.deepEqual(await Promise.all([apiClient.get('/private/test'), apiClient.get('/private/test')]), ['saved', 'saved']);
  assert.equal(rotations, 1); assert.equal(calls, 4);
});

test('permission denial with valid session never rotates credentials', async () => {
  let rotations = 0;
  const { apiClient } = client(async url => {
    if (url.endsWith('/status')) return ok();
    if (url.endsWith('/refresh')) rotations++;
    return new Response('', { status: 403 });
  });
  await assert.rejects(apiClient.get('/private/test'), e => e.httpStatus === 403);
  assert.equal(rotations, 0);
});

test('expired refresh clears the UI without retrying the protected mutation', async () => {
  let mutations = 0;
  const { apiClient, events } = client(async url => {
    if (url.endsWith('/private/test')) mutations++;
    return new Response('', { status: 401 });
  });
  await assert.rejects(apiClient.post('/private/test', {}), e => e.httpStatus === 401);
  assert.equal(mutations, 1); assert.deepEqual(events, ['mdmesh-session-expired']);
});

test('public login failures do not trigger refresh', async () => {
  const paths = [];
  const { apiClient } = client(async url => { paths.push(url); return new Response('', { status: 401 }); });
  await assert.rejects(apiClient.post('/public/auth/login', {}));
  assert.equal(paths.length, 1);
});
