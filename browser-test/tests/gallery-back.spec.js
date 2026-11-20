// Back to the gallery from a file's large view, and Esc there (the maintainer's decisions of
// 2026-11-19): the list opens at #file-<pageId> (RoundList.fileFragment), which matches no element
// id, so the browser no longer jumps the file to the window's top edge and scrolls the navbar out of
// view; file-anchor.js scrolls to the file (data-file-anchor) only when it is not wholly in view.
//   - from the first file, in the by-rate gallery, the comments report and the tiles: the page at
//     its top, the navbar in view;
//   - from a file several rows down in the by-rate gallery: that file in view;
//   - the cause, kept as a check: the old #<pageId> fragment jumps the by-rate gallery's first file
//     to the top edge (its tile's id), the new one doesn't.
//   - the same in the comments report, a row further down, its title in view;
// As an organizer, all the jurors' votes (user 0), read-only; the comments report as root, in
// lib/server.js anchorRound, a round of one juror in a contest of its own, whose files get this
// spec's own comments (written once per server), so that no other spec sees them, nor this one
// theirs.
import { test, expect } from '../lib/fixtures.js';
import { largePath } from '../lib/actions.js';

/** The page's scroll position once its scripts ran (file-anchor.js runs on DOMContentLoaded). */
async function settledScrollY(page) {
  await page.waitForLoadState('load');
  // a frame for a scroll to apply
  return page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(window.scrollY))));
}

/** Back from the file's large view (its "Back to gallery" link) or by Esc; waits for the list. */
async function backFrom(page, largeHref, how) {
  await page.goto(largeHref);
  const back = page.locator('a[data-nav="gallery"]');
  await expect(back).toBeVisible();
  expect(await back.getAttribute('href'), 'the link ends in #file-<pageId>').toMatch(/#file-\d+$/);
  if (how === 'esc') {
    await Promise.all([page.waitForURL(/#file-\d+$/), page.keyboard.press('Escape')]);
  } else {
    await Promise.all([page.waitForURL(/#file-\d+$/), back.click()]);
  }
}

/** The first file's large view in the list's HTML as the server sends it (the tiles' links are
  * data-largeUrl attributes, which Unite Gallery's tiles don't keep in the page). */
async function firstLargeHref(page, list, attribute) {
  // the by-rate page of all the jurors takes several seconds under a parallel run
  const html = await (await page.request.get(list, { timeout: 60_000 })).text();
  const m = html.match(new RegExp(`${attribute}="([^"]*/large/[^"]*)"`));
  expect(m, `${list} lists a file`).not.toBeNull();
  return m[1].replace(/&amp;/g, '&');
}

/** The comments report of lib/server.js anchorRound's juror, after making sure it lists four of
  * their files: this spec's own comments on them, written as root once per server, in a contest of
  * their own. */
async function commentsList(page, fixture) {
  const anchor = fixture.anchorRound;
  test.skip(!anchor, "the fixture has no comments anchor round (the guides' screenshot mode)");
  for (const pageId of anchor.pageIds.slice(0, 4)) {
    await page.goto(largePath(anchor.roundId, anchor.jurorId, pageId));
    if (await page.locator('a.wlx-chip-comments').count() === 0) {
      await page.locator('#addComment textarea').fill("A comment for the comments report's Back checks (browser test)");
      await Promise.all([page.waitForNavigation(), page.locator('#saySomething').click()]);
    }
  }
  return `/comments/round/${anchor.roundId}/user/${anchor.jurorId}/page/1`;
}

const VIEWS = [
  ['the by-rate gallery', 'organizer', async (page, f) => `/byrate/round/${f.roundRatingId}/user/0/page/1`, 'href'],
  ['the comments report', 'root', commentsList, 'href'],
  ['the tiles', 'organizer', async (page, f) => `/gallery/round/${f.roundBinaryId}/user/0/page/1`, 'data-largeUrl'],
];

for (const [name, role, listOf, attribute] of VIEWS) {
  for (const how of ['back', 'esc']) {
    test(`${how === 'esc' ? 'Esc' : 'Back'} from the first file of ${name} keeps the navbar in view`, { tag: '@O-02' },
      async ({ contextAs, fixture }) => {
        const context = await contextAs(role);
        const page = await context.newPage();
        const list = await listOf(page, fixture);
        await backFrom(page, await firstLargeHref(page, list, attribute), how);
        expect(await settledScrollY(page), 'the page at its top').toBe(0);
        await expect(page.locator('nav.navbar').first()).toBeInViewport({ ratio: 1 });
        await context.close();
      });
  }
}

test('Back from a file several rows down in the by-rate gallery shows that file', { tag: '@O-02' },
  async ({ contextAs, fixture }) => {
    // a short window: the page's last files several rows below it
    const context = await contextAs('organizer', { viewport: { width: 1366, height: 500 } });
    const page = await context.newPage();
    await page.goto(`/byrate/round/${fixture.roundRatingId}/user/0/page/1`);
    const tile = page.locator('li[data-file-anchor]').last();
    await expect(tile).not.toBeInViewport();
    const pageId = await tile.getAttribute('data-file-anchor');
    const largeHref = await tile.locator('a[href*="/large/"]').first().getAttribute('href');
    await backFrom(page, largeHref, 'back');
    expect(new URL(page.url()).hash).toBe(`#file-${pageId}`);
    await settledScrollY(page);
    await expect(page.locator(`li[data-file-anchor="${pageId}"]`)).toBeInViewport({ ratio: 1 });
    expect(await page.evaluate(() => window.scrollY), 'scrolled down to it').toBeGreaterThan(0);
    await context.close();
  });

test('Back from a row further down in the comments report shows that row, its title in view', { tag: '@O-02' },
  async ({ contextAs, fixture }) => {
    // a short window: the report's last rows below it
    const context = await contextAs('root', { viewport: { width: 1366, height: 500 } });
    const page = await context.newPage();
    const list = await commentsList(page, fixture);
    await page.goto(list);
    await page.waitForLoadState('load');
    const row = page.locator('article[data-file-anchor]').last();
    await expect(row).not.toBeInViewport();
    const pageId = await row.getAttribute('data-file-anchor');
    const largeHref = await row.locator('a.wlx-comments-thumb').getAttribute('href');
    await backFrom(page, largeHref, 'back');
    expect(new URL(page.url()).hash).toBe(`#file-${pageId}`);
    await settledScrollY(page);
    const back = page.locator(`article[data-file-anchor="${pageId}"]`);
    await expect(back).toBeInViewport();
    await expect(back.locator('h2')).toBeInViewport({ ratio: 1 });
    expect(await page.evaluate(() => window.scrollY), 'scrolled down to it').toBeGreaterThan(0);
    await context.close();
  });

test('the cause: the old #<pageId> fragment jumps the first file to the top edge, #file-<pageId> does not',
  { tag: '@O-02' }, async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer');
    const page = await context.newPage();
    const list = `/byrate/round/${fixture.roundRatingId}/user/0/page/1`;
    await page.goto(list);
    const pageId = await page.locator('li[data-file-anchor]').first().getAttribute('data-file-anchor');
    await page.goto('about:blank');
    await page.goto(`${list}#${pageId}`);
    // the old fragment's jump: the by-rate gallery's bar is sticky now (the reappearing top bar,
    // topbar.spec.js) and the root's scroll-padding-top its height, so the browser puts the file
    // below the bar, never under it
    await settledScrollY(page);
    const nav = page.locator('nav.navbar').first();
    await expect(nav).toBeInViewport({ ratio: 1 });
    const file = page.locator(`li[data-file-anchor="${pageId}"]`);
    const [n, f] = [await nav.boundingBox(), await file.boundingBox()];
    expect(f.y, "the old fragment's file below the bar").toBeGreaterThanOrEqual(n.y + n.height - 1);
    await page.goto('about:blank');
    await page.goto(`${list}#file-${pageId}`);
    expect(await settledScrollY(page)).toBe(0);
    await expect(page.locator('nav.navbar').first()).toBeInViewport({ ratio: 1 });
    await context.close();
  });
