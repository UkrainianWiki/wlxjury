// The sticky top bar (the maintainer's decision of 2026-11-23; topbar.js, main.css
// wlx-topbar-sticky): on the galleries and lists the navbar stays at the window's top while the
// user scrolls, from 576 px wide, and scrolls away with the page on a phone; Back to gallery and
// the keyboard focus land below it; its menus fit in the window; the large view has none.
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

const NAV = 'body.wlx-topbar-sticky > nav.navbar';

/** The organizers' by-rate gallery of all the jurors: a long page of tiles. */
const byRate = (fixture) => `/byrate/round/${fixture.roundRatingId}/user/0/page/1`;

/** Scrolls the page by script (the scroll events are the same; a phone has no wheel). */
async function scrollBy(page, dy) {
  await page.evaluate((d) => window.scrollBy({ top: d, behavior: 'instant' }), dy);
}

test.describe('the sticky top bar on a desktop', () => {
  test('stays at the window top while scrolling down and up', { tag: '@O-02' },
    async ({ contextAs, fixture }) => {
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 600 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      const nav = page.locator(NAV);
      await expect(nav).toHaveCount(1);
      expect(await nav.evaluate((n) => getComputedStyle(n).position)).toBe('sticky');
      for (const dy of [800, 600, -300]) {
        await scrollBy(page, dy);
        await expect(nav).toBeInViewport({ ratio: 1 });
        expect((await nav.boundingBox()).y).toBeCloseTo(0, 0);
      }
      expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(100);
      await context.close();
    });

  test('an open menu stays above the content; its list fits in the window and scrolls inside itself',
    { tag: '@J-14' }, async ({ contextAs, fixture }) => {
      // a short window: the region menu's list taller than the room under the bar
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 500 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      await scrollBy(page, 800);
      const nav = page.locator(NAV);
      const region = page.locator('#regionmenu');
      await region.locator('> button').click();
      const list = region.locator('.dropdown-menu');
      await expect(list).toBeVisible();
      await expect(list.locator('.dropdown-item').nth(5)).toBeAttached();
      const box = await list.boundingBox();
      expect(box.y + box.height).toBeLessThanOrEqual(500);
      // above the tiles under it: the element at the list's centre is the list's own
      expect(await list.evaluate((l) => {
        const r = l.getBoundingClientRect();
        return l.contains(document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2));
      }), 'the menu is above the content').toBe(true);
      if (await list.evaluate((l) => l.scrollHeight > l.clientHeight)) {
        const last = list.locator('.dropdown-item').last();
        await last.scrollIntoViewIfNeeded();
        // whole inside the list's scrollport and the window, to within a pixel (WebKit's whole
        // pixels, Linux's font metrics)
        await expect.poll(() => last.evaluate((item) => {
          const l = item.closest('.dropdown-menu');
          const r = item.getBoundingClientRect();
          const top = l.getBoundingClientRect().top + l.clientTop;
          const room = { top: Math.max(top, 0), bottom: Math.min(top + l.clientHeight, window.innerHeight) };
          return Math.max(room.top - r.top, r.bottom - room.bottom, r.height > 0 ? 0 : Infinity);
        })).toBeLessThanOrEqual(1);
      }
      await expect(nav).toBeInViewport({ ratio: 1 });
      await page.keyboard.press('Escape');
      await expect(list).toBeHidden();
      await context.close();
    });

  test('a file focused by the keyboard is not under the bar (WCAG 2.4.11)', { tag: '@O-02' },
    async ({ contextAs, fixture, browserName }) => {
      test.skip(browserName !== 'chromium', 'one engine is enough: the root scroll-padding-top is CSS');
      const context = await contextAs('organizer', { viewport: { width: 1366, height: 400 } });
      const page = await context.newPage();
      await page.goto(byRate(fixture));
      await page.waitForLoadState('load');
      const nav = page.locator(NAV);
      const links = page.locator('li[data-file-anchor] a[href*="/large/"]');
      // from the page's end, Shift+Tab onto files above the window until the page scrolls up
      await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }));
      await links.last().focus();
      const start = await page.evaluate(() => window.scrollY);
      let moved = false;
      for (let i = 0; i < 12 && !moved; i++) {
        await page.keyboard.press('Shift+Tab');
        // two frames after the key: the scroll it caused has landed on the main thread
        moved = await page.evaluate((y) => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(
          () => done(window.scrollY < y - 20)))), start);
      }
      expect(moved, 'Shift+Tab reached a file above the window').toBe(true);
      await expect.poll(() => page.evaluate((sel) => {
        const bar = document.querySelector(sel).getBoundingClientRect();
        return Math.round(document.activeElement.getBoundingClientRect().top - bar.bottom);
      }, NAV), "the focused file's link starts below the bar").toBeGreaterThanOrEqual(0);
      await expect(nav).toBeInViewport({ ratio: 1 });
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

  test('sticky and scroll-padding-top switch together at 576 px', async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 576, height: 800 } });
    const page = await context.newPage();
    await page.goto(byRate(fixture));
    await page.waitForLoadState('load');
    const read = () => page.evaluate(() => ({
      position: getComputedStyle(document.querySelector('body > nav.navbar')).position,
      padding: getComputedStyle(document.documentElement).scrollPaddingTop,
      bar: document.querySelector('body > nav.navbar').offsetHeight,
    }));
    const at576 = await read();
    expect(at576.position).toBe('sticky');
    expect(at576.padding).toBe(`${at576.bar}px`);
    await page.setViewportSize({ width: 575, height: 800 });
    await expect.poll(async () => (await read()).position).not.toBe('sticky');
    // no padding below 576 px: the initial value, computed as auto (or 0px)
    expect((await read()).padding).toMatch(/^(auto|0px)$/);
    await context.close();
  });

  test('a mainAdmin page (Users) has it too', { tag: '@R-03' }, async ({ contextAs }) => {
    const context = await contextAs('root', { viewport: { width: 1366, height: 600 } });
    const page = await context.newPage();
    await page.goto('/admin/users');
    await page.waitForLoadState('load');
    const nav = page.locator(NAV);
    await expect(nav).toBeInViewport({ ratio: 1 });
    await scrollBy(page, 800);
    await expect(nav).toBeInViewport({ ratio: 1 });
    expect((await nav.boundingBox()).y).toBeCloseTo(0, 0);
    await context.close();
  });

  test('Back to gallery from a lower file: the file below the bar', { tag: '@O-02' },
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
      const nav = page.locator(NAV);
      await expect(nav).toBeInViewport({ ratio: 1 });
      await expect.poll(async () => {
        const [n, f] = [await nav.boundingBox(), await back.boundingBox()];
        return f.y - (n.y + n.height);
      }, 'the file starts below the bar').toBeGreaterThanOrEqual(0);
      await context.close();
    });
});

test('the large view has no sticky top bar', async ({ contextAs, fixture }) => {
  const context = await contextAs('juror');
  const page = await context.newPage();
  const juror = fixture.accounts.juror.id;
  const html = await (await page.request.get(galleryPath(fixture.roundBinaryId, juror))).text();
  const m = html.match(/data-largeUrl="([^"]*\/large\/[^"]*)"/);
  expect(m, 'the gallery lists a file').not.toBeNull();
  await page.goto(m[1].replace(/&amp;/g, '&'));
  await expect(page.locator('a[data-nav="gallery"]')).toBeVisible();
  await expect(page.locator('body.wlx-topbar-sticky')).toHaveCount(0);
  await expect(page.locator('script[src*="topbar.js"]')).toHaveCount(0);
  await context.close();
});

test('on a phone: the bar scrolls away with the page, its menus inside the window', async ({ contextAs, fixture, browserName }) => {
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
  expect(await nav.evaluate((n) => getComputedStyle(n).position), 'not sticky below 576 px').not.toBe('sticky');
  const height = (await nav.boundingBox()).height;
  expect(height, 'the bar leaves most of the screen to the gallery').toBeLessThan(844 / 2);

  await scrollBy(page, 1200);
  await expect(nav).not.toBeInViewport();
  await scrollBy(page, -1200);
  await expect(nav).toBeInViewport({ ratio: 1 });

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
