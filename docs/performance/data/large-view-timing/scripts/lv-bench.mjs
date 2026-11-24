// Large-view keypress -> next file timing harness (Playwright/Chromium + CDP latency + server timing log).
// env: SERVER_JSON, PW_DIR (dir holding node_modules/playwright-core), TIMING_LOG, OUT, ROUND (binary|rated),
//      RTT (ms), VARIANTS (A,B,C,D,...), N (blocks, one vote per variant each), WARM, DWELL (ms), HEADLESS
import fs from 'node:fs';
import { startStub } from './stub.mjs';
import { startH2Proxy } from './h2proxy.mjs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const E = process.env;
const require = createRequire(E.PW_DIR + '/package.json');
const { chromium } = require('playwright-core');
const server = JSON.parse(fs.readFileSync(E.SERVER_JSON, 'utf8'));
const ROUND = E.ROUND || 'binary', RTT = Number(E.RTT || 0), N = Number(E.N || 10), WARM = Number(E.WARM || 4), DWELL = Number(E.DWELL || 1200);
const VARIANTS = (E.VARIANTS || 'A,B,C,D').split(',');
// [strip cookie, preload cookie]: strip '' (as today), C (off), L (loading=lazy); preload 1
// third: also start the preload from script (new Image(), via lvj cookie and the init script): F = B + script, G = D + script
const DEF = { A: ['', 0, 0], B: ['', 1, 0], C: ['C', 0, 0], D: ['C', 1, 0], E: ['L', 0, 0], F: ['', 1, 1], G: ['C', 1, 1], S: ['', 2, 1] };
const roundId = ROUND === 'binary' ? server.roundBinaryId : server.roundRatingId;
const juror = server.jurors[Number(E.JUROR || 0)];
const startPage = ROUND === 'binary' ? server.pageId : server.ratedPageId;
let base = server.baseUrl;
if (E.H2 === '1') { const p = await startH2Proxy(dirname(fileURLToPath(import.meta.url)), Number(new URL(base).port), E.IMMUTABLE === '1'); base = 'https://localhost:' + p.port; }
const median = (a) => { const s = [...a].sort((x, y) => x - y); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };

const INIT = () => {
  document.addEventListener('DOMContentLoaded', () => {
    if (!/(^|; )lvj=1/.test(document.cookie)) return;
    const l = document.querySelector('link[rel=preload][as=image]');
    const m = document.querySelector('meta[name=lv-next]');
    if (!l && !m) return;
    const i = new Image();
    if (l) { i.sizes = l.getAttribute('imagesizes') || ''; i.srcset = l.getAttribute('imagesrcset') || ''; i.src = l.href; }
    else { i.sizes = m.dataset.sizes || ''; i.srcset = m.dataset.srcset || ''; i.src = m.content; }
  });
  const now = () => performance.timeOrigin + performance.now();
  addEventListener('keydown', () => { try { sessionStorage.setItem('lv_t0', String(now())); } catch (e) {} }, true);
  const lv = (window.__lv = { t0: null });
  try { lv.t0 = Number(sessionStorage.getItem('lv_t0')) || null; } catch (e) {}
  new PerformanceObserver((l) => { for (const e of l.getEntries()) lv.lcp = { t: performance.timeOrigin + e.startTime, url: e.url, size: e.size }; })
    .observe({ type: 'largest-contentful-paint', buffered: true });
  document.addEventListener('DOMContentLoaded', () => {
    lv.dcl = now();
    setTimeout(() => { lv.interactive = now(); }, 0);
    const img = document.getElementById('large_image');
    const painted = () => requestAnimationFrame(() => requestAnimationFrame(() => { lv.imgPainted = now(); }));
    if (!img) { painted(); return; }
    const ready = () => (img.decode ? img.decode().catch(() => {}) : Promise.resolve()).then(painted);
    if (img.complete) ready(); else { img.addEventListener('load', ready); img.addEventListener('error', ready); }
  });
};

const browser = await (async () => { globalThis.__stub = await startStub(dirname(fileURLToPath(import.meta.url)), Number(process.env.IMG_KB || 250)); return chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP upload.wikimedia.org 127.0.0.1:' + globalThis.__stub.port, '--ignore-certificate-errors'] }); })();
const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1366, height: 768 } });
await ctx.addInitScript(INIT);
const page = await ctx.newPage();
await page.goto('/auth');
await page.getByLabel('Your login').fill(juror.login);
await page.getByLabel('Your password').fill(juror.password);
await page.getByLabel('Your password').press('Enter');
await page.waitForURL((u) => !/\/auth(\?|$)/.test(u.pathname + u.search));
const cdp = await ctx.newCDPSession(page);
await cdp.send('Network.enable');
await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: RTT, downloadThroughput: Number(E.BPS || -1), uploadThroughput: Number(E.BPS || -1) });
const hostname = new URL(base).hostname;
const setVariant = async (strip, pre, js = 0) => ctx.addCookies([
  { name: 'lvj', value: String(js), domain: hostname, path: '/' },
  { name: 'lvs', value: strip === '' ? 'x' : strip, domain: hostname, path: '/' },
  { name: 'lvp', value: String(pre), domain: hostname, path: '/' }]);

await setVariant('', 0);
await page.goto(`/large/round/${roundId}/user/${juror.id}/pageid/${startPage}`);
await page.waitForFunction(() => window.__lv && window.__lv.imgPainted);

// Each measured vote is preceded by a neutral vote, whose page carries the preload of the measured vote's variant (so the
// preload has been fetched when the measured vote starts, after DWELL) and nothing else; the measured vote's own
// destination page carries no preload. WARM neutral votes first; then N blocks, the order reversed in every second block.
const sched = [];
for (let i = 0; i < WARM; i++) sched.push({ v: 'A', neutral: true, warm: true });
for (let b = 0; b < N; b++) { const order = b % 2 ? [...VARIANTS].reverse() : VARIANTS; for (const v of order) { sched.push({ v, neutral: true, warm: false }); sched.push({ v, neutral: false, warm: false }); } }

const rows = [];
let rated = 0;
for (let i = 0; i < sched.length; i++) {
  const cur = sched[i];
  await page.waitForTimeout(DWELL);
  if (cur.neutral) await setVariant('', DEF[cur.v][1], DEF[cur.v][2]); else await setVariant(DEF[cur.v][0], 0);
  const keys = await page.evaluate(() => { const el = document.getElementById('large-keys'); return el ? [...el.attributes].filter((a) => a.name.startsWith('data-vote-key-')).map((a) => [a.name.slice(14), a.value]) : []; });
  if (!keys.length) { console.error('no vote keys on', page.url()); break; }
  let key, votePath;
  if (ROUND === 'binary') { const k = keys.find((x) => x[0] === 'space') || keys.find((x) => x[0] === 'del'); key = k[0] === 'space' ? 'Space' : 'Delete'; votePath = k[1]; }
  else { const digits = keys.filter((x) => /^\d$/.test(x[0]) && x[0] !== '0'); const k = digits[rated++ % digits.length]; key = k[0]; votePath = k[1]; }
  const nav = page.waitForNavigation({ waitUntil: 'load' });
  await page.keyboard.press(key);
  await nav;
  await page.waitForFunction(() => window.__lv && window.__lv.imgPainted && window.__lv.interactive, null, { timeout: 30000 });
  const m = await page.evaluate(() => {
    const lv = window.__lv, n = performance.getEntriesByType('navigation')[0].toJSON();
    const main = document.getElementById('large_image');
    const res = performance.getEntriesByType('resource').map((r) => ({ n: r.name, t: r.initiatorType, s: r.startTime, rs: r.responseStart, re: r.responseEnd, ts: r.transferSize, eb: r.encodedBodySize }));
    const fcp = performance.getEntriesByName('first-contentful-paint')[0];
    return { lv, nav: n, origin: performance.timeOrigin, res, mainSrc: main && main.currentSrc, thumbs: document.querySelectorAll('.thumb_small img').length, fcp: fcp ? performance.timeOrigin + fcp.startTime : null };
  });
  const o = m.origin, n = m.nav, t0 = m.lv.t0;
  const pp = (u) => { const x = new URL(u); return x.pathname + x.search; };
  const mainPath = m.mainSrc ? pp(m.mainSrc) : null;
  const mainRes = m.res.find((r) => r.t === 'img' && pp(r.n) === mainPath);
  const thumbRes = m.res.filter((r) => r.t === 'img' && r !== mainRes);
  const blocking = m.res.filter((r) => r.t === 'script' || (r.t === 'link' && /\.css/.test(r.n)));
  const lastEnd = Math.max(0, ...blocking.map((r) => r.re));
  const e = (x) => o + x;
  if (cur.neutral) { continue; }
  rows.push({
    i, variant: cur.v, warm: cur.warm, round: ROUND, rtt: RTT, key, votePath, toUrl: page.url(),
    total_painted: m.lv.imgPainted - t0, total_interactive: m.lv.interactive - t0,
    key_to_navstart: o - t0 + n.startTime,
    redirect: n.redirectEnd - n.redirectStart,
    page_ttfb: n.responseStart - n.requestStart,
    page_dl: n.responseEnd - n.responseStart,
    html_end_from_t0: e(n.responseEnd) - t0,
    resources_end_from_t0: e(lastEnd) - t0,
    dcl_from_t0: m.lv.dcl - t0, dcl_minus_resources: m.lv.dcl - e(lastEnd),
    load_from_t0: e(n.loadEventEnd) - t0,
    fcp_from_t0: m.fcp ? m.fcp - t0 : null,
    main: mainRes ? { start: e(mainRes.s) - t0, ttfb: mainRes.rs - mainRes.s, end: e(mainRes.re) - t0, dur: mainRes.re - mainRes.s, transfer: mainRes.ts, cached: mainRes.ts === 0, bytes: mainRes.eb } : null,
    paint_after_image_end: mainRes ? m.lv.imgPainted - e(mainRes.re) : null,
    thumbs: { n: thumbRes.length, imgs_in_dom: m.thumbs, bytes: thumbRes.reduce((a, r) => a + r.eb, 0), end_from_t0: thumbRes.length ? e(Math.max(...thumbRes.map((r) => r.re))) - t0 : null },
    files: { n: blocking.length, cached: blocking.filter((r) => r.ts === 0).length },
    lcp_from_t0: m.lv.lcp ? m.lv.lcp.t - t0 : null,
    t0_epoch: t0,
  });
}
await browser.close();

// join the server timing log: the POST by its path, the GET by the next page's path
let log = [];
if (E.TIMING_LOG && fs.existsSync(E.TIMING_LOG)) log = fs.readFileSync(E.TIMING_LOG, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
const find = (method, path, after) => log.filter((r) => r.method === method && r.path === path && r.at >= after - 50 && r.at <= after + 30000).sort((a, b) => a.at - b.at)[0];
for (const r of rows) {
  const u = new URL(r.toUrl);
  const post = find('POST', r.votePath, r.t0_epoch), get = find('GET', u.pathname + u.search, r.t0_epoch);
  r.server_post = post ? { total: post.totalMs, work: post.workMs, query: post.queryMs } : null;
  r.server_get = get ? { total: get.totalMs, work: get.workMs, render: get.renderMs, query: get.queryMs } : null;
}
fs.writeFileSync(E.OUT, JSON.stringify({ meta: { immutable: E.IMMUTABLE === '1', h2: E.H2 === '1', bps: E.BPS || null, round: ROUND, rtt: RTT, n: N, warm: WARM, dwell: DWELL, variants: VARIANTS, roundId }, rows }, null, 1));
const measured = rows.filter((r) => !r.warm);
for (const v of VARIANTS) {
  const rr = measured.filter((r) => r.variant === v);
  if (rr.length) console.log(`${ROUND} rtt=${RTT} ${v} n=${rr.length} painted median=${median(rr.map((r) => r.total_painted)).toFixed(0)} min=${Math.min(...rr.map((r) => r.total_painted)).toFixed(0)} max=${Math.max(...rr.map((r) => r.total_painted)).toFixed(0)} interactive median=${median(rr.map((r) => r.total_interactive)).toFixed(0)}`);
}
console.log('stub (stand-in for upload.wikimedia.org) requests:', globalThis.__stub.seen.length, 'bytes each', globalThis.__stub.bytes); process.exit(0);
