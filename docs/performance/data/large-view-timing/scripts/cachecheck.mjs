// does a second large view take its style and script files from the browser cache? (with and without the immutable header)
import fs from 'node:fs'; import { createRequire } from 'node:module'; import { startStub } from './stub.mjs'; import { startH2Proxy } from './h2proxy.mjs'; import { dirname } from 'node:path'; import { fileURLToPath } from 'node:url';
const require = createRequire(process.env.PW_DIR + '/package.json'); const { chromium } = require('playwright-core');
const s = JSON.parse(fs.readFileSync(process.env.SERVER_JSON, 'utf8')); const j = s.jurors[0]; const here = dirname(fileURLToPath(import.meta.url));
const stub = await startStub(here, 250);
for (const immutable of [false, true]) {
  const proxy = await startH2Proxy(here, Number(new URL(s.baseUrl).port), immutable); const base = 'https://localhost:' + proxy.port;
  const b = await chromium.launch({ args: ['--host-resolver-rules=MAP upload.wikimedia.org 127.0.0.1:' + stub.port, '--ignore-certificate-errors'] });
  const c = await b.newContext({ baseURL: base }); const p = await c.newPage();
  await p.goto('/auth'); await p.getByLabel('Your login').fill(j.login); await p.getByLabel('Your password').fill(j.password); await p.getByLabel('Your password').press('Enter');
  await p.waitForURL((u) => !/\/auth/.test(u.pathname));
  const heads = {}; p.on('response', (r) => { const u = new URL(r.url()); if (/^\/(assets|webjars)\//.test(u.pathname)) heads[u.pathname.split('/').pop().slice(0, 28)] = [r.status(), r.headers()['cache-control'] || null]; });
  for (let i = 0; i < 3; i++) {
    await p.goto(`/large/round/${s.roundBinaryId}/user/${j.id}/pageid/${s.pageId}`, { waitUntil: 'load' });
    const r = await p.evaluate(() => performance.getEntriesByType('resource').filter((r) => r.initiatorType === 'script' || r.initiatorType === 'link' || r.initiatorType === 'css').map((r) => [r.transferSize, Math.round(r.duration)]));
    console.log('immutable', immutable, 'load', i + 1, 'files', r.length, 'from cache (transferSize 0):', r.filter((x) => x[0] === 0).length, 'max duration', Math.max(...r.map((x) => x[1])));
  }
  if (!immutable) console.log(JSON.stringify(heads).slice(0, 700));
  await b.close();
}
process.exit(0);
