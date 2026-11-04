// Makes the one-file Allure report load nothing from another host (testing practices §10.10).
// Allure 3.21.0's Awesome report always adds Google Analytics (gtag.js from
// www.googletagmanager.com: `analyticsEnable: true` is hard-coded in plugin-awesome's
// generators.js, no option turns it off). This removes that markup, then fails if the report
// still names a script, stylesheet, image, frame or font on another host.
//   node allure-offline.mjs [../target/browser-test/allure-report/index.html]
import fs from 'node:fs';

const file = process.argv[2] ?? '../target/browser-test/allure-report/index.html';
let html = fs.readFileSync(file, 'utf8');

// the loader and its config script, as the template writes them
const GTAG = /<script async src="https:\/\/www\.googletagmanager\.com\/gtag\/js\?id=[\w-]+"><\/script>\s*<script>\s*window\.dataLayer[\s\S]*?<\/script>/g;
const removed = (html.match(GTAG) ?? []).length;
html = html.replace(GTAG, '');

// what the browser would still fetch from another host by itself: markup with an http(s) src or
// href (not a plain <a href>, a link the reader may follow), and CSS url()s. The app's scripts
// are inlined gzipped, so this can't see their requests: the offline check (§10.10) did.
const loads = [...new Set([...html.matchAll(/<(script|link|img|iframe|source|video|audio)\b[^>]*\b(?:src|href)\s*=\s*["']?((?:https?:)?\/\/[^"'\s>]+)/gi)].map((m) => `${m[1]} ${m[2]}`))];
const cssUrls = [...new Set((html.match(/url\(\s*["']?(?:https?:)?\/\/[^)]+\)/gi) ?? []))];

fs.writeFileSync(file, html);
console.log(`allure-offline: removed ${removed} analytics block(s); ${fs.statSync(file).size} bytes`);
if (loads.length || cssUrls.length) {
  console.error(`allure-offline: the report still loads from other hosts:\n  ${[...loads, ...cssUrls].join('\n  ')}`);
  process.exit(1);
}
if (removed === 0) {
  // a newer Allure without the block, or a changed template: the check above still holds
  console.log('allure-offline: no analytics block found');
}
