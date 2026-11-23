// The top bar's menus on a phone (the maintainer's decision of 2026-11-20; menu-fit.js, main.css):
// at 390 and 412 px every menu of the bar, opened, stays inside the window, and the page does not
// scroll sideways. Before, the Comments and Region menus opened past the right edge on the
// galleries, file lists, comments report and contest files (the organisers' region menu by 153 px).
// Chromium only: the fix is layout and script alone, the same in every engine; topbar.spec.js
// checks the juror gallery's menus in all three.
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';
import { readServer } from '../lib/server.js';

const HEIGHT = 844;
const WIDTHS = [390, 412];

/** The pages, by role: the galleries and lists (Comments and Region menus), the contest files,
  * the Rounds, statistics, Contests and users pages. */
function pages(fixture) {
  const juror = fixture.accounts.juror.id;
  const { roundBinaryId: binary, roundRatingId: rated, contestId } = fixture;
  const roundsContest = readServer().roundsContestId;
  return {
    juror: [
      galleryPath(binary, juror),
      galleryPath(rated, juror),
      `/byrate/round/${rated}/user/${juror}/page/1`,
      `/comments/round/${rated}/user/${juror}/page/1`,
      `/filelist/round/${rated}/user/${juror}/page/1`,
    ],
    admin: [
      galleryPath(binary, 0),
      `/byrate/round/${rated}/user/0/page/1`,
      `/comments/round/${binary}/user/0/page/1`,
      `/contest/${contestId}/files`,
      `/admin/rounds?contestId=${contestId}`,
      `/roundstat/${binary}`,
      `/admin/users?contestId=${contestId}`,
    ],
    organizer: [galleryPath(rated, 0), `/roundstat/${rated}`],
    root: ['/contests', '/admin/allUsers', ...(roundsContest ? [`/roundstat?contestId=${roundsContest}`] : [])],
  };
}

/** Opens each menu of the page's top bar in turn: each inside the window, no sideways scroll. */
async function checkMenus(page, url, width) {
  await page.goto(url);
  await page.waitForLoadState('load');
  // the page's own width with every menu closed (an organisers' gallery's tiles end 2 px past a
  // 390 px window, not a menu's doing): an open menu adds nothing to it
  const own = Math.max(width, await page.evaluate(() => document.documentElement.scrollWidth));
  const toggles = page.locator('nav.navbar [data-bs-toggle="dropdown"]');
  const count = await toggles.count();
  let opened = 0;
  for (let i = 0; i < count; i++) {
    const toggle = toggles.nth(i);
    if (!(await toggle.isVisible())) continue;
    const item = toggle.locator('xpath=..');
    const name = `${url} ${(await item.getAttribute('id')) || (await toggle.innerText()).trim() || `menu ${i}`} at ${width} px`;
    await toggle.click();
    const menu = item.locator('> .dropdown-menu');
    await expect(menu, name).toBeVisible();
    // the region menu fills itself once opened, and is fitted again
    if (await item.evaluate((el) => el.classList.contains('region-menu'))) {
      await expect.poll(() => menu.locator('a.dropdown-item').count(), { message: name }).toBeGreaterThan(2);
    }
    await expect.poll(async () => {
      const b = await menu.boundingBox();
      return b.x >= 0 && b.x + b.width <= width;
    }, { message: `${name}: inside the window` }).toBe(true);
    const b = await menu.boundingBox();
    expect(b.x + b.width, `${name}: its right edge`).toBeLessThanOrEqual(width);
    expect(b.x, `${name}: its left edge`).toBeGreaterThanOrEqual(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), `${name}: no sideways scroll`)
      .toBeLessThanOrEqual(own);
    expect(await page.evaluate(() => window.innerWidth), `${name}: the layout not widened`).toBeLessThanOrEqual(own);
    expect(await menu.evaluate((l) => l.scrollWidth <= l.clientWidth), `${name}: not scrolled sideways inside`).toBe(true);
    opened++;
    await page.keyboard.press('Escape');
    await expect(menu, name).toBeHidden();
  }
  expect(opened, `${url}: menus opened`).toBeGreaterThan(0);
}

for (const role of ['juror', 'admin', 'organizer', 'root']) {
  test(`every top-bar menu stays inside a phone's window: ${role}'s pages`, async ({ contextAs, fixture, browserName }) => {
    test.skip(browserName !== 'chromium', 'layout and script alone: one engine is enough (topbar.spec.js runs all three)');
    test.setTimeout(240_000);
    for (const width of WIDTHS) {
      const context = await contextAs(role, { viewport: { width, height: HEIGHT }, hasTouch: true, isMobile: true });
      const page = await context.newPage();
      for (const url of pages(fixture)[role]) await checkMenus(page, url, width);
      await context.close();
    }
  });
}

test('a desktop keeps its menus where Bootstrap puts them', async ({ contextAs, fixture, browserName }) => {
  test.skip(browserName !== 'chromium', 'one engine is enough');
  const context = await contextAs('juror', { viewport: { width: 1366, height: 900 } });
  const page = await context.newPage();
  await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
  await page.waitForLoadState('load');
  for (const id of ['#comments-menu', '.region-menu']) {
    const item = page.locator(`nav.navbar ${id}`).first();
    await item.locator('> [data-bs-toggle="dropdown"]').click();
    const menu = item.locator('> .dropdown-menu');
    await expect(menu).toBeVisible();
    // under its toggle, from its left edge, not moved
    const [t, m] = [await item.boundingBox(), await menu.boundingBox()];
    expect(Math.abs(m.x - t.x), `${id} starts at its toggle`).toBeLessThanOrEqual(1);
    expect(await menu.evaluate((el) => el.style.transform)).toBe('');
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
  }
  await context.close();
});
