// The contest health page and the import form's identity warning (support diagnostics plan, D-1a;
// DG10), on UiShotsServer's Nepal case: an old international WLE 2020 contest of its own, reused for
// WLM 2026 in Nepal (healthContestId). As root (no member's contest menu changes): the page opened
// from the Rounds page, its checks (H1 and H2 warn, H7 fails on the empty round 2, H8 warns on
// round 1 of every file), its facts in light and dark mode, and an axe-core scan of each in
// Chromium; the files page's warning for the contest's source, hidden for a matching typed source
// and back for the Nepal one (the script's check), the import never refused. An organizer is
// refused the page. HEALTH_SHOTS=<dir> (Chromium) saves the screenshots.
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';

const SHOTS = process.env.HEALTH_SHOTS;

/** An axe scan: no serious or critical finding (as rounds-filter.spec.js's); of the `include`
  * selector only, if given (the rest of a page pages.spec.js's tour scans, with its triage). */
async function axe(page, testInfo, name, include = null) {
  let builder = new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']);
  if (include) builder = builder.include(include);
  const results = await builder.analyze();
  const serious = results.violations.filter((v) => ['serious', 'critical'].includes(v.impact))
    .map((v) => ({ rule: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }));
  await testInfo.attach(`axe ${name}`, { body: JSON.stringify(serious, null, 1), contentType: 'application/json' });
  expect(serious, `${name}: serious or critical accessibility findings`).toEqual([]);
}

const check = (page, code) => page.locator(`#check-${code}`);

for (const mode of ['light', 'dark']) {
  test.describe(`the contest health page in ${mode} mode`, () => {
    test(`shows the Nepal case's checks and facts, opened from the Rounds page (${mode})`, { tag: '@A-29' },
      async ({ contextAs, fixture, baseURL, browserName }, testInfo) => {
        test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
        const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        const page = await context.newPage();
        await page.goto(`/admin/rounds?contestId=${fixture.healthContestId}`);
        await page.locator('#contest-health-link').click();
        await expect(page).toHaveURL((url) => url.pathname === `/admin/contests/${fixture.healthContestId}/health`);
        await expect(page.getByRole('heading', { level: 1 })).toHaveText('Contest health');

        await expect(check(page, 'H1').locator('.health-outcome')).toHaveText('Warning');
        await expect(check(page, 'H1')).toContainText('Import sources that name another contest type, year or country than this contest: 3');
        await expect(check(page, 'H2')).toContainText('12 of 32 files are in none of this contest\'s country categories');
        await expect(check(page, 'H4').locator('.health-outcome')).toHaveText('Warning');
        await expect(check(page, 'H7').locator('.health-outcome')).toHaveText('Problem');
        await expect(check(page, 'H7')).toContainText('Round 2 has no files.');
        await expect(check(page, 'H8')).toContainText('Round 1 takes every file of the contest');

        // the facts: the sources with their differences, the countries and none, the empty round's source
        await expect(page.locator('.health-contest-source')).toHaveText('Category:Images from Wiki Loves Monuments 2026 in Nepal');
        await expect(page.locator('#health-country-none')).toContainText('12');
        const empty = page.locator('tr.health-round[data-flagged="H7"]');
        await expect(empty).toContainText('Round 1: 32 files');
        await expect(empty).toContainText('Selected by at least: 1');
        await expect(empty.locator('.health-round-files')).toHaveText('0');

        // the warning badge's text stays legible: Bootstrap's text-bg pair in both modes (axe checks contrast)
        if (browserName === 'chromium') await axe(page, testInfo, `health ${mode}`);
        if (SHOTS && browserName === 'chromium') {
          fs.mkdirSync(SHOTS, { recursive: true });
          await page.screenshot({ path: path.join(SHOTS, `health-${mode}.png`), fullPage: true });
        }
      });
  });
}

test('the health page at a phone\'s width scrolls its tables, not the page', { tag: '@A-29' },
  async ({ contextAs, fixture }) => {
    test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
    const context = await contextAs('root', { viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    await page.goto(`/admin/contests/${fixture.healthContestId}/health`);
    const widths = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, window: document.documentElement.clientWidth }));
    expect(widths.page, 'no horizontal scroll of the page at a phone\'s width').toBeLessThanOrEqual(widths.window);
    if (SHOTS && test.info().project.name === 'chromium') {
      fs.mkdirSync(SHOTS, { recursive: true });
      await page.screenshot({ path: path.join(SHOTS, 'health-phone.png'), fullPage: true });
    }
  });

test('the import form warns for a source of another contest, as the admin types, and never refuses', { tag: '@A-29' },
  async ({ contextAs, fixture, baseURL, browserName }, testInfo) => {
    test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
    const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    await page.goto(`/contest/${fixture.healthContestId}/images`);
    const warning = page.locator('#source-identity-warning');
    await expect(warning).toBeVisible();
    await expect(warning).toContainText('This contest is Wiki Loves Earth 2020, but the source names Wiki Loves Monuments 2026 in Nepal.');
    // the warning only: the files page's own findings are pages.spec.js's ('contest images', triaged)
    if (browserName === 'chromium') await axe(page, testInfo, 'the identity warning', '#source-identity-warning');
    if (SHOTS && browserName === 'chromium') {
      fs.mkdirSync(SHOTS, { recursive: true });
      for (const mode of ['light', 'dark']) {
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        await page.reload();
        await expect(warning).toBeVisible();
        await page.locator('#import-images').screenshot({ path: path.join(SHOTS, `import-warning-${mode}.png`) });
      }
      await context.addCookies([{ name: 'theme', value: 'light', url: baseURL }]);
      await page.reload();
    }

    // the source field is a Tom Select: its own input, then leaving it, sets the source; the
    // script asks the server and hides the warning for a matching source, shows it for another.
    // Commons' suggestions come from this route (a page's route goes before the guard's): none
    await page.route('https://commons.wikimedia.org/w/api.php**', (route) => route.fulfill({
      status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify({ batchcomplete: true, query: { prefixsearch: [] } }),
    }));
    const typed = async (text) => {
      // the field's input (a 1-character one beside the chosen item, so focused, not clicked), then the keys
      await page.locator('#source-ts-control').focus();
      await expect(page.locator('#source-ts-control')).toBeFocused();
      // Backspace gives the chosen item's text back to edit (category-suggest.js): select it, type over it
      await page.keyboard.press('Backspace');
      await page.keyboard.press('ControlOrMeta+a');
      await page.keyboard.type(text);
      await page.keyboard.press('Tab');
      await expect(page.locator('#source')).toHaveValue(text);
    };
    await typed('Category:Images from Wiki Loves Earth 2020 in Armenia');
    await expect(warning).toBeHidden();
    await typed('Category:Images from Wiki Loves Monuments 2026 in Nepal');
    await expect(warning).toBeVisible();
    await expect(warning).toContainText('Wiki Loves Monuments 2026 in Nepal');
  });

test.describe('an organizer', () => {
  test.use({ role: 'organizer' });

  test('is refused the health page', { tag: '@A-29' }, async ({ page, fixture, diagnostics }) => {
    diagnostics.expectStatus(403);
    const response = await page.goto(`/admin/contests/${fixture.contestId}/health`);
    expect(response.status()).toBe(403);
  });
});
