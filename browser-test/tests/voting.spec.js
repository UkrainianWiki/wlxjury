// A juror votes in the binary and the rated round, in the large view by key and by click and in
// the gallery by click; after a vote in the large view, Back shows the stored vote (the pages
// are no-store), as a fresh load of the file does. Moved from scripts/ui_checks.py
// (vote_then_back), with the buttons found by role and name instead of their classes (U1).
// Each engine votes as its own juror (lib/server.js), so the engines can share a server; each
// test reads the file's vote first and casts one that changes it, so a rerun votes again.
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath, one } from '../lib/actions.js';

test.use({ role: 'juror' });

const selectButton = (page) => page.getByRole('button', { name: /^Select image/ });
const rejectButton = (page) => page.getByRole('button', { name: /^Reject image/ });

/** The binary round's vote the large view shows: 1 (only Reject offered), -1 (only Select), 0 (both). */
async function binaryVote(page) {
  const select = await selectButton(page).count();
  const reject = await rejectButton(page).count();
  expect(select + reject, 'the large view offers Select, Reject or both').toBeGreaterThan(0);
  return select && reject ? 0 : reject ? 1 : -1;
}

/** The rated round's rate the large view says ("Rating: 4"; 0 not rated). */
async function ratedVote(page) {
  const text = await (await one(page.getByText(/^(Rating: [\d.]+|Not rated)$/))).textContent();
  const rate = text.match(/Rating: ([\d.]+)/);
  return rate ? Number(rate[1]) : 0;
}

/** Waits for the vote's POST and the page it leads to, after `act`; returns the POST's path. */
async function voteAndMove(page, act) {
  const posted = page.waitForRequest((r) => r.method() === 'POST' && /\/select\/-?\d+/.test(r.url()));
  const moved = page.waitForNavigation();
  await act();
  const request = await posted;
  await moved;
  return new URL(request.url());
}

/** Back after the vote: the voted file, showing what a fresh load of it shows. */
async function expectBackShowsVote(page, context, url, read, expected) {
  await page.goBack();
  await expect(page).toHaveURL(url);
  await expect.poll(() => read(page), { message: 'Back shows the stored vote' }).toBe(expected);
  const fresh = await context.newPage();
  await fresh.goto(url);
  expect(await read(fresh), 'a fresh load shows the same vote').toBe(expected);
  await fresh.close();
}

test.describe('a juror votes', () => {
  test('in the binary round\'s large view by key, then Back shows the vote', async ({ page, context, fixture, baseURL }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    await page.goto(url);
    const before = await binaryVote(page);
    // Space selects unless selected; Delete rejects
    const [key, expected] = before === 1 ? ['Delete', -1] : ['Space', 1];
    const posted = await voteAndMove(page, () => page.keyboard.press(key));
    expect(posted.origin, 'the vote posts to this host').toBe(new URL(baseURL).origin);
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${juror.pageId}/select/${expected}$`));
    await expect(page).not.toHaveURL(url);
    await expectBackShowsVote(page, context, url, binaryVote, expected);
  });

  test('in the binary round\'s large view by its button, then Back shows the vote', async ({ page, context, fixture, baseURL }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    await page.goto(url);
    const before = await binaryVote(page);
    const [button, expected] = before === -1 ? [selectButton(page), 1] : [rejectButton(page), -1];
    const posted = await voteAndMove(page, () => button.click());
    expect(posted.pathname).toMatch(new RegExp(`/select/${expected}$`));
    await expectBackShowsVote(page, context, url, binaryVote, expected);
  });

  test('in the rated round\'s large view by key, then Back shows the rate', async ({ page, context, fixture, baseURL }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    await page.goto(url);
    const before = await ratedVote(page);
    const expected = before === 3 ? 4 : 3;
    const posted = await voteAndMove(page, () => page.keyboard.press(String(expected)));
    expect(posted.origin).toBe(new URL(baseURL).origin);
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${juror.ratedPageId}/select/${expected}$`));
    await expectBackShowsVote(page, context, url, ratedVote, expected);
  });

  test('in the rated round\'s large view by a star, then Back shows the rate', async ({ page, context, fixture, baseURL }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    await page.goto(url);
    const before = await ratedVote(page);
    const expected = before === 6 ? 7 : 6;
    // raty's stars: <i data-alt="n"> in the stars' element (no role: see the accessibility report)
    const star = page.locator(`#rate-image [data-alt="${expected}"]`);
    await expect(star).toHaveCount(1);
    const posted = await voteAndMove(page, () => star.click());
    expect(posted.pathname).toMatch(new RegExp(`/select/${expected}$`));
    await expectBackShowsVote(page, context, url, ratedVote, expected);
  });

  test('in the binary round\'s gallery by a tile\'s thumb', async ({ page, context, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const select = page.getByRole('button', { name: 'Selected' }).first();
    await expect(select).toBeVisible();
    const wasPressed = (await select.getAttribute('aria-pressed')) === 'true';
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await select.click();
    const voted = await response;
    expect(voted.status()).toBe(200);
    expect(voted.request().headers()['csrf-token'], 'the vote sends the CSRF token').toBeTruthy();
    await expect(select).toHaveAttribute('aria-pressed', String(!wasPressed));
    // the stored vote, in the file's large view
    const [, pageId, rate] = new URL(voted.url()).pathname.match(/\/pageid\/(\d+)\/select\/(-?\d+)$/);
    expect(Number(rate)).toBe(wasPressed ? 0 : 1);
    const large = await context.newPage();
    await large.goto(largePath(fixture.roundBinaryId, juror.id, pageId));
    expect(await binaryVote(large)).toBe(Number(rate));
    await large.close();
  });

  test('in the rated round\'s gallery by a tile\'s star', async ({ page, context, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundRatingId, juror.id));
    // the first tile's stars: raty's <i data-alt="n">, as in the large view (tile-votes.js
    // adds them to unitegallery's tiles, outside #gallery)
    const star = page.locator('[data-alt="5"]').first();
    await expect(star).toBeVisible();
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await star.click();
    const first = await response;
    expect(first.status()).toBe(200);
    const [, pageId, rate] = new URL(first.url()).pathname.match(/\/pageid\/(\d+)\/select\/(\d+)$/);
    expect(rate).toBe('5');
    const large = await context.newPage();
    await large.goto(largePath(fixture.roundRatingId, juror.id, pageId));
    expect(await ratedVote(large)).toBe(5);
    await large.close();
  });
});
