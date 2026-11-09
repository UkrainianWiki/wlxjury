// Every page of the tour opens with one h1 and, as every page in these tests, without CSP
// violations, console errors or failed requests (lib/fixtures.js). In Chromium each page also
// gets an accessibility scan (axe-core, from node_modules, injected by @axe-core/playwright: no
// CDN); its findings are written to target/browser-test/a11y/ as a report. A serious or critical
// finding fails the test unless KNOWN has its rule for that page: the triaged ones (UI practices
// §9, U19–U21), so the same rule failing on another page fails.
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath } from '../lib/actions.js';
import { OUT } from '../lib/server.js';

// page -> rule -> its triage entry (UI practices §9): reported, not failed. From the scans of
// 2026-11-04.
const U19 = 'U19: contrast below 4.5:1';
const U20 = 'U20: a control without an accessible name';
const U21 = 'U21: a field without a label';
const KNOWN = {
  login: { 'color-contrast': U19 },
  'gallery, rated': { 'link-name': U20 },
  'own profile': { 'color-contrast': U19 },
  'contest files': { 'color-contrast': U19 },
  'round form': { 'color-contrast': U19 },
  'new round form': { 'color-contrast': U19, 'select-name': U21 },
  users: { 'color-contrast': U19, 'button-name': U20, 'link-name': U20 },
  'contest images': { 'color-contrast': U19, label: U21 },
  // aria-label on the navbar's disabled view links; seen again on 2026-11-04's re-check
  'round statistics': { 'aria-prohibited-attr': U20 },
};

const A11Y_DIR = path.join(OUT, 'a11y');

/** (name, role, path) of the tour: role null is signed out; path from the fixture. */
const TOUR = [
  ['login', null, () => '/auth'],
  ['gallery, binary', 'juror', (f) => galleryPath(f.roundBinaryId, f.accounts.juror.id)],
  ['gallery, rated', 'juror', (f) => galleryPath(f.roundRatingId, f.accounts.juror.id)],
  ['file list', 'juror', (f) => `/filelist/round/${f.roundBinaryId}/user/${f.accounts.juror.id}/page/1`],
  ['large view, binary', 'juror', (f) => largePath(f.roundBinaryId, f.accounts.juror.id, f.accounts.juror.pageId)],
  ['large view, rated', 'juror', (f) => largePath(f.roundRatingId, f.accounts.juror.id, f.accounts.juror.ratedPageId)],
  ['own profile', 'juror', (f) => `/users/edit/${f.accounts.juror.id}`],
  ['round statistics', 'organizer', (f) => `/roundstat/${f.roundBinaryId}`],
  ['by rate, all jurors', 'organizer', (f) => `/byrate/round/${f.roundRatingId}/user/0/page/1`],
  ['contest files', 'admin', (f) => `/contest/${f.contestId}/files`],
  ['contest file', 'admin', (f) => `/contest/${f.contestId}/files/${f.accounts.juror.pageId}`],
  ['rounds', 'admin', (f) => `/admin/rounds?contestId=${f.contestId}`],
  ['round form', 'admin', (f) => `/admin/rounds/edit?id=${f.roundBinaryId}&contestId=${f.contestId}`],
  ['new round form', 'admin', (f) => `/admin/rounds/edit?contestId=${f.contestId}`],
  ['users', 'admin', (f) => `/admin/users?contestId=${f.contestId}`],
  ['contest images', 'admin', (f) => `/contest/${f.contestId}/images`],
  ['contests', 'root', () => '/contests'],
];

const summarize = (violations) => violations.map((v) => ({
  rule: v.id,
  impact: v.impact,
  help: v.help,
  nodes: v.nodes.length,
  targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
}));

test.describe('the page tour', { tag: '@S-04' }, () => {
  for (const [name, role, pathOf] of TOUR) {
    test(name, async ({ contextAs, fixture, browserName }, testInfo) => {
      const context = await contextAs(role);
      const page = await context.newPage();
      const target = pathOf(fixture);
      const response = await page.goto(target);
      expect(response.status()).toBe(200);
      await expect(page.locator('h1'), 'one h1 (UI §4.2)').toHaveCount(1);

      if (browserName !== 'chromium') return;
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      const found = summarize(results.violations);
      fs.mkdirSync(A11Y_DIR, { recursive: true });
      const file = path.join(A11Y_DIR, `${name.replace(/[^a-z0-9]+/gi, '-')}.json`);
      fs.writeFileSync(file, JSON.stringify({ name, role, path: target, violations: found }, null, 1));
      await testInfo.attach('accessibility', { path: file, contentType: 'application/json' });
      const known = KNOWN[name] ?? {};
      const failing = found.filter((v) => ['serious', 'critical'].includes(v.impact) && !known[v.rule]);
      expect(failing, 'serious or critical accessibility findings not triaged for this page').toEqual([]);
    });
  }
});
