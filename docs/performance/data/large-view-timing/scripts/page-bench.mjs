// Page-load timing for galleries and count-heavy pages: Chromium + CDP latency + the server's timing log.
// env: SERVER_JSON, PW_DIR, TIMING_LOG, OUT, SCENARIOS (json file: [{name, role: juror|organizer, from, to}]), RTT, REPS
// A scenario measures the navigation to `to` after being on `from` (a page turn, a filter change); `from` null = cold link.
import fs from 'node:fs';
import { startStub } from './stub.mjs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const E = process.env;
const require = createRequire(E.PW_DIR + '/package.json');
const { chromium } = require('playwright-core');
const server = JSON.parse(fs.readFileSync(E.SERVER_JSON, 'utf8'));
const scenarios = JSON.parse(fs.readFileSync(E.SCENARIOS, 'utf8'));
const RTT = Number(E.RTT || 0), REPS = Number(E.REPS || 8), base = server.baseUrl;
const accounts = { juror: server.jurors[0], organizer: server.organizer };
const hostname = new URL(base).hostname;

const browser = await (async () => { globalThis.__stub = await startStub(dirname(fileURLToPath(import.meta.url)), Number(process.env.IMG_KB || 250)); return chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP upload.wikimedia.org 127.0.0.1:' + globalThis.__stub.port, '--ignore-certificate-errors'] }); })();
const out = []; 
const lineDiff = (a, b) => { // bytes of B's lines not present in A (multiset)
  const m = new Map(); for (const l of a.split('\n')) m.set(l, (m.get(l) || 0) + 1);
  let changed = 0; for (const l of b.split('\n')) { const c = m.get(l) || 0; if (c) m.set(l, c - 1); else changed += l.length + 1; }
  return changed;
};
for (const role of ['juror', 'organizer']) {
  const mine = scenarios.filter((s) => s.role === role);
  if (!mine.length) continue;
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1366, height: 768 } });
  await ctx.addInitScript(() => {
    new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lcp = e.startTime; }).observe({ type: 'largest-contentful-paint', buffered: true });
  });
  const page = await ctx.newPage();
  await page.goto('/auth');
  await page.getByLabel('Your login').fill(accounts[role].login);
  await page.getByLabel('Your password').fill(accounts[role].password);
  await page.getByLabel('Your password').press('Enter');
  await page.waitForURL((u) => !/\/auth(\?|$)/.test(u.pathname + u.search));
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: RTT, downloadThroughput: Number(E.BPS || -1), uploadThroughput: Number(E.BPS || -1) });
  for (const sc of mine) {
    const rows = [];
    // the bytes of the pages, for the "what changes" estimate
    const get = async (u) => (await ctx.request.get(u)).text();
    const htmlFrom = sc.from ? await get(sc.from) : '', htmlTo = await get(sc.to);
    for (let k = -1; k < REPS; k++) { // k = -1 warm-up
      if (sc.from) await page.goto(sc.from, { waitUntil: 'load' });
      await page.waitForTimeout(400);
      const t0 = Date.now();
      await page.goto(sc.to, { waitUntil: 'load' });
      const m = await page.evaluate(() => {
        const n = performance.getEntriesByType('navigation')[0];
        const res = performance.getEntriesByType('resource');
        const imgs = res.filter((r) => r.initiatorType === 'img');
        return {
          origin: performance.timeOrigin, ttfb: n.responseStart - n.requestStart, dl: n.responseEnd - n.responseStart, htmlEnd: n.responseEnd,
          dcl: n.domContentLoadedEventEnd, load: n.loadEventEnd, enc: n.encodedBodySize, dec: n.decodedBodySize, lcp: window.__lcp || null,
          fcp: (performance.getEntriesByName('first-contentful-paint')[0] || {}).startTime || null,
          nres: res.length, nimg: imgs.length, imgEnd: imgs.length ? Math.max(...imgs.map((r) => r.responseEnd)) : null,
          dom: document.getElementsByTagName('*').length };
      });
      if (k >= 0) rows.push({ ...m, t0 });
    }
    out.push({ name: sc.name, role, rtt: RTT, to: sc.to, from: sc.from, htmlBytes: htmlTo.length, htmlChangedBytes: sc.from ? lineDiff(htmlFrom, htmlTo) : null, rows });
    console.log(sc.name, 'rtt', RTT, 'html', htmlTo.length, 'changed', sc.from ? lineDiff(htmlFrom, htmlTo) : '-', 'load med', rows.map((r) => r.load).sort((a, b) => a - b)[rows.length >> 1].toFixed(0));
  }
  await ctx.close();
}
await browser.close();
let log = [];
if (E.TIMING_LOG && fs.existsSync(E.TIMING_LOG)) log = fs.readFileSync(E.TIMING_LOG, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
for (const sc of out) {
  for (const r of sc.rows) {
    const p = new URL(sc.to, base); const path = p.pathname + p.search;
    const g = log.filter((x) => x.method === 'GET' && x.path === path && x.at >= r.t0 - 100 && x.at <= r.t0 + 30000).sort((a, b) => a.at - b.at)[0];
    r.server = g ? { total: g.totalMs, work: g.workMs, render: g.renderMs, query: g.queryMs, renderQuery: g.renderQueryMs, queries: g.queries } : null;
  }
}
fs.writeFileSync(E.OUT, JSON.stringify({ meta: { rtt: RTT, reps: REPS }, scenarios: out }, null, 1));
console.log('stub (stand-in for upload.wikimedia.org) requests:', globalThis.__stub.seen.length, 'bytes each', globalThis.__stub.bytes); process.exit(0);
