// The round form's cut control (docs/user/round-files.md "Choosing the round's files"; regional winners
// plan §10.4, one control decided 2026-11-26, loaded in place decided 2026-11-26): choosing the fixture's
// rated round as the previous round lists every valid cut as "top N · score ≥ X", the candidates
// labelled, without leaving the page (cut-list.js posts the form to the cut list and fills the box); a
// filter changed refreshes the list; picking a cut shows "Show this cut in the ratings", which opens the
// rated round's rating view in a new tab at the cut's page, its divider between file N and N + 1
// scrolled into view. "Save and find matching files" then holds the round in review with the cut, and
// its Edit page (a re-apply) has the list again as it opens, the saved cut chosen.
//
// It writes to the shared fixture: one round per run, named after the engine and the time, left in
// review (hidden from every juror), as review.spec.js's, so no other test's rounds, menus or votes
// change. Chromium only: the control is server-rendered, the script is one engine's fetch, the divider
// file-anchor.js's scroll, which the other engines' runs of gallery-back.spec.js cover.
import { test, expect } from '../lib/fixtures.js';

const CUT_LIST = '/admin/rounds/cut-list';

/** The cut list's next answer (a POST to it). */
const cutListAnswer = (page) => page.waitForResponse((r) =>
  r.request().method() === 'POST' && new URL(r.url()).pathname === CUT_LIST, { timeout: 60_000 });

test.describe('the cut control', () => {
  test.use({ role: 'admin' });

  test('lists the cuts in place, refreshes them, shows one in the ratings, saves it, and lists them again on Edit',
    { tag: ['@A-16', '@A-01'] }, async ({ page, fixture, browserName, context }) => {
      test.skip(browserName !== 'chromium', 'one engine: a server-rendered control and one fetch');
      test.setTimeout(240_000);
      const name = `Cut list ${browserName} ${Date.now()}`;
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      await page.getByLabel('Round name (optional)').fill(name);
      await page.locator('a[href="#collapseImageFiltering"]').click();
      // no previous round: no list, no "Choose N", nothing asked for
      await expect(page.locator('#topImages-choose')).toHaveCount(0);
      await expect(page.locator('#topImages_help')).toHaveText('Choose the previous rounds first.');
      // a marker that a navigation would lose
      await page.evaluate(() => { window.cutListMarker = 1; });

      // the rated round as the previous round (its Tom Select keeps the native select in step)
      const first = cutListAnswer(page);
      await page.evaluate((prev) => {
        const select = document.querySelector('select#previousRound');
        for (const o of select.options) o.selected = (o.value === String(prev));
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }, fixture.roundRatingId);
      expect((await first).status()).toBe(200);

      const control = page.locator('select#topImages');
      await expect(control).toBeVisible();
      expect(await page.evaluate(() => window.cutListMarker)).toBe(1);
      const status = page.locator('#top-n-status');
      await expect(status).toHaveAttribute('aria-live', 'polite');
      await expect(status).toHaveText(/^Cuts available: [\d,]+$/);
      // "No top N" first and chosen; every other option a cut "top N · score ≥ X"
      await expect(control).toHaveValue('');
      await expect(control.locator('option').first()).toHaveText('No top N: all matching files');
      const texts = await control.locator('option[value]:not([value=""])').allTextContents();
      expect(texts.length).toBeGreaterThan(1);
      for (const text of texts) expect(text).toMatch(/^top [\d,]+ · score ≥ \d+(\.\d+)?/);
      await expect(control.locator('optgroup[label="Candidate cuts"] option').first()).toBeAttached();
      // the round keeps the files scoring at least the cut's score, by default
      await expect(page.getByRole('radio', { name: 'the files scoring at least the cut’s score' })).toBeChecked();

      // a filter changed (conflicts of interest kept in): the list asked for again, still in place
      const refreshed = cutListAnswer(page);
      await page.locator('#excludeCoi').uncheck();
      expect((await refreshed).status()).toBe(200);
      await expect(control).toBeVisible();
      await expect(status).toHaveText(/^Cuts available: [\d,]+$/);
      expect(await page.evaluate(() => window.cutListMarker)).toBe(1);
      const back = cutListAnswer(page);
      await page.locator('#excludeCoi').check();
      await back;
      await expect(status).toHaveText(/^Cuts available: [\d,]+$/);

      // no cut chosen: no link; a cut chosen: the link to the rated round's rating view
      const link = page.locator('#top-n-cut-link');
      await expect(link).toBeHidden();
      const candidate = control.locator('optgroup[label="Candidate cuts"] option').first();
      const n = Number(await candidate.getAttribute('value'));
      await control.selectOption(String(n));
      await expect(link).toBeVisible();
      await expect(link).toHaveAttribute('target', '_blank');
      await expect(link).toHaveAttribute('href',
        new RegExp(`^/byrate/round/${fixture.roundRatingId}/user/0/page/${Math.floor((n - 1) / 15) + 1}\\?sort=rate&cut=${n}#cut$`));

      const [ratings] = await Promise.all([context.waitForEvent('page'), link.click()]);
      await ratings.waitForLoadState('load');
      const divider = ratings.locator('li.wlx-cut-divider');
      await expect(divider).toHaveCount(1);
      await expect(divider).toHaveText(new RegExp(`^Cut: top ${n.toLocaleString('en-US')} · score ≥ \\d+(\\.\\d+)?$`));
      await expect(divider).toBeInViewport();
      // between file N and N + 1 of the page: after (N - 1) % 15 + 1 tiles
      const before = await divider.evaluate((d) => {
        let count = 0;
        for (let e = d.previousElementSibling; e; e = e.previousElementSibling) if (e.matches('li.gallerybox')) count += 1;
        return count;
      });
      expect(before).toBe(((n - 1) % 15) + 1);
      await ratings.close();

      // saved with the cut: held in review
      await page.getByRole('button', { name: 'Save and find matching files' }).click();
      await expect(page).toHaveURL((url) => /^\/admin\/rounds\/\d+\/review$/.test(url.pathname), { timeout: 60_000 });
      const roundId = /^\/admin\/rounds\/(\d+)\/review$/.exec(new URL(page.url()).pathname)[1];
      // its files found (a background job): reloaded until the review says how many match
      await expect(async () => {
        await page.goto(`/admin/rounds/${roundId}/review`);
        await expect(page.locator('#round-review-match')).toBeVisible({ timeout: 1_000 });
      }).toPass({ timeout: 90_000, intervals: [500, 1_000, 2_000] });

      // its Edit page (a re-apply): the list asked for as it opens, the saved cut chosen
      const onOpen = cutListAnswer(page);
      await page.goto(`/admin/rounds/edit?id=${roundId}&contestId=${fixture.contestId}`);
      expect((await onOpen).status()).toBe(200);
      await expect(page.getByRole('button', { name: 'Re-apply filters' })).toBeVisible();
      await expect(page.locator('select#topImages')).toHaveValue(String(n));
      await expect(page.locator('#top-n-status')).toHaveText(/^Cuts available: [\d,]+$/);
    });
});
