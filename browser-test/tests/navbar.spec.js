// The navbar's menus by keyboard and the organisers' bar on one line (the navigation proposal v3,
// §2.4 and §2.5). The menus open from real buttons (Space opens them, as Enter does), the arrow
// keys move through their items, Esc closes them and gives the focus back to the button. The
// organisers' bar of one juror's votes, on all files (the widest Votes button), keeps its pager on
// the line of its first menu at 1280 px and at 1700 px in Ukrainian (the longest), with a long
// juror name.
//
// The fixture has no comments, so the Comments menu's way between the gallery, the files with
// comments and the comments list (comments=1, the Votes choice kept) is the Scala specs'
// (NavbarMenusSpec, JurorCommentsReportSpec); here its greyed-out items are skipped by the keys.
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

/** The menu's toggle, focused, opened by Space; its menu shown. */
async function openBySpace(page, menu) {
  const button = menu.locator('> button');
  await button.focus();
  await page.keyboard.press('Space');
  await expect(button).toHaveAttribute('aria-expanded', 'true');
  await expect(menu.locator('.dropdown-menu')).toBeVisible();
  return button;
}

/** ArrowDown until the focused item's text starts with `name` (at most `max` presses). */
async function arrowTo(page, name, max = 6) {
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('ArrowDown');
    const text = await page.evaluate(() => document.activeElement && document.activeElement.textContent.trim());
    if (text && text.startsWith(name)) return;
  }
  throw new Error(`no item "${name}" reached by ArrowDown`);
}

test.describe('a juror\'s navbar menus by keyboard', () => {
  test.use({ role: 'juror' });

  test('Votes opens by Space, the arrows reach Unrated, Enter opens it; Esc gives the focus back', { tag: '@J-12' }, async ({ page, fixture }) => {
    await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
    const votes = page.locator('#votes-menu');
    // no visible word: the current choice and its count, "Votes" its accessible name's start
    await expect(votes.locator('> button')).toHaveAccessibleName(/^Votes: All \(\d+\) · \d+ unrated$/);

    let button = await openBySpace(page, votes);
    await page.keyboard.press('Escape');
    await expect(votes.locator('.dropdown-menu')).toBeHidden();
    await expect(button).toBeFocused();

    await openBySpace(page, votes);
    await arrowTo(page, 'Unrated');
    await Promise.all([page.waitForURL(/filter=unrated/), page.keyboard.press('Enter')]);
    button = votes.locator('> button');
    await expect(button).toHaveAccessibleName(/^Votes: Unrated \(\d+\)$/);
    await expect(votes.getByRole('link', { name: /^Unrated/, includeHidden: true })).toHaveAttribute('aria-current', 'page');
  });

  test('the order and Comments menus open by Space; greyed-out items are skipped', { tag: '@J-12' }, async ({ page, fixture }) => {
    await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
    // the order menu (dropDownSelection, a button now)
    const order = page.locator('.wlx-nav-order li.dropdown').first();
    await openBySpace(page, order);
    await page.keyboard.press('Escape');
    await expect(order.locator('> button')).toBeFocused();

    const comments = page.locator('#comments-menu');
    await openBySpace(page, comments);
    const disabled = comments.locator('.dropdown-item.disabled');
    // the fixture's juror has no files with comments: two items greyed out, not links
    await expect(disabled).toHaveCount(2);
    await expect(disabled.first()).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    expect(await page.evaluate(() => document.activeElement.textContent.trim())).toBe('All files');
    await page.keyboard.press('Escape');
    await expect(comments.locator('> button')).toBeFocused();
  });
});

// The widest bar the fixture can't show by itself, put in before measuring: a long juror name
// (the fixture's are "Juror N"; main.css wlx-truncate cuts it), four-digit counts on the Votes
// button (All and its unrated) and a count on the Comments button (the fixture has no comments).
// A classic 17 px scrollbar, which the headless browser's overlay scrollbars don't take: the
// window keeps the nominal width (the media queries see it, as a real browser's do) and the page
// 17 px less, by a right margin on the root element.
const LONG_NAME = 'Олександра Костянтинопольська';
const SCROLLBAR = 17;

async function widest(page) {
  await page.locator('#juror-menu .wlx-truncate').evaluate((span, name) => { span.textContent = name; }, LONG_NAME);
  await page.locator('#votes-menu .wlx-votes-count').evaluate((span) => { span.textContent = ' (1234)'; });
  await page.locator('#votes-menu .wlx-votes-unrated').evaluate((span) => { span.textContent = ' · 1234 без оцінки'; });
  await page.locator('#comments-menu > button').evaluate((button) => {
    let count = button.querySelector('.wlx-comments-count');
    if (!count) {
      count = document.createElement('span');
      count.className = 'wlx-comments-count';
      button.appendChild(count);
    }
    count.textContent = ' (31)';
  });
}

// an admin's bar has the most pages in its Pages menu, root's not counted (no wider button)
for (const [role, width] of [['organizer', 1280], ['organizer', 1700], ['admin', 1280], ['admin', 1700]]) {
  test(`the ${role}'s bar of one juror's votes stays on one line at ${width} px in Ukrainian`, { tag: '@O-03' }, async ({ contextAs, fixture }, testInfo) => {
    const context = await contextAs(role, { viewport: { width, height: 900 }, locale: 'uk-UA' });
    await context.addCookies([{ name: 'PLAY_LANG', value: 'uk', url: fixture.baseUrl }]);
    const page = await context.newPage();
    await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
    await expect(page.locator('html')).toHaveAttribute('lang', 'uk');
    // on all files, the widest Votes button: its unrated read by screen readers only below
    // 1700 px, shown from there; the count always
    await expect(page.locator('#votes-menu > button')).toHaveAccessibleName(/^Голоси: Всі \(\d+\) · \d+ без оцінки$/);
    await expect(page.locator('#pages-menu > button')).toHaveAccessibleName('Перейти до: Галерея');
    await widest(page);
    await page.evaluate((px) => { document.documentElement.style.marginRight = `${px}px`; }, SCROLLBAR);
    expect(await page.evaluate(() => Math.round(document.querySelector('nav.navbar').getBoundingClientRect().width))).toBe(width - SCROLLBAR);
    const unrated = await page.locator('#votes-menu .wlx-votes-unrated').boundingBox();
    if (width >= 1700) expect(unrated.width).toBeGreaterThan(20);
    else expect(unrated.width).toBeLessThanOrEqual(1);
    expect((await page.locator('#votes-menu .wlx-votes-count').boundingBox()).width).toBeGreaterThan(20);
    expect((await page.locator('#comments-menu .wlx-comments-count').boundingBox()).width).toBeGreaterThan(10);
    const first = await page.locator('.wlx-nav-groups > ul > li').first().boundingBox();
    const pager = await page.locator('#pagenav').boundingBox();
    const logged = await page.locator('nav .container-fluid > .navbar-nav').last().boundingBox();
    const middle = (box) => box.y + box.height / 2;
    const free = Math.round(logged.x - (pager.x + pager.width));
    testInfo.annotations.push({ type: 'width', description: `${role} ${width - SCROLLBAR} px: ${free} px free after the pager` });
    console.log(`[width] ${role} ${width - SCROLLBAR} px: ${free} px free after the pager`);
    expect(Math.abs(middle(pager) - middle(first)), `the pager on the first menu's line at ${width - SCROLLBAR} px`).toBeLessThan(4);
    await context.close();
  });
}
