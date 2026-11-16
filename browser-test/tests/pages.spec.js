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
  // the organisers' comments list: its "Comments CSV" button (btn-outline-primary), first scanned
  // on 2026-11-15 with its Comments menu open (its "Comments by" section has no finding of its own)
  'comments list, all jurors, Comments menu open': { 'color-contrast': U19 },
  // the same button, on the list filtered by an author (its funnels scanned, the filled one too)
  'comments list, filtered by an author': { 'color-contrast': U19 },
  // aria-label on the navbar's disabled view links; seen again on 2026-11-04's re-check
  'round statistics': { 'aria-prohibited-attr': U20 },
};

const A11Y_DIR = path.join(OUT, 'a11y');

/** (name, role, path[, menu[, follow]]) of the tour: role null is signed out; path from the
  * fixture; menu, a navbar menu opened before the scan (its expanded items scanned too); follow, a
  * link followed first if the page has one (a comment author's funnel: the list filtered by them,
  * its funnel filled; without a comment yet, voting.spec.js writes it, the page as it is). */
const TOUR = [
  ['login', null, () => '/auth'],
  ['gallery, binary', 'juror', (f) => galleryPath(f.roundBinaryId, f.accounts.juror.id)],
  ['gallery, rated', 'juror', (f) => galleryPath(f.roundRatingId, f.accounts.juror.id)],
  ['file list', 'juror', (f) => `/filelist/round/${f.roundBinaryId}/user/${f.accounts.juror.id}/page/1`],
  ['comments list, juror', 'juror', (f) => `/comments/round/${f.roundBinaryId}/user/${f.accounts.juror.id}/page/1`],
  // all the contest's comments (the one voting.spec.js writes, commentFile, and its row's chip once
  // it has), its Comments menu open with the "Comments by" section
  ['comments list, all jurors, Comments menu open', 'organizer', (f) => `/comments/round/${f.roundBinaryId}/user/0/page/1?scope=contest`, '#comments-menu'],
  ['comments list, filtered by an author', 'organizer', (f) => `/comments/round/${f.roundBinaryId}/user/0/page/1?scope=contest`, null, 'a.wlx-comment-filter-link'],
  ['gallery, Votes menu open', 'juror', (f) => galleryPath(f.roundBinaryId, f.accounts.juror.id), '#votes-menu'],
  ['gallery, Comments menu open', 'juror', (f) => galleryPath(f.roundBinaryId, f.accounts.juror.id), '#comments-menu'],
  ["one juror's gallery, Votes menu open", 'organizer', (f) => galleryPath(f.roundBinaryId, f.accounts.juror.id), '#votes-menu'],
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
  // a round's review page (P3): the organizer's read-only view, and a file's large view there
  ['round review', 'organizer', (f) => `/admin/rounds/${f.roundBinaryId}/review`],
  ['round review, a file', 'admin', (f) => `/admin/rounds/${f.roundBinaryId}/review/file/${f.accounts.juror.pageId}`],
  // a round's jurors form (2026-11-16): the fixture's round is distributed, so its state without the form
  ['round jurors form', 'admin', (f) => `/admin/rounds/${f.roundBinaryId}/jurors`],
  // adding a juror to a running round: the contest's jurors outside it (none may be left), its jurors
  ['add juror page', 'admin', (f) => `/admin/rounds/${f.roundBinaryId}/add-juror`],
  ['users', 'admin', (f) => `/admin/users?contestId=${f.contestId}`],
  // the duplicates report (the account join plan's J-1): the contest's, and root's of every account
  ['duplicates report', 'admin', (f) => `/admin/duplicates?contestId=${f.contestId}`],
  ['duplicates report, all', 'root', () => '/admin/duplicates/all'],
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
  for (const [name, role, pathOf, menu, follow] of TOUR) {
    test(name, async ({ contextAs, fixture, browserName }, testInfo) => {
      const context = await contextAs(role);
      const page = await context.newPage();
      const target = pathOf(fixture);
      const response = await page.goto(target);
      expect(response.status()).toBe(200);
      await expect(page.locator('h1'), 'one h1 (UI §4.2)').toHaveCount(1);
      if (follow && await page.locator(follow).count() > 0) {
        await Promise.all([page.waitForNavigation(), page.locator(follow).first().click()]);
        await expect(page.locator('a.wlx-comment-filter-link.active[aria-current="true"]').first()).toBeVisible();
      }
      if (menu) {
        await page.locator(`${menu} > button`).click();
        await expect(page.locator(`${menu} .dropdown-menu`)).toBeVisible();
      }

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

// A page left while its head's scripts still load: Firefox fires DOMContentLoaded on the stopped
// page, before its body. Bootstrap run from <head> then read document.body there ("document.body
// is null", the review spec's reloads in CI, 2026-11-16); deferred (layout.scala.html) it starts
// only once the body is parsed, so a stopped page has nothing to throw (lib/fixtures.js fails the
// test on a page error).
test.describe('a page left while it loads', { tag: '@S-04' }, () => {
  test.use({ role: 'juror' });

  test('throws no page error', async ({ page, fixture }) => {
    // a script after Bootstrap's in the gallery's <head>, held until the page is left
    let reached, release;
    const isReached = new Promise((r) => { reached = r; });
    const held = new Promise((r) => { release = r; });
    const SCRIPT = /\/javascripts\/([0-9a-f]+-)?region-menu\.js/; // versioned: a digest before the name
    await page.route(SCRIPT, async (route) => {
      reached();
      await held;
      await route.continue().catch(() => {});
    });
    const gallery = galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id);
    await page.goto(gallery, { waitUntil: 'commit' });
    await isReached;
    // the head's scripts before it run (jQuery, then Bootstrap where it isn't deferred)
    await page.waitForFunction(() => !!window.jQuery);
    await page.waitForTimeout(300);
    await page.unroute(SCRIPT, { behavior: 'ignoreErrors' });
    const leaving = page.goto(gallery);
    release();
    await leaving;
    await expect(page.locator('h1')).toHaveCount(1);
  });
});
