// A round's review before distribution (P3, docs/user/admin-guide.md "Reviewing a round's images",
// "Choosing the jurors"), as the contest's admin. The new-round form has filters only, and two
// buttons (the maintainer's decision of 2026-11-16): "Review images first" holds the round in review
// (the rounds list, then its review page); "Choose jurors next" goes to the round's jurors form,
// which waits for the selection and then offers the jurors. The review page's Distribute opens that
// form, which refuses with no juror; Edit the round offers "Re-apply filters", whose narrower monument
// filter (ids separated by ", ") takes files out and the review page shows by how many; a comment in
// the review's large view; two jurors picked, one per file, Distribute opens the round.
//
// It writes to the shared fixture: two rounds per engine, named after it: one distributed to two of
// the contest's jurors that no other test signs in as (server.json's jurors are the voting tests'),
// one left in review (hidden from every juror), so no other test's rounds, menus or votes change. The
// fill and the distribution are background jobs: a page is reloaded until its state says they are done.
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

/** Clicks `button` (a POST answered by a redirect to `path`) and returns the text of the page the
  * redirect loads: its flash message is checked there, as the page shows it while a job runs, since
  * the job's status script reloads the page (without the flash) as the job ends. */
async function postTo(page, path, button) {
  const [response] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === path),
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

/** The new-round form with the name and the monuments: no jurors, no file distribution. */
async function newRound(page, contestId, name, monuments) {
  await page.goto(`/admin/rounds/edit?contestId=${contestId}`);
  await page.getByLabel('Round name (optional)').fill(name);
  await expect(page.locator('#jurors')).toHaveCount(0);
  await expect(page.locator('#distribution')).toHaveCount(0);
  await expect(page.locator('#distributeAfter')).toHaveCount(0);
  await openFiltering(page);
  await page.locator('#monumentIds').fill(monuments);
}

test.describe('an admin', () => {
  test.use({ role: 'admin' });

  test('creates a round with "Review images first", re-applies its filters, comments, then distributes from its jurors form',
    { tag: ['@A-21', '@A-01'] }, async ({ page, fixture, browserName }) => {
      test.setTimeout(240_000);
      const name = `Review ${browserName} ${Date.now()}`;

      // 1. the new round form: filters only, "Review images first"
      await newRound(page, fixture.contestId, name, BOTH);
      await page.getByRole('button', { name: 'Review images first' }).click();
      await expect(page).toHaveURL((url) => url.pathname === '/admin/rounds');
      const row = page.getByRole('row').filter({ hasText: name });
      const edit = new URL(await row.locator('a[href*="/admin/rounds/edit"]').first().getAttribute('href'), page.url());
      const roundId = edit.searchParams.get('id');
      expect(roundId).toMatch(/^\d+$/);
      const reviewPath = `/admin/rounds/${roundId}/review`;
      const jurorsPath = `/admin/rounds/${roundId}/jurors`;

      // 2. held in review: the files of both monuments (the ids' spaces trimmed)
      const before = readyCount(await reviewState(page, roundId, 'Images ready'));
      expect(before).toBeGreaterThan(0);

      // 3. Distribute opens the jurors form: the contest's jurors chosen; with none it refuses and says so
      await page.locator('#round-review-distribute-link').click();
      await expect(page).toHaveURL((url) => url.pathname === jurorsPath);
      await expect(page.locator('#round-jurors-images')).toContainText(String(before));
      expect(await page.evaluate(() => document.getElementById('jurors').tomselect.items.length)).toBeGreaterThan(1);
      await page.evaluate(() => document.getElementById('jurors').tomselect.clear());
      await expect(page.locator('#distribution')).toBeDisabled();
      await page.getByRole('button', { name: 'Distribute to jurors' }).click();
      await expect(page).toHaveURL((url) => url.pathname === jurorsPath);
      await expect(page.locator('#flash-error')).toContainText('has no jurors');

      // 4. Edit the round: "Re-apply filters" with one monument
      await page.goto(reviewPath);
      await page.getByRole('link', { name: 'Edit the round' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Edit round' })).toBeVisible();
      const reapply = page.getByRole('button', { name: 'Re-apply filters' });
      await expect(reapply).toBeVisible();
      await openFiltering(page);
      await expect(page.locator('#monumentIds')).toBeEditable();
      await page.locator('#monumentIds').fill(ONE);
      expect(await postTo(page, reviewPath, reapply)).toMatch(/id="flash-success"[^>]*>[^<]*its filters are being re-applied/);
      const after = readyCount(await reviewState(page, roundId, 'Images ready'));
      expect(after).toBeGreaterThan(0);
      expect(after).toBeLessThan(before);
      await expect(page.locator('#round-review-reapplied')).toContainText(`${before - after} removed`);

      // 5. a comment in the review's large view: an ordinary round comment, shown there
      const note = `Review note ${browserName}`;
      // a tile's large view, as the page serves its first tile's link (data-largeUrl, which the
      // gallery script opens on a click)
      const html = await (await page.request.get(reviewPath)).text();
      const largeUrl = /data-largeUrl="([^"]+)"/.exec(html)?.[1]?.replace(/&amp;/g, '&');
      expect(largeUrl).toContain(`/admin/rounds/${roundId}/review/file/`);
      await page.goto(largeUrl);
      await page.locator('#addComment textarea').fill(note);
      await page.locator('#saySomething').click();
      await expect(page.locator('.large-comments')).toContainText(note);

      // 6. two jurors no other test signs in as, one per file: Distribute opens the round
      await page.goto(jurorsPath);
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
      expect(await postTo(page, reviewPath, page.getByRole('button', { name: 'Distribute to jurors' })))
        .toMatch(/id="flash-success"[^>]*>[^<]*being distributed to the jurors/);
      await reviewState(page, roundId, 'have been distributed');

      // reassigning a juror's files in this round of one juror per file (round_rebalance), read only: the
      // other juror, active, is ticked; changing the ticks disables the confirm, with a hint, until the
      // figures are shown for them (it posts the receivers they were shown for)
      await page.goto(`/roundstat/${roundId}`);
      await page.locator('a.round-juror-reassign').first().click();
      const receivers = page.locator('#round-juror-reassign-receivers input[name="receiver"]');
      await expect(receivers).toHaveCount(1);
      await expect(receivers.first()).toBeChecked();
      const confirm = page.locator('#round-juror-reassign-form button[type="submit"]');
      const stale = page.locator('#round-juror-reassign-stale');
      // the hint is a status, always in the page: empty, then filled in (announced)
      await expect(confirm).toBeEnabled();
      await expect(stale).toHaveText('');
      await receivers.first().uncheck();
      await expect(confirm).toBeDisabled();
      await expect(stale).toContainText('Show what this does');
      await expect(confirm).toHaveAttribute('aria-describedby', 'round-juror-reassign-stale');
      await receivers.first().check();
      await expect(confirm).toBeEnabled();
      await expect(stale).toHaveText('');
      await expect(confirm).not.toHaveAttribute('aria-describedby');
    });

  test('creates a round with "Choose jurors next": its jurors form waits for the selection, then offers the jurors',
    { tag: ['@A-21'] }, async ({ page, fixture, browserName }) => {
      test.setTimeout(150_000);
      const name = `Jurors next ${browserName} ${Date.now()}`;

      await newRound(page, fixture.contestId, name, ONE);
      await page.getByRole('button', { name: 'Choose jurors next' }).click();
      await expect(page).toHaveURL((url) => /^\/admin\/rounds\/\d+\/jurors$/.test(url.pathname));
      const jurorsPath = new URL(page.url()).pathname;

      // the form once the selection has ended (the job's status script reloads the page; reloaded here too)
      await expect(async () => {
        await page.goto(jurorsPath);
        await expect(page.locator('#round-jurors-form')).toBeVisible({ timeout: 1_000 });
      }).toPass({ timeout: 90_000, intervals: [500, 1_000, 2_000] });
      const images = Number((await page.locator('#round-jurors-images .wlx-count').textContent()).replace(/\D/g, ''));
      expect(images).toBeGreaterThan(0);
      // nothing distributed: the round waits in review, hidden from the jurors
      await page.locator('#round-jurors-review').click();
      await expect(page.locator('#round-review-state')).toContainText(`Images ready: ${images}`);
    });
});
