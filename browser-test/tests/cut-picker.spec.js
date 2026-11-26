// The round form's cut control (docs/user/admin-guide.md "Choosing the round's files"; regional winners
// plan §10.4, one control decided 2026-11-26): "Choose N" over the fixture's rated round lists every
// valid cut as "top N · score ≥ X", the candidates labelled; picking one shows "Show this cut in the
// ratings", which opens the rated round's rating view in a new tab at the cut's page, its divider
// between file N and N + 1 scrolled into view. Nothing is saved (only the preview is posted). Chromium
// only: the control is server-rendered, the link is one script line (round-form.js), the divider
// file-anchor.js's scroll, which the other engines' runs of gallery-back.spec.js cover.
import { test, expect } from '../lib/fixtures.js';

test.describe('the cut control', () => {
  test.use({ role: 'admin' });

  test('picks a cut and shows it in the previous round\'s ratings', { tag: '@A-16' },
    async ({ page, fixture, browserName, context }) => {
      test.skip(browserName !== 'chromium', 'one engine: a server-rendered control and one link');
      // the save is never posted
      await page.route('**/admin/rounds/save**', (route) => route.abort());
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      // the rated round as the previous round (its Tom Select keeps the native select in step)
      await page.evaluate((prev) => {
        const select = document.querySelector('select#previousRound');
        for (const o of select.options) o.selected = (o.value === String(prev));
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }, fixture.roundRatingId);
      await page.locator('a[href="#collapseImageFiltering"]').click();
      await expect(page.locator('#topImages-choose')).toBeVisible();
      await Promise.all([
        page.waitForURL('**/admin/rounds/cut-preview**', { timeout: 60_000 }),
        page.locator('#topImages-choose').click(),
      ]);

      const control = page.locator('select#topImages');
      await expect(control).toBeVisible();
      // "No top N" first and chosen; every other option a cut "top N · score ≥ X"
      await expect(control).toHaveValue('');
      await expect(control.locator('option').first()).toHaveText('No top N: all matching files');
      const texts = await control.locator('option[value]:not([value=""])').allTextContents();
      expect(texts.length).toBeGreaterThan(1);
      for (const text of texts) expect(text).toMatch(/^top [\d,]+ · score ≥ \d+(\.\d+)?/);
      await expect(control.locator('optgroup[label="Candidate cuts"] option').first()).toBeAttached();
      // the round keeps the files scoring at least the cut's score, by default
      await expect(page.getByRole('radio', { name: 'the files scoring at least the cut’s score' })).toBeChecked();

      // no cut chosen: no link; a cut chosen: the link to the rated round's rating view
      const link = page.locator('#top-n-cut-link');
      await expect(link).toBeHidden();
      const first = control.locator('optgroup[label="Candidate cuts"] option').first();
      const n = Number(await first.getAttribute('value'));
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
    });
});
