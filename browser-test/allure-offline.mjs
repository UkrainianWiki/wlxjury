// The one-file Allure report must load nothing from another host (testing practices §10.10).
// It is built by the allure2 plugin with ALLURE_NO_ANALYTICS=true (allurerc.mjs; the Awesome
// plugin hard-codes Google Analytics, with no opt-out). This script:
//   1. puts a Content-Security-Policy <meta> first in the report's <head>: nothing but the file's
//      own inline scripts and styles and data:/blob: URLs, so a request to another host is
//      refused by the browser even if a later Allure adds one;
//   2. fails (exit 1) if the report has a Google Analytics loader (googletagmanager, gtag), or
//      markup or CSS that loads a script, stylesheet, image, frame, media or url() from another
//      host; or if the file is not a report (no <head>), so it can't pass by finding nothing.
//   node allure-offline.mjs [index.html]      (default ../target/browser-test/allure-report/)
//   node allure-offline.mjs --self-test       (a report with a gtag block must fail)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const CSP = [
  "default-src 'none'",
  "script-src 'unsafe-inline' data: blob:",
  "style-src 'unsafe-inline' data: blob:",
  'img-src data: blob:',
  'font-src data: blob:',
  'media-src data: blob:',
  'connect-src data: blob:',
  'frame-src data: blob:',
  'worker-src data: blob:',
  "form-action 'none'",
].join('; ');
const META = `<meta http-equiv="Content-Security-Policy" content="${CSP}">`;

const ANALYTICS = /googletagmanager|google-analytics\.com|gtag\/js|\bgtag\s*\(/i;
const LOADS = /<(script|link|img|iframe|frame|source|video|audio|embed|object)\b[^>]*\b(?:src|href|data)\s*=\s*["']?((?:https?:)?\/\/[^"'\s>]+)/gi;
const CSS_URL = /url\(\s*["']?(?:https?:)?\/\/[^)]+\)/gi;

/** The problems of a report's HTML, and the HTML with the CSP meta first in its <head>. */
export function check(html) {
  const problems = [];
  if (!/<head\b[^>]*>/i.test(html)) problems.push('no <head>: not a report');
  const analytics = html.match(ANALYTICS);
  if (analytics) problems.push(`a Google Analytics loader (${analytics[0]})`);
  for (const m of html.matchAll(LOADS)) problems.push(`<${m[1]}> from ${m[2]}`);
  for (const m of html.match(CSS_URL) ?? []) problems.push(`CSS ${m}`);
  const out = html.includes(META) ? html : html.replace(/<head\b[^>]*>/i, (h) => `${h}\n${META}`);
  return { problems: [...new Set(problems)], html: out };
}

function run(file) {
  const { problems, html } = check(fs.readFileSync(file, 'utf8'));
  if (problems.length) {
    console.error(`allure-offline: ${file} would load from another host or is not a report:\n  ${problems.join('\n  ')}`);
    return 1;
  }
  fs.writeFileSync(file, html);
  console.log(`allure-offline: ok, CSP meta first in <head>; ${fs.statSync(file).size} bytes`);
  return 0;
}

function selfTest() {
  const page = (body) => `<!DOCTYPE html><html><head><meta charset="utf-8"><title>r</title></head><body>${body}</body></html>`;
  const cases = [
    ['the gtag block Awesome writes', page('<script async src="https://www.googletagmanager.com/gtag/js?id=G-X"></script><script>window.dataLayer=[];function gtag(){}gtag(\'js\', new Date());</script>'), false],
    ['an inline gtag call', page('<script>gtag("config", "G-X")</script>'), false],
    ['a stylesheet on a CDN', page('<link rel="stylesheet" href="https://cdn.example/x.css">'), false],
    ['a protocol-relative script', page('<script src="//cdn.example/x.js"></script>'), false],
    ['a CSS url() to another host', page('<style>body{background:url("https://x.example/a.png")}</style>'), false],
    ['no <head>', '<p>empty</p>', false],
    ['a link the reader may follow', page('<a href="https://commons.wikimedia.org/">Commons</a><script>var a=1</script>'), true],
  ];
  const failures = [];
  for (const [name, html, ok] of cases) {
    const r = check(html);
    if ((r.problems.length === 0) !== ok) failures.push(`${name}: want ${ok ? 'ok' : 'a problem'}, got ${JSON.stringify(r.problems)}`);
    if (ok && !r.html.includes(`<title>r</title>`) || ok && r.html.indexOf(META) > r.html.indexOf('<meta charset')) {
      failures.push(`${name}: the CSP meta is not first in <head>`);
    }
  }
  // and through the file path, as CI runs it
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'allure-offline-'));
  const bad = path.join(dir, 'index.html');
  fs.writeFileSync(bad, cases[0][1]);
  if (run(bad) !== 1) failures.push('the file with a gtag block passed');
  fs.rmSync(dir, { recursive: true, force: true });
  for (const f of failures) console.error(`FAIL: ${f}`);
  console.log(`allure-offline self-test: ${failures.length ? `${failures.length} failure(s)` : 'ok'}`);
  return failures.length ? 1 : 0;
}

const arg = process.argv[2];
process.exitCode = arg === '--self-test' ? selfTest() : run(arg ?? '../target/browser-test/allure-report/index.html');
