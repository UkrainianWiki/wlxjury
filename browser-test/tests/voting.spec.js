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
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath, one } from '../lib/actions.js';
import { png } from '../lib/placeholder.js';

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

  test('in the binary round\'s gallery by a tile\'s ✓ button', { tag: ['@J-16', '@J-36', '@S-02'] }, async ({ page, context, fixture }) => {
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
    // the tile shows the vote as a ✓ chip in its lower-left corner (where the organisers' tally
    // is), none unrated, and has no coloured frame (the maintainer's decisions of 2026-11-15):
    // one chip (the fixture has no comments), named once for screen readers
    const tile = select.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " ug-tile ")][1]');
    const mark = tile.locator('.wlx-chip-vote');
    await expect(mark).toHaveCount(wasPressed ? 0 : 1);
    if (!wasPressed) {
      await expect(mark.locator('i.bi-check-lg')).toBeVisible();
      await expect(tile.locator('.wlx-tile-chips > .wlx-chip')).toHaveCount(1);
      await expect(tile.locator('.wlx-chip-time')).toHaveCount(0); // in file order: no time
      await expect(mark.locator('.visually-hidden')).toHaveText('Selected');
      const [t, m] = [await tile.boundingBox(), await mark.boundingBox()];
      expect(m.x - t.x, 'the chip at the left').toBeLessThan(12);
      expect(t.y + t.height - (m.y + m.height), 'the chip at the foot').toBeLessThan(12);
      // the chips under the ✓ and ✗ buttons: the point at the ✗'s centre is the ✗'s
      await tile.hover();
      const reject = tile.locator('.ug-icon-reject');
      await expect(reject).toBeVisible();
      const r = await reject.boundingBox();
      expect(await page.evaluate(([x, y]) => !!document.elementFromPoint(x, y)?.closest('.ug-icon-reject'),
        [r.x + r.width / 2, r.y + r.height / 2]), 'the ✗ button not covered').toBe(true);
      expect(await tile.locator('.wlx-tile-chips').evaluate((el) => Number(getComputedStyle(el).zIndex)))
        .toBeLessThan(await reject.evaluate((el) => Number(getComputedStyle(el).zIndex)));
    }
    expect(await tile.evaluate((el) => getComputedStyle(el, '::before').borderTopWidth)).toBe('0px');
    // the stored vote, in the file's large view
    const [, pageId, rate] = new URL(voted.url()).pathname.match(/\/pageid\/(\d+)\/select\/(-?\d+)$/);
    expect(Number(rate)).toBe(wasPressed ? 0 : 1);
    const large = await context.newPage();
    await large.goto(largePath(fixture.roundBinaryId, juror.id, pageId));
    expect(await binaryVote(large)).toBe(Number(rate));
    await large.close();
    if (!wasPressed) {
      // newest votes first: the file first, its mark and time in one chip ("✓ 15 Nov, 14:32"),
      // the time muted, one name for both ("Selected, 15 Nov, 14:32"); no second chip for the time
      const latest = await context.newPage();
      await latest.goto(`${galleryPath(fixture.roundBinaryId, juror.id)}?sort=latest-vote`);
      const first = latest.locator('.ug-tile').first();
      const chip = first.locator('.wlx-chip-vote');
      await expect(chip.locator('i.bi-check-lg')).toBeVisible();
      await expect(chip.locator('.wlx-chip-time time')).toBeVisible();
      await expect(first.locator('.wlx-chip-time')).toHaveCount(1);
      await expect(first.locator('.wlx-tile-chips > .wlx-chip')).toHaveCount(1);
      await expect(chip.locator('.visually-hidden')).toHaveText(/^Selected, .*\d\d:\d\d$/);
      const [time, mark] = await Promise.all([chip.locator('.wlx-chip-time'), chip.locator('i')]
        .map((l) => l.evaluate((el) => getComputedStyle(el).color)));
      expect(time, 'the time muted, not in the mark\'s colour').not.toBe(mark);
      await latest.close();
    }
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

// The large view's comments link (navigationButtons commentsLink): after the vote's summary, the
// tiles' comment chip ("💬 1") as a link to the comments section under the file; a click moves
// there. The tile's comments chip links the same place from the gallery (tile-votes.js addComments).
// The fixture has no comments: the contest's admin writes one on a file none of the tests' jurors
// has (lib/server.js commentFile), so their views still have none (navbar.spec.js); a rerun on the
// same server finds it there.
test.describe('the large view\'s comments link', () => {
  test('scrolls to the comments section, with the count it shows', { tag: '@J-25' }, async ({ contextAs, fixture, browserName }) => {
    const context = await contextAs('admin');
    const page = await context.newPage();
    const other = { id: fixture.commentFile.juror, pageId: fixture.commentFile.pageId };
    expect(other.pageId, 'server.json has a file for the comment (restart the fixture)').toBeGreaterThan(0);
    const url = largePath(fixture.roundBinaryId, other.id, other.pageId);
    await page.goto(url);
    const link = page.locator('a.wlx-chip-comments');
    if (await link.count() === 0) {
      await page.locator('#addComment textarea').fill('A comment for the comments link (browser test)');
      await Promise.all([page.waitForNavigation(), page.locator('#saySomething').click()]);
      await page.goto(url);
    }
    const count = await page.locator('.large-comments .msg').count();
    expect(count).toBeGreaterThan(0);
    await expect(link).toHaveAttribute('href', '#comments');
    await expect(link.locator('span[aria-hidden=true]')).toHaveText(String(count));
    await expect(link).toHaveAccessibleName(`${count} comment${count === 1 ? '' : 's'}: open the comments`);
    // the heading below the view's height first, then in view after the click: the view made
    // shorter than the heading's place (the file loaded, which pushes the comments down; the
    // file's height follows the view's in some engines, so measured again after each change)
    await page.waitForFunction(() => { const img = document.getElementById('large_image'); return !img || img.complete; });
    const heading = page.locator('h2#comments');
    let height = 400;
    for (let i = 0; i < 4; i++) {
      await page.setViewportSize({ width: 1366, height });
      await page.evaluate(() => window.scrollTo(0, 0));
      const top = await heading.evaluate((h) => h.getBoundingClientRect().top);
      if (top >= height) break;
      height = Math.max(120, Math.floor(top * 0.7));
    }
    await expect(heading).not.toBeInViewport();
    await link.click();
    await expect(page).toHaveURL(/#comments$/);
    await expect(heading).toBeInViewport();

    // the gallery page with the file (the large view's Gallery button): its tile's comments chip,
    // the one chip that is a link, opens the file's large view at the comments
    await page.goto(await page.locator('[data-nav=gallery]').getAttribute('href'));
    const chip = page.locator(`.ug-tile a.wlx-chip-comments[href*="/pageid/${other.pageId}"]`);
    await expect(chip).toBeVisible();
    // its name says its shown number (another engine sharing the server may have commented since)
    await expect(chip).toHaveAccessibleName(/^\d+ comments?: open the comments$/);
    const named = (await chip.getAttribute('title')).match(/^(\d+) /)[1];
    await expect(chip.locator('span[aria-hidden=true]')).toHaveText(named);
    const box = await chip.boundingBox();
    expect(box.height, 'a 24 px target').toBeGreaterThanOrEqual(24);
    expect(box.width, 'a 24 px target').toBeGreaterThanOrEqual(24);
    await chip.click();
    await page.waitForURL(new RegExp(`/pageid/${other.pageId}.*#comments$`));
    await expect(heading).toBeInViewport();

    // the comments list (commentsReport commentsLink): the file's row, after its vote marks, has
    // the same chip with the row's own comments, a link to its large view at the comments
    await page.goto(`/comments/round/${fixture.roundBinaryId}/user/${other.id}/page/1`);
    const row = page.locator(`article.wlx-comments-row[id="${other.pageId}"]`);
    const rowChip = row.locator('.wlx-comments-result > a.wlx-chip-comments');
    const shown = await row.locator('.msg').count();
    await expect(rowChip.locator('span[aria-hidden=true]')).toHaveText(String(shown));
    await expect(rowChip).toHaveAccessibleName(`${shown} comment${shown === 1 ? '' : 's'}: open the comments`);
    const target = await rowChip.boundingBox();
    expect(target.height, 'a 24 px target').toBeGreaterThanOrEqual(24);
    if (browserName === 'chromium') {
      const results = await new AxeBuilder({ page }).include(`article.wlx-comments-row[id="${other.pageId}"]`)
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map((v) => v.id), 'the row with its chip: no accessibility findings').toEqual([]);
    }
    await rowChip.click();
    await page.waitForURL(new RegExp(`/large/round/${fixture.roundBinaryId}/user/${other.id}/pageid/${other.pageId}.*#comments$`));
    await expect(page.locator('h2#comments')).toBeInViewport();
  });

  // Opened at its comments (the chips' links) while the file is still on its way: the file's box
  // has its loaded height before it arrives (Global.largeImageBox), so the comments are in view at
  // once and stay where they are when it does. A file of other proportions than the stored ones
  // takes another height once loaded (large-view.js removes the box): the page scrolls back to the
  // comments (keepCommentsInView), unless the user has scrolled since, and not on a reload, where
  // the place is the browser's. The upload host's answer is held until the test serves it.
  test('keeps the comments in view while the file loads', { tag: '@J-25' }, async ({ contextAs, fixture }) => {
    const context = await contextAs('admin');
    const page = await context.newPage();
    const other = { id: fixture.commentFile.juror, pageId: fixture.commentFile.pageId };
    expect(other.pageId, 'server.json has a file for the comment (restart the fixture)').toBeGreaterThan(0);
    const url = largePath(fixture.roundBinaryId, other.id, other.pageId);
    // lower than the file, which so has the comments below it; narrow, so that a landscape file's
    // box is narrower than the column and a portrait one's as tall (another file's height then differs)
    await page.setViewportSize({ width: 700, height: 500 });
    const PLACEHOLDER = png(64, 48, [120, 140, 160]);
    let stored;
    let serve;
    let held;
    let requested;
    // the large image (the view's only file from the upload host), served when the test says
    await page.route((u) => u.hostname === 'upload.wikimedia.org', async (route) => {
      requested = route.request().url();
      const body = await held;
      await route.fulfill({ status: 200, contentType: 'image/png', body }).catch(() => {});
    });
    // a fresh load each time: from the view to the view at #comments would only move to the comments
    const open = async (path) => {
      if (serve) serve(PLACEHOLDER);
      await page.goto('about:blank');
      held = new Promise((resolve) => { serve = resolve; });
      requested = null;
      await page.goto(path, { waitUntil: 'domcontentloaded' });
    };
    // the file the page asked for, `tall` times as tall as the stored proportions make it: as wide as
    // its srcset says (the browser shows it the srcset's width over the sizes' wide)
    const file = async (tall) => {
      await expect.poll(() => requested, { message: 'the page asked for the file' }).toBeTruthy();
      const width = await image.evaluate((img, url) => {
        const candidate = img.srcset.split(/,\s+/).map((c) => c.trim().split(/\s+/))
          .find(([u]) => new URL(u, location.href).href === url);
        return candidate ? parseInt(candidate[1], 10) : 0;
      }, requested);
      expect(width, `the file asked for is in the srcset: ${requested}`).toBeGreaterThan(0);
      return png(width, Math.round(tall * width * stored.height / stored.width), [120, 140, 160]);
    };
    const image = page.locator('#large_image');
    const heading = page.locator('h2#comments');
    const box = () => image.evaluate((img) => { const r = img.getBoundingClientRect(); return { width: r.width, height: r.height }; });
    const loaded = () => page.waitForFunction(() => document.getElementById('large_image').complete);
    // how far the page is from where a link to the comments scrolls it: the heading at the top, or
    // as near as the page's end lets it
    const offComments = () => heading.evaluate((h) => {
      const root = document.scrollingElement;
      const target = Math.min(h.getBoundingClientRect().top + root.scrollTop, root.scrollHeight - root.clientHeight);
      return Math.abs(root.scrollTop - target);
    });

    // the stored proportions, in the box's width and height
    await open(url);
    stored = { width: Number(await image.getAttribute('width')), height: Number(await image.getAttribute('height')) };
    expect(stored.width, "the fixture's file has a stored size: its box has a width").toBeGreaterThan(0);
    expect(stored.height).toBeGreaterThan(0);

    // a file of the stored proportions: its box as tall before it arrives as the file after, so the
    // comments below don't move (where the engine scrolls to #comments before the file arrives is
    // its own: WebKit at times only once the page has loaded), and the page at them once loaded
    await open(`${url}#comments`);
    expect(await image.evaluate((img) => img.complete), 'the file is held').toBe(false);
    const before = await box();
    expect(before.height, 'the box has its height before the file arrives').toBeGreaterThan(100);
    serve(await file(1));
    await loaded();
    await expect(image).not.toHaveAttribute('width');
    await expect(image).not.toHaveAttribute('height');
    // as tall as its box (which may be wider while the file is on its way: object-fit, main.css)
    expect(Math.abs((await box()).height - before.height), 'the loaded file as tall as its box').toBeLessThanOrEqual(1);
    await page.waitForLoadState('load');
    await expect(heading).toBeInViewport();
    // polled: a smooth scroll may still be on its way at the page's load
    await expect.poll(offComments, { message: 'the page at the comments' }).toBeLessThanOrEqual(1.5);

    // a file of other proportions than the stored ones (twice as tall, or, for a box as tall as the
    // column already, half as tall): another height once loaded, the page back at the comments
    await open(`${url}#comments`);
    const reserved = await box();
    const columnHeight = await page.locator('#mainContent').evaluate((c) => c.clientHeight);
    const taller = reserved.height < columnHeight * 0.75;
    serve(await file(taller ? 2 : 0.5));
    await loaded();
    expect(Math.abs((await box()).height - reserved.height), 'the file of another height than its box').toBeGreaterThan(50);
    // once the page has loaded, the comments in view (a few px from the top: at the page's load it
    // scrolls only while their heading is out of view)
    await page.waitForLoadState('load');
    await expect(heading).toBeInViewport();

    // from here the page's own scrolls to the comments are counted (its scrollIntoView of them): where
    // the engine puts the page is its own (on a reload Chromium restores the user's place, WebKit goes
    // to #comments)
    await page.addInitScript(() => {
      const scrollIntoView = Element.prototype.scrollIntoView;
      window.wlxCommentsScrolls = 0;
      Element.prototype.scrollIntoView = function (...args) {
        if (this.id === 'comments') window.wlxCommentsScrolls += 1;
        return scrollIntoView.apply(this, args);
      };
    });
    const commentsScrolls = () => page.evaluate(() => window.wlxCommentsScrolls);

    // the user scrolls (a wheel) before the file (of other proportions) arrives: the page doesn't
    // scroll back to the comments
    await open(`${url}#comments`);
    await page.mouse.move(400, 250);
    await page.mouse.wheel(0, -300);
    const otherFile = await file(taller ? 2 : 0.5);
    serve(otherFile);
    await loaded();
    await page.waitForLoadState('load');
    expect(await commentsScrolls(), 'no scroll back to the comments after the user scrolled').toBe(0);

    // a reload (Back too: the view is no-store): no scroll to the comments, the place is the browser's
    held = Promise.resolve(otherFile);
    await page.reload({ waitUntil: 'load' });
    await loaded();
    expect(await commentsScrolls(), 'no scroll to the comments on a reload').toBe(0);

    // a control: opened at #comments with no user action, the page does scroll to them
    await open(`${url}#comments`);
    serve(otherFile);
    await loaded();
    await page.waitForLoadState('load');
    expect(await commentsScrolls(), 'the page scrolls to the comments when the file loads').toBeGreaterThan(0);
  });
});
