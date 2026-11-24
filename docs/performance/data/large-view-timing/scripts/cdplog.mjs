// CDP log of the requests to the (stubbed) upload.wikimedia.org host around one vote under variant B (link preload only),
// F (link + script new Image) or S (script only, from a meta element): was the preload issued, from which initiator, and
// was the next page's main image served from a cache?  env: SERVER_JSON, PW_DIR, VARIANT (B|F|S), HEADED=1, BPS, RTT
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startStub } from './stub.mjs';
import { startH2Proxy } from './h2proxy.mjs';
const E = process.env;
const require = createRequire(E.PW_DIR + '/package.json');
const { chromium } = require('playwright-core');
const server = JSON.parse(fs.readFileSync(E.SERVER_JSON, 'utf8'));
const juror = server.jurors[0];
const here = dirname(fileURLToPath(import.meta.url));
const stub = await startStub(here, 250);
const proxy = await startH2Proxy(here, Number(new URL(server.baseUrl).port), true);
const base = 'https://localhost:' + proxy.port;
const variant = E.VARIANT || 'B';
const [pre, js] = { B: [1, 0], F: [1, 1], S: [2, 1] }[variant];
const browser = await chromium.launch({ headless: E.HEADED !== '1', args: ['--host-resolver-rules=MAP upload.wikimedia.org 127.0.0.1:' + stub.port, '--ignore-certificate-errors'] });
console.log('browser', browser.version(), E.HEADED === '1' ? 'headed' : 'headless', 'variant', variant);
const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1366, height: 768 }, ignoreHTTPSErrors: true });
await ctx.addInitScript(() => {
  document.addEventListener('DOMContentLoaded', () => {
    if (!/(^|; )lvj=1/.test(document.cookie)) return;
    const l = document.querySelector('link[rel=preload][as=image]');
    const m = document.querySelector('meta[name=lv-next]');
    if (!l && !m) return;
    const i = new Image();
    if (l) { i.sizes = l.getAttribute('imagesizes') || ''; i.srcset = l.getAttribute('imagesrcset') || ''; i.src = l.href; }
    else { i.sizes = m.dataset.sizes || ''; i.srcset = m.dataset.srcset || ''; i.src = m.content; }
  });
});
const page = await ctx.newPage();
await page.goto('/auth');
await page.getByLabel('Your login').fill(juror.login);
await page.getByLabel('Your password').fill(juror.password);
await page.getByLabel('Your password').press('Enter');
await page.waitForURL((u) => !/\/auth(\?|$)/.test(u.pathname + u.search));
const cdp = await ctx.newCDPSession(page);
await cdp.send('Network.enable');
let phase = 'page N';
const t0 = Date.now();
const tail = (u) => decodeURIComponent(u).split('/').pop().slice(-28);
const reqs = new Map();
cdp.on('Network.requestWillBeSent', (e) => {
  if (!e.request.url.includes('upload.wikimedia.org')) return;
  reqs.set(e.requestId, tail(e.request.url));
  console.log(phase, '+' + (Date.now() - t0) + 'ms requestWillBeSent', tail(e.request.url), 'type', e.type, 'initiator', e.initiator.type, 'loaderId', e.loaderId.slice(0, 6));
});
cdp.on('Network.requestServedFromCache', (e) => { if (reqs.has(e.requestId)) console.log(phase, '+' + (Date.now() - t0) + 'ms requestServedFromCache', reqs.get(e.requestId)); });
cdp.on('Network.responseReceived', (e) => {
  if (!reqs.has(e.requestId)) return;
  const r = e.response;
  console.log(phase, '+' + (Date.now() - t0) + 'ms responseReceived', reqs.get(e.requestId), 'status', r.status, 'fromDiskCache', r.fromDiskCache, 'fromPrefetchCache', r.fromPrefetchCache, 'encodedDataLength', r.encodedDataLength);
});
cdp.on('Network.loadingFinished', (e) => { if (reqs.has(e.requestId)) console.log(phase, '+' + (Date.now() - t0) + 'ms loadingFinished', reqs.get(e.requestId), 'encodedDataLength', e.encodedDataLength); });
await ctx.addCookies(['lvj', 'lvp', 'lvs'].map((name, i) => ({ name, value: String([js, pre, 'x'][i]), domain: 'localhost', path: '/' })));
await page.goto(`/large/round/${server.roundBinaryId}/user/${juror.id}/pageid/${server.pageId}`, { waitUntil: 'load' });
console.log('page N loaded; waiting 3 s for the prefetch');
await page.waitForTimeout(3000);
const preload = await page.evaluate(() => { const l = document.querySelector('link[rel=preload]'); const m = document.querySelector('meta[name=lv-next]'); return { link: !!l, meta: !!m }; });
console.log('page N has preload link / meta:', JSON.stringify(preload));
await ctx.addCookies([{ name: 'lvp', value: '0', domain: 'localhost', path: '/' }]);
phase = 'after vote';
const keys = await page.evaluate(() => { const el = document.getElementById('large-keys'); return [...el.attributes].filter((a) => a.name.startsWith('data-vote-key-')).map((a) => a.name.slice(14)); });
const nav = page.waitForNavigation({ waitUntil: 'load' });
await page.keyboard.press(keys.includes('space') ? 'Space' : 'Delete');
await nav;
await page.waitForTimeout(500);
console.log('next page main image resource entry:', JSON.stringify(await page.evaluate(() => performance.getEntriesByType('resource').filter((r) => r.name.includes('upload.wikimedia.org')).map((r) => [r.initiatorType, r.transferSize, Math.round(r.duration)]))));
console.log('stub requests in all:', stub.seen.length);
await browser.close();
process.exit(0);
