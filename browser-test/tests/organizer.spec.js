// The organizers' pages: a round's results for the organizer role, and the round form for the
// contest's admin, opened, changed and left with Cancel (Montage's edit-then-cancel flows), so no
// round is saved and no distribution job starts.
import { test, expect } from '../lib/fixtures.js';

test.describe('an organizer', () => {
  test.use({ role: 'organizer' });

  test('opens a round\'s statistics, with a row per juror', { tag: '@O-01' }, async ({ page, fixture }) => {
    await page.goto(`/roundstat/${fixture.roundBinaryId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Statistics' })).toBeAttached();
    await expect(page.getByRole('columnheader', { name: 'Selected' })).toBeVisible();
    await expect(page.getByRole('cell', { name: fixture.accounts.juror.login, exact: true })).toBeVisible();
    // the rounds' links: the current one marked
    await expect(page.getByRole('link', { name: /Yes\/No Round/ })).toHaveAttribute('aria-current', 'page');
  });

  test('opens the rated round\'s files by rate, of all the jurors', { tag: '@O-02' }, async ({ page, fixture }) => {
    await page.goto(`/byrate/round/${fixture.roundRatingId}/user/0/page/1`);
    await expect(page.getByRole('heading', { level: 1, name: 'By rate' })).toBeAttached();
    // a file per row, each its image linking to its large view; read-only: no vote buttons
    const files = page.getByRole('link').filter({ has: page.getByRole('img') })
      .and(page.locator(`[href*="/large/round/${fixture.roundRatingId}/user/0/"]`));
    await expect(files.first().getByRole('img')).toBeVisible();
    expect(await files.count()).toBeGreaterThan(1);
    await expect(page.getByRole('button', { name: /^Select image/ })).toHaveCount(0);
  });
});

test.describe('an admin', () => {
  test.use({ role: 'admin' });

  test('opens a round\'s form from the rounds list, with its jurors\' table', { tag: '@A-02' }, async ({ page, fixture }) => {
    await page.goto(`/admin/rounds?contestId=${fixture.contestId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Contest');
    await page.getByRole('row', { name: /Yes\/No Round/ }).getByRole('link', { name: '1', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit round' })).toBeVisible();
    await expect(page.getByLabel('Round name (optional)')).toHaveValue('Yes/No Round');

    // the jurors' panel loads its table when opened (round-form.js)
    const table = page.waitForResponse((r) => new URL(r.url()).pathname === `/roundstat/${fixture.roundBinaryId}/table`);
    await page.getByRole('button', { name: 'Jurors', exact: true }).click();
    expect((await table).status()).toBe(200);
    await expect(page.getByRole('cell', { name: fixture.accounts.juror.login, exact: true })).toBeVisible();
  });

  test('changes a round\'s name, cancels, and the round keeps its name', { tag: '@A-02' }, async ({ page, fixture }) => {
    const form = `/admin/rounds/edit?id=${fixture.roundBinaryId}&contestId=${fixture.contestId}`;
    await page.goto(form);
    const name = page.getByLabel('Round name (optional)');
    await expect(name).toHaveValue('Yes/No Round');
    await name.fill('Renamed and cancelled');
    const posts = [];
    page.on('request', (r) => { if (r.method() === 'POST') posts.push(r.url()); });
    await page.getByRole('link', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/\/admin\/rounds/);
    expect(posts, 'Cancel saves nothing').toEqual([]);
    await page.goto(form);
    await expect(page.getByLabel('Round name (optional)')).toHaveValue('Yes/No Round');
  });

  test('opens the new round form, whose fields follow the choices', { tag: '@A-01' }, async ({ page, fixture }) => {
    await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Create round' })).toBeVisible();
    await expect(page.getByLabel('Round name (optional)')).toHaveValue('');
    await expect(page.getByLabel('Rating type')).toBeVisible();
  });

  // At a phone's width the users and rounds tables scroll inside their .table-responsive box
  // (position-relative, so their visually hidden labels don't widen the page): the page itself
  // has no horizontal scroll. The contests page's own check is in root's suggestion test.
  test('keeps the users and rounds pages within a phone\'s width, their tables scrolling in their boxes', { tag: ['@A-07', '@A-03'] },
    async ({ page, fixture }) => {
      await page.setViewportSize({ width: 390, height: 800 });
      for (const path of [`/admin/users?contestId=${fixture.contestId}`, `/admin/rounds?contestId=${fixture.contestId}`]) {
        await page.goto(path);
        await expect(page.locator('.table-responsive table').first()).toBeVisible();
        const widths = await page.evaluate(() => ({
          page: document.documentElement.scrollWidth, window: document.documentElement.clientWidth }));
        expect(widths.page, `${path}: no horizontal scroll of the page`).toBeLessThanOrEqual(widths.window);
      }
    });
});

test.describe('root', () => {
  test.use({ role: 'root' });

  test('opens the contests page', { tag: '@R-01' }, async ({ page }) => {
    await page.goto('/contests');
    await expect(page.getByRole('heading', { level: 1, name: 'Contests' })).toBeAttached();
    await expect(page.getByRole('cell', { name: /Wiki Loves Monuments/ }).first()).toBeVisible();
  });

  // The add row's source field is in the table's .table-responsive box, which would clip its
  // suggestions: they hang from the body (data-category-suggest-dropdown-parent), under the
  // field, moving with the page, in the page's colours. Commons' answer comes from this test's
  // route (a page's route goes before the guard's): nothing reaches Commons.
  test('suggests a new contest\'s source under its field, outside the table\'s box, in the dark theme', { tag: '@R-01' },
    async ({ page, baseURL }) => {
      const titles = Array.from({ length: 10 }, (_, i) => `Category:Wiki Loves Earth ${2016 + i}`);
      await page.route('https://commons.wikimedia.org/w/api.php**', (route) => route.fulfill({
        status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ batchcomplete: true, query: { prefixsearch: titles.map((title, i) => ({ ns: 14, title, pageid: i + 1 })) } }),
      }));
      await page.context().addCookies([{ name: 'theme', value: 'dark', url: baseURL }]);
      await page.goto('/contests');
      const control = page.locator('#images-ts-control');
      await control.fill('Category:Wiki');
      const dropdown = page.locator('body > .ts-dropdown');
      await expect(dropdown).toBeVisible();
      await expect(dropdown.locator('.option')).toHaveCount(titles.length);
      expect(await page.locator('.table-responsive .ts-dropdown').count()).toBe(0);
      // under the field, at its left edge, and still there after the page scrolls (null if the
      // list or its field has no box)
      const place = async () => {
        const [c, d] = [await control.locator('xpath=ancestor::div[contains(@class,"ts-control")][1]').boundingBox(),
          await dropdown.boundingBox()];
        return c && d ? { below: d.y - (c.y + c.height), left: d.x - c.x } : null;
      };
      const before = await place();
      expect(before, 'the open list and its field have boxes').not.toBeNull();
      expect(before.below).toBeGreaterThanOrEqual(0);
      expect(before.below).toBeLessThan(12);
      expect(Math.abs(before.left)).toBeLessThan(2);
      await page.evaluate(() => window.scrollBy(0, 40));
      expect(await place()).toEqual(before);
      // the page's colours (data-bs-theme on <html>), not the light defaults
      const colours = await page.evaluate(() => {
        const d = getComputedStyle(document.querySelector('body > .ts-dropdown'));
        const b = getComputedStyle(document.body);
        return { dropdown: [d.backgroundColor, d.color], body: [b.backgroundColor, b.color] };
      });
      expect(colours.dropdown).toEqual(colours.body);
      // the keys still choose a suggestion
      await control.press('ArrowDown');
      await control.press('Enter');
      await expect(page.locator('#images')).toHaveValue(titles[0]);

      // a phone's width: the table scrolls sideways in its box (the page doesn't); when the box
      // scrolls, an open list follows the field while the field is wholly in the box's view and
      // closes once it isn't (category-suggest.js; no window event then), so it never widens
      // the page, whichever way the box scrolls
      await page.setViewportSize({ width: 390, height: 800 });
      await page.goto('/contests');
      // the page itself doesn't scroll sideways: the box (position-relative) contains the hidden
      // label and Tom Select's hidden input, and the open list fits under the field
      const pageFits = () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
      const box = page.locator('.table-responsive').filter({ has: page.locator('#images') });
      // the box, the field (Tom Select's wrapper, which category-suggest.js measures), its
      // control and the list (null when closed), read in one turn of the page's script, so that
      // nothing moves or closes between the reads
      const boxState = () => box.evaluate((el) => {
        const rect = (e) => {
          const b = e.getBoundingClientRect();
          return { left: b.left, right: b.right, top: b.top, bottom: b.bottom };
        };
        const wrapper = el.querySelector('#images-ts-control').closest('.ts-wrapper');
        const list = document.querySelector('body > .ts-dropdown');
        const open = !!list && getComputedStyle(list).display !== 'none' && list.getBoundingClientRect().height > 0;
        return { visible: rect(el), field: rect(wrapper), control: rect(wrapper.querySelector('.ts-control')),
          list: open ? rect(list) : null };
      });
      // category-suggest.js's rule: the whole field in the box's view (a pixel's slack), or the
      // left edge of a field wider than the box
      const fieldInView = ({ visible, field }) => {
        const leftIn = field.left >= visible.left - 1 && field.left <= visible.right;
        if (field.right - field.left > visible.right - visible.left) return leftIn;
        return field.left >= visible.left - 1 && field.right <= visible.right + 1;
      };
      // what the list should do after a box scroll, and what it does
      const followState = (s) => {
        if (!fieldInView(s)) return s.list === null ? 'closed' : 'open, its field out of view';
        if (s.list === null) return 'closed, its field in view';
        return Math.abs(s.list.left - s.control.left) < 3 ? 'under the field' : 'not under the field';
      };
      for (const by of [60, -60, 200, -200]) {
        await control.fill('Category:Wiki'); // scrolls the box to the field, and opens the list
        await expect(dropdown).toBeVisible();
        await expect(dropdown.locator('.option')).toHaveCount(titles.length);
        expect(await pageFits(), 'open, before the box scrolls').toBe(true);
        const moved = await box.evaluate((el, delta) => {
          const start = el.scrollLeft;
          el.scrollLeft = start + delta;
          return el.scrollLeft - start;
        }, by);
        if (moved === 0) continue; // already at that end of the box
        // under the field at its left edge while the field is in view, closed once it isn't (the
        // box's scroll event, and so the list's move, can come a frame after the scroll: polled)
        await expect.poll(async () => {
          const state = followState(await boxState());
          return state === 'closed' || state === 'under the field' ? 'as expected' : state;
        }, `after the box scrolled by ${moved}`).toBe('as expected');
        const after = await boxState();
        if (after.list) expect(after.list.top - after.control.bottom).toBeGreaterThanOrEqual(0);
        expect(await pageFits(), `after the box scrolled by ${moved}`).toBe(true);
        await control.press('Escape');
      }
    });
});
