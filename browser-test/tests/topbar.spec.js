// The reappearing top bar (a trial, the maintainer's decision of 2026-11-20; topbar.js, main.css
// wlx-topbar-reappears): on the galleries and lists the navbar sticks to the window's top, hides
// while the user scrolls down and comes back when they scroll up; it stays under an open menu,
// whose list fits in the window and scrolls inside itself; Back to gallery puts the file below the
// shown bar; the large view has none; on a phone the same, its menus inside the window.
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

const NAV = 'body.wlx-topbar-reappears > nav.navbar';

/** The organizers' by-rate gallery of all the jurors: a long page of tiles. */
const byRate = (fixture) => `/byrate/round/${fixture.roundRatingId}/user/0/page/1`;

/** Scrolls the page by the mouse wheel, as a user does, over the middle of the window. */
async function wheel(page, dy) {
  const { width, height } = page.viewportSize();
  await page.mouse.move(width / 2, height * 0.75);
  await page.mouse.wheel(0, dy);
}

/** Scrolls the page by script (the scroll events are the same; a phone has no wheel). */
async function scrollBy(page, dy) {
  await page.evaluate((d) => window.scrollBy({ top: d, behavior: 'instant' }), dy);
}

test.describe('the reappearing top bar on a desktop', () => {
  test('hides while scrolling down, comes back on a scroll up, and is shown at the top', { tag: '@O-02' },
    async ({ contextAs, fixture }) => {
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 600 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      const nav = page.locator(NAV);
      await expect(nav).toHaveCount(1);
      await expect(nav).toBeInViewport({ ratio: 1 });
      expect(await nav.evaluate((n) => getComputedStyle(n).position)).toBe('sticky');

      await wheel(page, 800);
      await expect(nav).toHaveClass(/wlx-topbar-hidden/);
      await expect(nav).not.toBeInViewport();
      expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(100);

      await wheel(page, -150);
      await expect(nav).not.toHaveClass(/wlx-topbar-hidden/);
      await expect(nav).toBeInViewport({ ratio: 1 });
      // shown at the window's top, not in its place at the page's top
      expect((await nav.boundingBox()).y).toBeCloseTo(0, 0);

      await wheel(page, 600);
      await expect(nav).not.toBeInViewport();
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
      await expect(nav).toBeInViewport({ ratio: 1 });
      await context.close();
    });

  test('an open menu keeps the bar shown; its list fits in the window and scrolls inside itself', { tag: '@J-14' },
    async ({ contextAs, fixture }) => {
      // a short window: the region menu's list taller than the room under the bar
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 500 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      const nav = page.locator(NAV);
      await wheel(page, 800);
      await expect(nav).not.toBeInViewport();
      await wheel(page, -150);
      await expect(nav).toBeInViewport({ ratio: 1 });

      const region = page.locator('#regionmenu');
      await region.locator('> button').click();
      const list = region.locator('.dropdown-menu');
      await expect(list).toBeVisible();
      await expect(list.locator('.dropdown-item').nth(5)).toBeAttached();
      // the page scrolls down under the open menu (the wheel outside it): the bar stays
      const before = await page.evaluate(() => window.scrollY);
      await page.mouse.move(1300, 450);
      await page.mouse.wheel(0, 600);
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before + 100);
      await expect(nav).not.toHaveClass(/wlx-topbar-hidden/);
      await expect(nav).toBeInViewport({ ratio: 1 });
      await expect(list).toBeVisible();
      // the list ends inside the window and scrolls inside itself
      const box = await list.boundingBox();
      expect(box.y + box.height).toBeLessThanOrEqual(500);
      const scrollable = await list.evaluate((l) => l.scrollHeight > l.clientHeight);
      if (scrollable) {
        // its last item reached inside the window by scrolling the list (not by a wheel: Firefox
        // keeps a wheel that follows the page's on the page, its wheel transaction)
        const last = list.locator('.dropdown-item').last();
        await last.scrollIntoViewIfNeeded();
        // whole inside the list's scrollport and the window, to within a pixel: WebKit scrolls by
        // whole pixels, so an item at a fractional offset (Linux's font metrics) stayed clipped by
        // a layout unit (1/64 px, toBeInViewport's ratio 0.9995 in CI)
        await expect.poll(() => last.evaluate((item) => {
          const list = item.closest('.dropdown-menu');
          const r = item.getBoundingClientRect();
          const top = list.getBoundingClientRect().top + list.clientTop;
          const room = { top: Math.max(top, 0), bottom: Math.min(top + list.clientHeight, window.innerHeight) };
          return Math.max(room.top - r.top, r.bottom - room.bottom, r.height > 0 ? 0 : Infinity);
        })).toBeLessThanOrEqual(1);
        expect(await list.evaluate((l) => l.scrollTop)).toBeGreaterThan(0);
        await expect(nav).toBeInViewport({ ratio: 1 });
      }
      // closed: the next scroll down hides the bar again
      await page.keyboard.press('Escape');
      await expect(list).toBeHidden();
      // Esc gave the focus back to the toggle, which keeps the bar for a keyboard user: away
      await page.evaluate(() => document.activeElement.blur());
      // up first: the page may be at its end
      await wheel(page, -300);
      await expect(nav).toBeInViewport({ ratio: 1 });
      await wheel(page, 300);
      await expect(nav).not.toBeInViewport();
      await context.close();
    });

  test('the focus moving into the hidden bar shows it', async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 1366, height: 600 } });
    const page = await context.newPage();
    await page.goto(byRate(fixture));
    await page.waitForLoadState('load');
    const nav = page.locator(NAV);
    await wheel(page, 800);
    await expect(nav).not.toBeInViewport();
    await nav.locator('#regionmenu > button').focus();
    await expect(nav).toBeInViewport({ ratio: 1 });
    await context.close();
  });

  test('a menu opened from the keyboard while the bar is hidden: the bar comes back and stays', { tag: '@J-14' },
    async ({ contextAs, fixture }) => {
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 600 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      const nav = page.locator(NAV);
      await wheel(page, 800);
      await expect(nav).not.toBeInViewport();
      const button = nav.locator('#regionmenu > button');
      await button.focus();
      await page.keyboard.press('Space');
      await expect(button).toHaveAttribute('aria-expanded', 'true');
      await expect(nav.locator('#regionmenu .dropdown-menu')).toBeVisible();
      await expect(nav).toBeInViewport({ ratio: 1 });
      // the page scrolled down under the open menu: the bar stays
      await page.mouse.move(1300, 500);
      await page.mouse.wheel(0, 300);
      await expect(nav).not.toHaveClass(/wlx-topbar-hidden/);
      await expect(nav).toBeInViewport({ ratio: 1 });
      await page.keyboard.press('Escape');
      await expect(button).toBeFocused();
      await context.close();
    });

  /** Shift+Tab (Option+Shift+Tab in WebKit, whose Tab skips links) from the page's last file
    * onto a file above the window: the scroll up brings the bar back, the focused link below it. */
  async function shiftTabOntoFileAbove(contextAs, fixture, browserName) {
    // a low window: the files above it after a scroll to the page's end
    const context = await contextAs('organizer', { viewport: { width: 1366, height: 400 } });
    const page = await context.newPage();
    await page.goto(byRate(fixture));
    await page.waitForLoadState('load');
    const nav = page.locator(NAV);
    await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }));
    await wheel(page, 200);
    await expect(nav).not.toBeInViewport();
    await page.locator('li[data-file-anchor] a[href*="/large/"]').last().focus();
    const start = await page.evaluate(() => window.scrollY);
    const onTile = () => page.evaluate(() => !!document.activeElement.closest('li[data-file-anchor]'));
    const key = browserName === 'webkit' ? 'Alt+Shift+Tab' : 'Shift+Tab';
    let moved = false;
    for (let i = 0; i < 12 && !moved; i++) {
      await page.keyboard.press(key);
      if (!(await onTile())) break;
      moved = await page.evaluate((y) => window.scrollY < y - 20, start);
    }
    // WebKit on Windows: neither Tab nor Option+Tab reaches links (Safari's default), the tiles'
    // only controls; the focus goes to the bar (topbar.test.js covers the correction there)
    test.skip(!(await onTile()) && browserName === 'webkit', "WebKit's Tab and Option+Tab skip links here");
    expect(moved, `${key} reached a file above the window`).toBe(true);
    await expect(nav).toBeInViewport({ ratio: 1 });
    await expect.poll(() => page.evaluate((sel) => {
      const bar = document.querySelector(sel).getBoundingClientRect();
      return Math.round(document.activeElement.getBoundingClientRect().top - bar.bottom);
    }, NAV), "the focused file's link starts below the bar").toBeGreaterThanOrEqual(0);
    await context.close();
  }

  test('Shift+Tab onto a file above the window: it lands below the bar, not under it (WCAG 2.4.11)',
    async ({ contextAs, fixture, browserName }) => {
      await shiftTabOntoFileAbove(contextAs, fixture, browserName);
    });

  test("without the root's scroll-padding-top, topbar.js alone keeps a focused file below the bar (Chromium)",
    async ({ contextAs, fixture, browserName }) => {
      // Chromium centres an element that the focus moves to out of view, so Shift+Tab onto a file
      // above the window proves nothing here: instead a file in view but under the shown bar's
      // place, which the browser doesn't scroll to, so that only topbar.js can move it below
      test.skip(browserName !== 'chromium', 'one engine is enough for the script alone');
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 400 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      await page.addStyleTag({ content: 'html{scroll-padding-top:0 !important}' });
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).scrollPaddingTop)).toBe('0px');
      const nav = page.locator(NAV);
      const links = page.locator('li[data-file-anchor] a[href*="/large/"]');
      const [a, b] = [links.nth(4), links.nth(5)];
      // the page scrolled so that file a's top is 5 px below the window's top, coming from below
      // (a scroll up of 300 px: the bar shown, over it)
      const target = await a.evaluate((el) => window.scrollY + el.getBoundingClientRect().top - 5);
      await page.evaluate((t) => window.scrollTo({ top: t + 300, behavior: 'instant' }), target);
      await expect(nav).not.toBeInViewport();
      await page.evaluate((t) => window.scrollTo({ top: t, behavior: 'instant' }), target);
      await expect(nav).toBeInViewport({ ratio: 1 });
      expect(Math.round((await a.boundingBox()).y), 'file a under the bar').toBeLessThan((await nav.boundingBox()).height);
      // the focus on the next file by script (no keyboard), then Shift+Tab onto file a
      await b.evaluate((el) => el.focus());
      await page.keyboard.press('Shift+Tab');
      await expect(a).toBeFocused();
      await expect.poll(async () => {
        const [n, f] = [await nav.boundingBox(), await a.boundingBox()];
        return Math.round(f.y - (n.y + n.height));
      }, "the focused file's link starts below the bar").toBeGreaterThanOrEqual(0);
      await context.close();
    });

  test('--wlx-topbar-height follows the bar from 1366 to 390 px wide', async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 1366, height: 800 } });
    const page = await context.newPage();
    await page.goto(byRate(fixture));
    await page.waitForLoadState('load');
    const read = () => page.evaluate(() => ({
      prop: document.documentElement.style.getPropertyValue('--wlx-topbar-height'),
      bar: document.querySelector('body > nav.navbar').offsetHeight,
    }));
    const wide = await read();
    expect(wide.prop).toBe(`${wide.bar}px`);
    await page.setViewportSize({ width: 390, height: 800 });
    await expect.poll(async () => (await read()).prop).not.toBe(wide.prop);
    const narrow = await read();
    expect(narrow.prop).toBe(`${narrow.bar}px`);
    expect(narrow.bar, 'the bar wraps, taller').toBeGreaterThan(wide.bar);
    await context.close();
  });

  test('a mainAdmin page (Users) has it too', { tag: '@R-03' }, async ({ contextAs }) => {
    const context = await contextAs('root', { viewport: { width: 1366, height: 600 } });
    const page = await context.newPage();
    await page.goto('/admin/users');
    await page.waitForLoadState('load');
    const nav = page.locator(NAV);
    await expect(nav).toBeInViewport({ ratio: 1 });
    await wheel(page, 800);
    await expect(nav).not.toBeInViewport();
    await wheel(page, -150);
    await expect(nav).toBeInViewport({ ratio: 1 });
    expect((await nav.boundingBox()).y).toBeCloseTo(0, 0);
    await context.close();
  });

  test('without animation for those who prefer reduced motion', async ({ contextAs, fixture }) => {
    for (const [reducedMotion, duration] of [['no-preference', '0.2s'], ['reduce', '0s']]) {
      const context = await contextAs('organizer', { reducedMotion });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      expect(await page.locator(NAV).evaluate((n) => getComputedStyle(n).transitionDuration)).toBe(duration);
      await context.close();
    }
  });

  test('Back to gallery from a lower file: the file below the shown bar', { tag: '@O-02' },
    async ({ contextAs, fixture }) => {
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 500 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      const tile = page.locator('li[data-file-anchor]').last();
      await expect(tile).not.toBeInViewport();
      const pageId = await tile.getAttribute('data-file-anchor');
      const largeHref = await tile.locator('a[href*="/large/"]').first().getAttribute('href');
      await page.goto(largeHref);
      await Promise.all([page.waitForURL(/#file-\d+$/), page.locator('a[data-nav="gallery"]').click()]);
      await page.waitForLoadState('load');
      const back = page.locator(`li[data-file-anchor="${pageId}"]`);
      await expect(back).toBeInViewport({ ratio: 1 });
      expect(await page.evaluate(() => window.scrollY), 'scrolled down to it').toBeGreaterThan(0);
      // the bar stays shown after the page's own scroll, the file below it
      const nav = page.locator(NAV);
      await expect(nav).toBeInViewport({ ratio: 1 });
      await expect.poll(async () => {
        const [n, f] = [await nav.boundingBox(), await back.boundingBox()];
        return f.y - (n.y + n.height);
      }, 'the file starts below the bar').toBeGreaterThanOrEqual(0);
      // the user's own scroll down hides it again (up first: the last file is at the page's end)
      await wheel(page, -300);
      await expect(nav).toBeInViewport({ ratio: 1 });
      await wheel(page, 300);
      await expect(nav).not.toBeInViewport();
      await context.close();
    });
});

test('the large view has no reappearing top bar', async ({ contextAs, fixture }) => {
  const context = await contextAs('juror');
  const page = await context.newPage();
  const juror = fixture.accounts.juror.id;
  const html = await (await page.request.get(galleryPath(fixture.roundBinaryId, juror))).text();
  const m = html.match(/data-largeUrl="([^"]*\/large\/[^"]*)"/);
  expect(m, 'the gallery lists a file').not.toBeNull();
  await page.goto(m[1].replace(/&amp;/g, '&'));
  await expect(page.locator('a[data-nav="gallery"]')).toBeVisible();
  await expect(page.locator('body.wlx-topbar-reappears')).toHaveCount(0);
  await expect(page.locator('script[src*="topbar.js"]')).toHaveCount(0);
  await context.close();
});

test('on a phone: hides and comes back, its menus inside the window', async ({ contextAs, fixture, browserName }) => {
  // Firefox has no mobile emulation (isMobile): a narrow window there
  const phone = browserName === 'firefox'
    ? { viewport: { width: 390, height: 844 } }
    : { viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true };
  const context = await contextAs('juror', phone);
  const page = await context.newPage();
  await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
  await page.waitForLoadState('load');
  const nav = page.locator(NAV);
  await expect(nav).toBeInViewport({ ratio: 1 });
  const height = (await nav.boundingBox()).height;
  expect(height, 'the bar leaves most of the screen to the gallery').toBeLessThan(844 / 2);

  await scrollBy(page, 1200);
  await expect(nav).not.toBeInViewport();
  await scrollBy(page, -100);
  await expect(nav).toBeInViewport({ ratio: 1 });

  // a menu of the bar, opened while it is shown mid-page: inside the window
  const votes = page.locator('#votes-menu');
  await votes.locator('> button').click();
  const list = votes.locator('.dropdown-menu');
  await expect(list).toBeVisible();
  const box = await list.boundingBox();
  expect(box.y + box.height).toBeLessThanOrEqual(844);
  expect(box.x).toBeGreaterThanOrEqual(0);
  await scrollBy(page, 400);
  await expect(nav).toBeInViewport({ ratio: 1 });
  await expect(list).toBeVisible();
  await page.keyboard.press('Escape');

  // every menu of the bar opens inside the window's width, without a horizontal scrollbar
  const menus = nav.locator('li.dropdown');
  const count = await menus.count();
  expect(count).toBeGreaterThan(2);
  for (let i = 0; i < count; i++) {
    const menu = menus.nth(i);
    const toggle = menu.locator('> button, > a.dropdown-toggle').first();
    if (!(await toggle.isVisible())) continue;
    await toggle.click();
    const items = menu.locator('> .dropdown-menu');
    await expect(items).toBeVisible();
    const b = await items.boundingBox();
    const id = (await menu.getAttribute('id')) || `menu ${i}`;
    expect(b.x, `${id} from the window's left edge`).toBeGreaterThanOrEqual(0);
    // a menu near the bar's right edge is moved inside the window (menu-fit.js; it ran past it,
    // the Comments menu to 420 px in Chromium, the region menu to 399 in WebKit;
    // phone-menus.spec.js checks every page's menus)
    await expect.poll(async () => {
      const box = await items.boundingBox();
      return box.x + box.width;
    }, { message: `${id} before the window's right edge` }).toBeLessThanOrEqual(390);
    expect(b.y + b.height, `${id} above the window's bottom`).toBeLessThanOrEqual(844);
    expect(await items.evaluate((l) => l.scrollWidth <= l.clientWidth), `${id} not scrolled sideways`).toBe(true);
    await page.keyboard.press('Escape');
    await expect(items).toBeHidden();
  }
  await context.close();
});
