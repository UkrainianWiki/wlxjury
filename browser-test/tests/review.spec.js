// A round's review before distribution (P3, docs/user/admin-guide.md "Reviewing a round's images"),
// as the contest's admin: a round created with no jurors waits in review; its Distribute carries the
// round form's juror picker, the contest's jurors chosen, and refuses with none; Edit the round
// offers "Re-apply filters", whose narrower monument filter (ids separated by ", ") takes files out
// and the review page shows by how many; two jurors picked, one per file, Distribute opens the round.
//
// It writes to the shared fixture: one round per engine, named after it, distributed to two of the
// contest's jurors that no other test signs in as (server.json's jurors are the voting tests'), so
// no other test's rounds, menus or votes change. The fill and the distribution are background jobs:
// the review page is reloaded until its state line says they are done.
import { test, expect } from '../lib/fixtures.js';
import { readServer } from '../lib/server.js';

// two monuments of the fixture's contest (data/wlm-UA-images-2025.csv), then one of them
const BOTH = '73-250-0001, 21-104-0001';
const ONE = '73-250-0001';

/** Reloads the review page until its state line matches `text`; returns the line. */
async function reviewState(page, roundId, text) {
  const state = page.locator('#round-review-state');
  await expect(async () => {
    await page.goto(`/admin/rounds/${roundId}/review`);
    await expect(state).toContainText(text, { timeout: 1_000 });
  }).toPass({ timeout: 90_000, intervals: [500, 1_000, 2_000] });
  return (await state.textContent()).trim();
}

/** Clicks `button` (a POST answered by a redirect to the review page) and returns the text of the
  * review page the redirect loads: its flash message is checked there, as the page shows it while a
  * job runs, since the job's status script reloads the page (without the flash) as the job ends. */
async function postToReview(page, roundId, button) {
  const [response] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/admin/rounds/${roundId}/review`),
    button.click(),
  ]);
  expect(response.status()).toBe(200);
  return response.text();
}

const readyCount = (line) => Number(/Images ready: (\d+)/.exec(line)?.[1] ?? NaN);

/** The media filtering panel opened (a collapsible). */
async function openFiltering(page) {
  const toggle = page.getByRole('button', { name: 'Media filtering' });
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click();
  await expect(page.locator('#monumentIds')).toBeVisible();
}

test.describe('an admin', () => {
  test.use({ role: 'admin' });

  test('creates a round without jurors, re-applies its filters in review, then distributes to the jurors picked',
    { tag: ['@A-21', '@A-01'] }, async ({ page, fixture, browserName }) => {
      test.setTimeout(240_000);
      const name = `Review ${browserName} ${Date.now()}`;

      // 1. the new round form: no juror, two monuments, "Create and review images"
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      await page.getByLabel('Round name (optional)').fill(name);
      await page.evaluate(() => document.getElementById('jurors').tomselect.clear());
      await expect(page.locator('#distribution')).toBeDisabled();
      await openFiltering(page);
      await page.locator('#monumentIds').fill(BOTH);
      await page.getByRole('button', { name: 'Create and review images' }).click();
      await expect(page).toHaveURL((url) => url.pathname === '/admin/rounds');
      const row = page.getByRole('row').filter({ hasText: name });
      const edit = new URL(await row.locator('a[href*="/admin/rounds/edit"]').first().getAttribute('href'), page.url());
      const roundId = edit.searchParams.get('id');
      expect(roundId).toMatch(/^\d+$/);

      // 2. held in review: the files of both monuments (the ids' spaces trimmed)
      const before = readyCount(await reviewState(page, roundId, 'Images ready'));
      expect(before).toBeGreaterThan(0);

      // 3. the picker: the contest's jurors chosen; with none, Distribute is refused and says so
      const picker = page.locator('#jurors');
      await expect(picker).toBeAttached();
      expect(await page.evaluate(() => document.getElementById('jurors').tomselect.items.length)).toBeGreaterThan(1);
      await page.evaluate(() => document.getElementById('jurors').tomselect.clear());
      await expect(page.locator('#distribution')).toBeDisabled();
      await page.getByRole('button', { name: 'Distribute to jurors' }).click();
      await expect(page.locator('#flash-error')).toContainText('has no jurors');
      await expect(page.locator('#round-review-state')).toContainText('Images ready');

      // 4. Edit the round: "Re-apply filters" with one monument
      await page.getByRole('link', { name: 'Edit the round' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Edit round' })).toBeVisible();
      const reapply = page.getByRole('button', { name: 'Re-apply filters' });
      await expect(reapply).toBeVisible();
      await openFiltering(page);
      await expect(page.locator('#monumentIds')).toBeEditable();
      await page.locator('#monumentIds').fill(ONE);
      expect(await postToReview(page, roundId, reapply)).toMatch(/id="flash-success"[^>]*>[^<]*its filters are being re-applied/);
      const after = readyCount(await reviewState(page, roundId, 'Images ready'));
      expect(after).toBeGreaterThan(0);
      expect(after).toBeLessThan(before);
      await expect(page.locator('#round-review-reapplied')).toContainText(`${before - after} removed`);

      // 5. two jurors no other test signs in as, one per file: Distribute opens the round
      const signedIn = new Set(readServer().jurors.map((j) => String(j.id)));
      const ids = await page.evaluate((taken) =>
        Object.keys(document.getElementById('jurors').tomselect.options).filter((id) => !taken.includes(id)).slice(0, 2),
      [...signedIn]);
      expect(ids).toHaveLength(2);
      await page.evaluate((chosen) => {
        const ts = document.getElementById('jurors').tomselect;
        ts.clear();
        chosen.forEach((id) => ts.addItem(id));
      }, ids);
      await expect(page.locator('#distribution option')).toHaveText(['Each file to all jurors', 'Each file to 1 juror(s)']);
      await page.locator('#distribution').selectOption('1');
      // the redirected page's message, in the page as served (a reload by the job's status script drops it)
      expect(await postToReview(page, roundId, page.getByRole('button', { name: 'Distribute to jurors' })))
        .toMatch(/id="flash-success"[^>]*>[^<]*being distributed to the jurors/);
      await reviewState(page, roundId, 'have been distributed');
    });
});
