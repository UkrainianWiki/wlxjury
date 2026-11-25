// "Download images locally" on the contest's files page and on a round's form (the maintainer's
// decisions of 2026-11-19): posted by script (async-form.js), so the page neither reloads nor moves,
// and the status above the button shows the download at once (cache-status.js); a refused post (a
// stale CSRF token here) says why under the button, the page still in place.
// In the round icons contest, which has no files and which no spec writes files to: the download
// ends at once with nothing to fetch, offline (the fixture's image stub, never Commons). As root.
import { test, expect } from '../lib/fixtures.js';

test.use({ role: 'root' });

/** A round of the round icons contest: its form's URL, from the Rounds page. */
async function roundFormPath(page, contestId) {
  await page.goto(`/admin/rounds?contestId=${contestId}`);
  return page.locator('a.round-edit-link').first().getAttribute('href');
}

/** Scrolls the page so that the cache section is in the middle; marks the page, clears the status. */
async function prepare(page, statusId) {
  const button = page.getByRole('button', { name: 'Download images locally' });
  await expect(button).toBeVisible();
  // the page's own first status poll done, so that only the click's refresh writes the status
  await page.waitForLoadState('networkidle');
  // At once: Bootstrap's `scroll-behavior: smooth` would animate it, and Firefox starts that
  // animation late, so the click's own scrolling cut it short with the button at the window's
  // bottom edge, where the status filled in above pushed it partly out (#201).
  await button.evaluate((b) => b.scrollIntoView({ block: 'center', behavior: 'instant' }));
  await expect(button).toBeInViewport({ ratio: 1 });
  await page.evaluate((id) => {
    window.wlxNotReloaded = true;
    document.getElementById(id).textContent = '';
  }, statusId);
  return { button, url: page.url() };
}

/** Not reloaded, not opened again at its top: the button and the status still in the window (the
  * round form's parts above them settle later, which moves them a little, not out of view). */
async function expectInPlace(page, before, statusId) {
  expect(await page.evaluate(() => window.wlxNotReloaded), 'the page not reloaded').toBe(true);
  expect(page.url()).toBe(before.url);
  await expect(before.button).toBeInViewport({ ratio: 1 });
  await expect(page.locator(`#${statusId}`)).toBeInViewport();
}

const PAGES = [
  ['the files page', 'cache-status', async (page, f) => `/contest/${f.roundsContestId}/images`],
  ['a round\'s form', 'round-cache-status', async (page, f) => roundFormPath(page, f.roundsContestId)],
];

for (const [name, statusId, path] of PAGES) {
  test(`${name}: the download starts without a reload, its status shown in place`, { tag: '@A-02' },
    async ({ page, fixture }) => {
      test.skip(!fixture.roundsContestId, "the fixture has no round icons contest (the guides' screenshot mode)");
      await page.goto(await path(page, fixture));
      const before = await prepare(page, statusId);
      const [response] = await Promise.all([
        page.waitForResponse((r) => r.request().method() === 'POST' && /\/images\/cache\/start$/.test(r.url())),
        before.button.click(),
      ]);
      expect(response.status()).toBe(204);
      expect(response.request().headers()['x-requested-with']).toBe('XMLHttpRequest');
      await expect(page.locator(`#${statusId}`)).not.toBeEmpty();
      await expectInPlace(page, before, statusId);
      await expect(page.locator('[data-async-error]')).toBeHidden();
      await expect(before.button).toBeEnabled();
    });
}

test('a refused start says why under the button, the page in place', { tag: '@A-02' },
  async ({ page, fixture, diagnostics }) => {
    test.skip(!fixture.roundsContestId, "the fixture has no round icons contest (the guides' screenshot mode)");
    // the CSRF filter's refusal of the stale token, on purpose
    diagnostics.allow(/^HTTP 403: /);
    diagnostics.allow(/^console error: Failed to load resource: the server responded with a status of 403\b/);
    await page.goto(`/contest/${fixture.roundsContestId}/images`);
    const before = await prepare(page, 'cache-status');
    await page.locator('form[data-async-form]').evaluate((form) => {
      form.setAttribute('data-csrf-token', 'stale');
      form.querySelector('input[type="hidden"]').value = 'stale';
    });
    const [response] = await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'POST' && /\/images\/cache\/start$/.test(r.url())),
      before.button.click(),
    ]);
    expect(response.status()).toBe(403);
    const error = page.locator('[data-async-error]');
    await expect(error).toBeVisible();
    await expect(error).toContainText('This page has expired. Reload it and try again.');
    await expect(page.locator('#cache-status')).toBeEmpty();
    await expectInPlace(page, before, 'cache-status');
  });
