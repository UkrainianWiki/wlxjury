// Every page of the tour opens with one h1 and, as every page in these tests, without CSP
// violations, console errors or failed responses (lib/fixtures.js). In Chromium each page also
// gets an accessibility scan (axe-core, from node_modules, injected by @axe-core/playwright: no
// CDN); its findings are written to target/browser-test/a11y/ as a report. A serious or critical
// finding fails the test unless its rule is in KNOWN, the triaged ones (UI practices §9, U3).
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath } from '../lib/actions.js';
import { OUT } from '../lib/server.js';

// rule id -> where it is triaged (UI practices §9); a finding of these is reported, not failed.
// The first scans (2026-11-04) found these six rules on 11 of the 17 pages.
const KNOWN = {
  'color-contrast': 'U4: outline-secondary buttons and theme labels below 4.5:1',
  'button-name': 'U5: icon-only buttons inside links (campaigns, users)',
  'link-name': 'U5: icon-only links (campaigns, users, the rated gallery\'s thumbnail)',
  label: 'U6: a field without a label (contest images: #source)',
  'select-name': 'U6: a select without a name (new round form: #jurors, behind Tom Select)',
  'aria-prohibited-attr': 'U5: aria-label on the navbar\'s disabled view links (round statistics)',
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
  ['campaigns', 'root', () => '/contests'],
];

const summarize = (violations) => violations.map((v) => ({
  rule: v.id,
  impact: v.impact,
  help: v.help,
  nodes: v.nodes.length,
  targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
}));

test.describe('the page tour', () => {
  for (const [name, role, pathOf] of TOUR) {
    test(name, async ({ contextAs, fixture, browserName }, testInfo) => {
      const context = await contextAs(role);
      const page = await context.newPage();
      const target = pathOf(fixture);
      const response = await page.goto(target);
      expect(response.status()).toBe(200);
      await expect(page.locator('h1'), 'one h1 (UI §4.2)').toHaveCount(1);
      await page.waitForLoadState('networkidle');

      if (browserName !== 'chromium') return;
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      const found = summarize(results.violations);
      fs.mkdirSync(A11Y_DIR, { recursive: true });
      const file = path.join(A11Y_DIR, `${name.replace(/[^a-z0-9]+/gi, '-')}.json`);
      fs.writeFileSync(file, JSON.stringify({ name, role, path: target, violations: found }, null, 1));
      await testInfo.attach('accessibility', { path: file, contentType: 'application/json' });
      const failing = found.filter((v) => ['serious', 'critical'].includes(v.impact) && !KNOWN[v.rule]);
      expect(failing, 'serious or critical accessibility findings not yet triaged').toEqual([]);
    });
  }
});
