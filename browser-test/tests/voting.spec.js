// A juror votes in the binary and the rated round, in the large view by key and by click and in
// the gallery by click; after a vote in the large view, Back shows the stored vote (the pages
// are no-store), as a fresh load of the file does. Moved from scripts/ui_checks.py
// (vote_then_back), with the buttons found by role and name instead of their classes (U1).
// The large-view tests run in the routeless browser (lib/fixtures.js routelessAs): the guard's
// routes turn the browser's cache off and Playwright's Chromium has no back/forward cache, so
// with them a page without no-store would pass too. Without no-store they fail in Firefox. In
// Chromium, Back restores the no-store large view from the back/forward cache with the old vote
// (U23): there they assert just that (the page restored, the old vote shown, a fresh load showing
// the stored one), so they fail once Chromium stops doing it, the signal to revisit U23.
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

/** Waits for the vote's POST and the next file's page it leads to, after `act`; returns the POST's URL. */
async function voteAndMove(page, act) {
  const from = page.url();
  const posted = page.waitForRequest((r) => r.method() === 'POST' && /\/select\/-?\d+/.test(r.url()));
  await act();
  const request = await posted;
  await page.waitForURL((u) => u.href !== from);
  return new URL(request.url());
}

/** Back after the vote: the voted file, showing the stored vote `expected`, as a fresh load of
  * it does; in Chromium (U23), the page restored from the back/forward cache with the vote it had
  * `before`. */
async function expectBackShowsVote(page, url, read, expected, before, browserName) {
  // 'commit': a page the back/forward cache restores fires no load event
  await page.goBack({ waitUntil: 'commit' });
  await expect(page).toHaveURL(url);
  if (browserName === 'chromium') {
    test.info().annotations.push({ type: 'U23', description: 'Chromium restores the no-store large view from its back/forward cache (UI practices §9)' });
    await expect.poll(() => page.evaluate(() => window.wlxPageshow), { message: 'U23: restored from the back/forward cache' })
      .toEqual({ persisted: true });
    expect(await read(page), 'U23: the restored page shows the vote from before').toBe(before);
  } else {
    // the reloaded page parsed: its buttons are there (expect.poll doesn't retry a reader that throws)
    await page.waitForLoadState('domcontentloaded');
    await expect.poll(() => read(page), { message: 'Back shows the stored vote' }).toBe(expected);
  }
  const fresh = await page.context().newPage();
  await fresh.goto(url);
  expect(await read(fresh), 'a fresh load shows the stored vote').toBe(expected);
  await fresh.close();
}

/** The juror's large view of `url`, in the routeless browser. */
async function largeView(routelessAs, url) {
  const context = await routelessAs('juror');
  // each pageshow: persisted is true for a page the back/forward cache brought back
  await context.addInitScript(() => {
    window.addEventListener('pageshow', (e) => { window.wlxPageshow = { persisted: e.persisted }; });
  });
  const page = await context.newPage();
  await page.goto(url);
  return page;
}

test.describe('a juror votes', () => {
  test('in the binary round\'s large view by key, then Back shows the vote', { tag: ['@J-21', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    const page = await largeView(routelessAs, url);
    const before = await binaryVote(page);
    // Space selects unless selected; Delete rejects
    const [key, expected] = before === 1 ? ['Delete', -1] : ['Space', 1];
    const posted = await voteAndMove(page, () => page.keyboard.press(key));
    expect(posted.origin, 'the vote posts to this host').toBe(new URL(baseURL).origin);
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${juror.pageId}/select/${expected}$`));
    await expectBackShowsVote(page, url, binaryVote, expected, before, browserName);
  });

  test('in the binary round\'s large view by its button, then Back shows the vote', { tag: ['@J-20', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    const page = await largeView(routelessAs, url);
    const before = await binaryVote(page);
    const [button, expected] = before === -1 ? [selectButton(page), 1] : [rejectButton(page), -1];
    const posted = await voteAndMove(page, () => button.click());
    expect(posted.pathname).toMatch(new RegExp(`/select/${expected}$`));
    await expectBackShowsVote(page, url, binaryVote, expected, before, browserName);
  });

  test('in the rated round\'s large view by key, then Back shows the rate', { tag: ['@J-21', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    const page = await largeView(routelessAs, url);
    const before = await ratedVote(page);
    const expected = before === 3 ? 4 : 3;
    const posted = await voteAndMove(page, () => page.keyboard.press(String(expected)));
    expect(posted.origin).toBe(new URL(baseURL).origin);
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${juror.ratedPageId}/select/${expected}$`));
    await expectBackShowsVote(page, url, ratedVote, expected, before, browserName);
  });

  test('in the rated round\'s large view by a star, then Back shows the rate', { tag: ['@J-20', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    const page = await largeView(routelessAs, url);
    const before = await ratedVote(page);
    const expected = before === 6 ? 7 : 6;
    // raty's stars: <i data-alt="n"> in the stars' element (no role: see the accessibility report)
    const star = page.locator(`#rate-image [data-alt="${expected}"]`);
    await expect(star).toHaveCount(1);
    const posted = await voteAndMove(page, () => star.click());
    expect(posted.pathname).toMatch(new RegExp(`/select/${expected}$`));
    await expectBackShowsVote(page, url, ratedVote, expected, before, browserName);
  });

  test('in the binary round\'s gallery by a tile\'s thumb', { tag: ['@J-16', '@J-36', '@S-02'] }, async ({ page, context, fixture }) => {
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

  test('in the rated round\'s gallery by a tile\'s star', { tag: '@J-17' }, async ({ page, context, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundRatingId, juror.id));
    // the first tile's stars: raty's <i data-alt="n">, as in the large view (tile-votes.js adds
    // them to unitegallery's tiles, outside #gallery); its rate first, then another one
    const stars = page.locator('[data-alt="1"]').first().locator('..');
    await expect(stars).toBeVisible();
    const current = await stars.evaluate((el) => Number(window.jQuery(el).raty('score')) || 0);
    const expected = current === 5 ? 6 : 5;
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await stars.locator(`[data-alt="${expected}"]`).click();
    const voted = await response;
    expect(voted.status()).toBe(200);
    const [, pageId, rate] = new URL(voted.url()).pathname.match(/\/pageid\/(\d+)\/select\/(\d+)$/);
    expect(Number(rate)).toBe(expected);
    const large = await context.newPage();
    await large.goto(largePath(fixture.roundRatingId, juror.id, pageId));
    expect(await ratedVote(large)).toBe(expected);
    await large.close();
  });
});
