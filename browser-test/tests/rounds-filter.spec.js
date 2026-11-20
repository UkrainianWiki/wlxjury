// The Rounds page's and the round statistics page's Status, Type and Order menus (the maintainer's
// decisions of 2026-11-18): a choice opens the page with it, kept in the URL and by the links between
// the two pages; the rounds' look, the same on both pages and in the galleries' round menu (the
// maintainer's decisions of 2026-11-19): each round's type icons before its name in a slot of one
// width (a yes/no round's check and cross, a rated round's star, a comments-only round's empty
// slot), coloured for an active round (3:1 or more), grey for an inactive one, whose text is grey
// too at 4.5:1 or more, its links still in the link colour, in light and dark mode; the icons and
// the names lined up whatever the names' lengths (roundsContestId: 30 rounds of every kind); the
// Type menu a legend of the icons; the galleries' round menu scrolling inside itself; an axe scan of
// each page with a menu open (Chromium).
//
// The fixture's two rounds are both active, and stopping one would change what the other tests see
// (they run in parallel); other specs add rounds to the contest as they run (review.spec.js: two per
// engine), so the round menus are checked on the fixture's two only, whatever else they list. The inactive look is checked by giving a row or an item the class the
// server gives an inactive round (wlx-round-inactive, RoundsFilterSpec asserts the server's side),
// so its computed colours are main.css's. ROUNDS_FILTER_SHOTS=<dir> (a run of this spec alone)
// stops the rating round for real, saves the screenshots and starts it again; ROUND_ICONS_SHOTS=<dir>
// saves the round icons contest's (Chromium).
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

const SHOTS = process.env.ROUNDS_FILTER_SHOTS;
const ICON_SHOTS = process.env.ROUND_ICONS_SHOTS;
// STAT_FILTER_SHOTS=<dir>: the statistics page's chip outside the filter, selected and focused (Chromium)
const SHOTS_STAT = process.env.STAT_FILTER_SHOTS;

/** WCAG contrast of two rgb(a) strings, the foreground drawn over the (opaque) background. */
function contrast(fg, bg) {
  const parse = (c) => c.match(/[\d.]+/g).map(Number);
  const [br, bgG, bb] = parse(bg);
  const [r, g, b, a = 1] = parse(fg);
  const lum = (rgb) => {
    const [x, y, z] = rgb.map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * x + 0.7152 * y + 0.0722 * z;
  };
  const over = [r, g, b].map((v, i) => v * a + [br, bgG, bb][i] * (1 - a));
  const [hi, lo] = [lum(over), lum([br, bgG, bb])].sort((p, q) => q - p);
  return (hi + 0.05) / (lo + 0.05);
}

/** The computed value of a CSS colour expression (a var()) in the page. */
async function colour(page, value) {
  return page.evaluate((val) => {
    const probe = document.createElement('span');
    probe.style.color = val;
    document.body.append(probe);
    const out = getComputedStyle(probe).color;
    probe.remove();
    return out;
  }, value);
}

/** An element's text colour and the first opaque background under it (itself or an ancestor). */
async function textOn(locator) {
  return locator.evaluate((el) => {
    const color = getComputedStyle(el).color;
    let e = el;
    while (e) {
      const bg = getComputedStyle(e).backgroundColor;
      if (bg && !/rgba\(.*,\s*0\)$/.test(bg) && bg !== 'transparent') return { color, bg };
      e = e.parentElement;
    }
    return { color, bg: 'rgb(255, 255, 255)' };
  });
}

/** The menus' items that read 0, but each menu's first (All) and its chosen item (the maintainer's
  * decision of 2026-11-19: UiSelectionDto.hideEmpty), as "menu: item". */
async function emptyItems(page, ids) {
  return page.evaluate((menuIds) => menuIds.flatMap((id) => [...document.querySelectorAll(`#${id} .dropdown-item`)]
    .filter((a, i) => i > 0 && !a.classList.contains('active'))
    .filter((a) => (a.querySelector('.wlx-menu-count')?.textContent ?? '').trim() === '0')
    .map((a) => `${id}: ${a.textContent.trim()}`)), ids);
}

/** An axe scan: no serious or critical finding but those triaged for the page (`known`: rule ->
  * its triage entry, as pages.spec.js's KNOWN). */
async function axe(page, testInfo, name, known = {}) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const serious = results.violations.filter((v) => ['serious', 'critical'].includes(v.impact) && !known[v.id])
    .map((v) => ({ rule: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }));
  await testInfo.attach(`axe ${name}`, { body: JSON.stringify(serious, null, 1), contentType: 'application/json' });
  expect(serious, `${name}: serious or critical accessibility findings`).toEqual([]);
}

/** A round's icon slot in `scope` (snippets/roundIcon): its type, whether grey, whether hidden from
  * screen readers, its icons' classes and its tooltip. */
async function slotOf(scope) {
  return scope.locator('.wlx-round-icon').evaluate((el) => ({
    type: el.dataset.type,
    inactive: el.classList.contains('wlx-round-icon-inactive'),
    hidden: el.getAttribute('aria-hidden'),
    icons: [...el.querySelectorAll('i')].map((i) => [...i.classList].find((c) => c.startsWith('wlx-round-'))),
    title: el.getAttribute('title'),
  }));
}

const COLOURED = { 'wlx-round-yes': 'var(--wlx-selected)', 'wlx-round-no': 'var(--wlx-rejected)', 'wlx-round-star': 'var(--wlx-star)' };
const GREY = { 'wlx-round-yes': 'var(--bs-secondary-color)', 'wlx-round-no': 'var(--bs-secondary-color)', 'wlx-round-star': 'var(--bs-secondary-color)' };

/** Each icon of the slot in `scope`: its colour, `expected`'s (var() expressions by icon class), at
  * 3:1 or more (a graphic, WCAG 1.4.11) on its own background and on each of `backgrounds`. */
async function checkIcons(page, mode, scope, expected, backgrounds, what) {
  const icons = scope.locator('.wlx-round-icon > i');
  expect(await icons.count(), `${mode}: ${what} has icons`).toBeGreaterThan(0);
  for (const icon of await icons.all()) {
    await expect(icon).toBeVisible();
    const cls = await icon.evaluate((el) => [...el.classList].find((c) => c.startsWith('wlx-round-')));
    const on = await textOn(icon);
    expect(on.color, `${mode}: ${what}'s ${cls}`).toBe(await colour(page, expected[cls]));
    for (const bg of [...backgrounds, on.bg]) {
      expect(contrast(on.color, bg), `${mode}: ${what}'s ${cls} on ${bg}`).toBeGreaterThanOrEqual(3);
    }
  }
}

/** Checks a round's look: the active round's (`active`) icons coloured, no badge; an inactive one's
  * (`inactive`, given the inactive look) text and icons grey, its text at 4.5:1 on each background
  * it may have (`backgrounds`), its links in the link colour. */
async function checkLook(page, mode, { active, inactive, backgrounds, link }) {
  await expect(active.locator('.badge')).toHaveCount(0);
  await checkIcons(page, mode, active, COLOURED, backgrounds, 'the active round');
  const grey = await colour(page, 'var(--bs-secondary-color)');
  // once settled: the inactive class was just given, and a pager link's colour changes with a
  // transition (Bootstrap's .page-link, 0.15 s), so a read at once may find it a step short
  await expect(inactive, `${mode}: the inactive round's text in the secondary grey`).toHaveCSS('color', grey);
  const text = await inactive.evaluate((el) => getComputedStyle(el).color);
  for (const bg of backgrounds) {
    expect(contrast(text, bg), `${mode}: the inactive round's text on ${bg}`).toBeGreaterThanOrEqual(4.5);
  }
  // its slot given the class the server gives an inactive round's (RoundsFilterSpec)
  const slot = inactive.locator('.wlx-round-icon');
  await slot.evaluate((el) => el.classList.add('wlx-round-icon-inactive'));
  await checkIcons(page, mode, inactive, GREY, backgrounds, 'the inactive round');
  await slot.evaluate((el) => el.classList.remove('wlx-round-icon-inactive'));
  expect(await inactive.evaluate((el) => getComputedStyle(el).textDecorationLine), 'no strikethrough').not.toContain('line-through');
  if (link) {
    const linkColour = await colour(page, 'var(--bs-link-color)');
    const a = inactive.locator('a.round-stat-link');
    expect(await a.evaluate((el) => getComputedStyle(el).color), `${mode}: an inactive row's link keeps the link colour`).toBe(linkColour);
    for (const bg of backgrounds) {
      expect(contrast(linkColour, bg), `${mode}: the link on ${bg}`).toBeGreaterThanOrEqual(4.5);
    }
  }
}

/** An inactive round's grey icons on the selected highlight (the current item: the pager's
  * page-item.active, the round menu's dropdown-item.active), 3:1 or more: `current` given the
  * inactive slot's class for the check. */
async function checkGreyOnCurrent(page, mode, current, what) {
  const slot = current.locator('.wlx-round-icon');
  const selected = await colour(page, 'var(--wlx-current-bg)');
  expect((await textOn(slot)).bg, `${mode}: ${what} on the selected highlight`).toBe(selected);
  await slot.evaluate((el) => el.classList.add('wlx-round-icon-inactive'));
  await checkIcons(page, mode, current, GREY, [selected], `${what}, inactive`);
  await slot.evaluate((el) => el.classList.remove('wlx-round-icon-inactive'));
}

/** The left edges of each entry's icon slot, its first icon and its name (the first thing after the
  * slot: a link, or the text node), and the slot's width, in `entries` (the rows' name cells, the
  * menu items). */
async function edges(entries) {
  return entries.evaluateAll((els) => els.map((el) => {
    const slot = el.querySelector('.wlx-round-icon');
    let name = slot.nextSibling;
    while (name && name.nodeType === Node.TEXT_NODE && !name.textContent.trim()) name = name.nextSibling;
    let x;
    if (name.nodeType === Node.TEXT_NODE) {
      const r = document.createRange();
      r.selectNodeContents(name);
      x = r.getClientRects()[0].x;
    } else x = name.getBoundingClientRect().x;
    const icon = slot.querySelector('i');
    const box = slot.getBoundingClientRect();
    return { slot: box.x, width: box.width, icon: icon ? icon.getBoundingClientRect().x : null, name: x };
  }));
}

/** All entries' slots, first icons and names start at one x each (within half a pixel), the slots
  * of one width. */
function expectLinedUp(list, what) {
  expect(list.length, `${what}: entries`).toBeGreaterThan(1);
  for (const key of ['slot', 'width', 'icon', 'name']) {
    const xs = list.map((e) => e[key]).filter((x) => x !== null);
    expect(Math.max(...xs) - Math.min(...xs), `${what}: every ${key} at one x (${xs.join(', ')})`).toBeLessThanOrEqual(0.5);
  }
}

/** In each entry, the name and the first icon at one offset from the slot, the slots of one width:
  * the statistics pager's items, side by side. */
function expectSameOffsets(list, what) {
  const names = list.map((e) => e.name - e.slot);
  const icons = list.filter((e) => e.icon !== null).map((e) => e.icon - e.slot);
  const widths = list.map((e) => e.width);
  for (const [xs, key] of [[names, 'name'], [icons, 'icon'], [widths, 'slot width']]) {
    expect(Math.max(...xs) - Math.min(...xs), `${what}: one ${key} offset (${xs.join(', ')})`).toBeLessThanOrEqual(0.5);
  }
}

for (const mode of ['light', 'dark']) {
  test.describe(`the rounds' menus and look in ${mode} mode`, () => {
    test(`the Rounds page: Status, Type and Order menus, the badges and an inactive row in ${mode}`, { tag: '@A-28' },
      async ({ contextAs, fixture, baseURL, browserName }, testInfo) => {
        // the round icons contest, which no spec writes to: in the fixture's contest other specs
        // create and distribute rounds (review.spec.js), and the Rounds page reloads when a job
        // ends (job-status.js), which closed the open Status menu
        test.skip(!fixture.roundsContestId, "the fixture has no round icons contest (the guides' screenshot mode)");
        const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        const page = await context.newPage();
        const rounds = `/admin/rounds?contestId=${fixture.roundsContestId}`;
        await page.goto(rounds);
        const status = page.locator('#rounds-status-menu');
        await expect(status.locator('> button')).toHaveAccessibleName('Status: All');
        await expect(page.locator('#rounds-type-menu > button')).toHaveAccessibleName('Type: All');
        await expect(page.locator('#rounds-order-menu > button')).toHaveAccessibleName('Order: Oldest first');

        // an active yes/no round's row: its icons; an active rated one, given the inactive look,
        // grey with its link
        const BINARY = 'Pre-screening of all the uploaded files of the national campaign, yes/no';
        const named = (name) => page.locator('tr.round-row', { has: page.getByRole('link', { name, exact: true }) });
        const row = named(BINARY);
        const other = named('Stars');
        expect(await slotOf(row)).toEqual({ type: 'binary', inactive: false, hidden: 'true',
          icons: ['wlx-round-yes', 'wlx-round-no'], title: 'Yes/No, Active' });
        expect(await slotOf(other)).toEqual({ type: 'rated', inactive: false, hidden: 'true',
          icons: ['wlx-round-star'], title: 'Rated, Active' });
        await expect(row.locator('a.round-stat-link')).toHaveAccessibleName(BINARY);
        await other.evaluate((tr) => tr.classList.add('wlx-round-inactive'));
        const pageBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        const stripe = await colour(page, 'var(--background-color-neutral-subtle)'); // the stripes (codex-theme.css)
        await checkLook(page, mode, { active: row, inactive: other.locator('td').nth(1), backgrounds: [pageBg, stripe], link: true });
        await other.evaluate((tr) => tr.classList.remove('wlx-round-inactive'));

        // the Type menu: a legend of the icons, coloured, All's slot empty
        const typeMenu = page.locator('#rounds-type-menu');
        await typeMenu.locator('> button').click();
        const legend = typeMenu.locator('.dropdown-item');
        await expect(legend).toHaveCount(3);
        expect((await slotOf(legend.nth(0))).icons).toEqual([]);
        expect(await slotOf(legend.nth(1))).toEqual({ type: 'binary', inactive: false, hidden: 'true',
          icons: ['wlx-round-yes', 'wlx-round-no'], title: null });
        expect(await slotOf(legend.nth(2))).toEqual({ type: 'rated', inactive: false, hidden: 'true',
          icons: ['wlx-round-star'], title: null });
        const legendBg = await typeMenu.locator('.dropdown-menu').evaluate((m) => getComputedStyle(m).backgroundColor);
        await legend.nth(1).hover();
        await checkIcons(page, mode, legend.nth(1), COLOURED, [legendBg, await colour(page, 'var(--wlx-hover-bg)')], 'the Yes/No legend');
        await checkIcons(page, mode, legend.nth(2), COLOURED, [legendBg], 'the Rated legend');
        await expect(legend.nth(1)).toHaveAccessibleName(/^Yes\/No\s*\d+$/);
        await page.keyboard.press('Escape');

        // a choice opens the page with it; the statistics link carries it
        await status.locator('> button').click();
        await expect(status.locator('.dropdown-menu')).toBeVisible();
        await expect(status.locator('.dropdown-item.active')).toContainText('All');
        if (browserName === 'chromium') await axe(page, testInfo, `rounds ${mode}`);
        await Promise.all([page.waitForURL(/status=active/), status.getByRole('link', { name: /^Active/ }).click()]);
        await expect(status.locator('> button')).toHaveAccessibleName('Status: Active');
        await expect(page.locator('tr.round-row.wlx-round-inactive')).toHaveCount(0);
        await Promise.all([page.waitForURL(/\/roundstat\/.*status=active/),
          row.getByRole('link', { name: BINARY, exact: true }).click()]);
        await expect(page.locator('#rounds-status-menu > button')).toHaveAccessibleName('Status: Active');
        // and back
        await page.locator('#pages-menu > button').click();
        await Promise.all([page.waitForURL(/\/admin\/rounds\?.*status=active/), page.locator('#menu-rounds').click()]);
        await context.close();
      });

    test(`the round statistics: the menus order its round menu, kept when switching round, in ${mode}`, { tag: '@O-07' },
      async ({ contextAs, fixture, baseURL, browserName }, testInfo) => {
        const context = await contextAs('organizer', { viewport: { width: 1440, height: 900 } });
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        const page = await context.newPage();
        await page.goto(`/roundstat/${fixture.roundBinaryId}`);
        const links = page.locator('#round-stat-rounds .page-link');
        const ids = async () => (await page.locator('#round-stat-rounds .page-item').evaluateAll((lis) => lis.map((li) => li.dataset.round)));
        const fixtureIds = [String(fixture.roundBinaryId), String(fixture.roundRatingId)];
        const ours = (list) => list.filter((id) => fixtureIds.includes(id));
        const ascending = (list) => list.every((id, i) => i === 0 || Number(list[i - 1]) < Number(id));
        const oldest = await ids();
        expect(ours(oldest)).toEqual(fixtureIds);
        expect(ascending(oldest), `the oldest first: ${oldest}`).toBe(true);

        const order = page.locator('#rounds-order-menu');
        await order.locator('> button').click();
        await expect(order.locator('.dropdown-menu')).toBeVisible();
        // the statistics table's disabled view links: triaged as U20 (pages.spec.js KNOWN)
        if (browserName === 'chromium') await axe(page, testInfo, `round statistics ${mode}`, { 'aria-prohibited-attr': 'U20' });
        await Promise.all([page.waitForURL(/order=newest/), order.getByRole('link', { name: 'Newest first' }).click()]);
        const newest = await ids();
        expect(ours(newest)).toEqual([...fixtureIds].reverse());
        expect(ascending([...newest].reverse()), `the newest first: ${newest}`).toBe(true);
        // the round menu's links keep the order; switching round keeps it
        for (const href of await links.evaluateAll((as) => as.map((a) => a.getAttribute('href')))) expect(href).toContain('order=newest');
        await Promise.all([page.waitForURL(new RegExp(`/roundstat/${fixture.roundRatingId}\\?order=newest`)),
          page.locator(`#round-stat-rounds .page-item[data-round="${fixture.roundRatingId}"] > .page-link`).click()]);
        await expect(page.locator('#rounds-order-menu > button')).toHaveAccessibleName('Order: Newest first');

        // the look: the current round's (selected) and the other's, given the inactive look
        const current = page.locator('#round-stat-rounds .page-item.active > .page-link');
        // the name and what its icons show read apart (", " for screen readers only,
        // snippets/roundSpoken; the icons hidden from them), the name first
        // (Chromium may put a space before the hidden text, out of the line's flow: "Rating Round , Rated, Active")
        await expect(current).toHaveAccessibleName(/^Rating Round ?, Rated, Active$/);
        const otherLink = page.locator(`#round-stat-rounds .page-item[data-round="${fixture.roundBinaryId}"] > .page-link`);
        await otherLink.evaluate((a) => a.classList.add('wlx-round-inactive'));
        const bg = await otherLink.evaluate((a) => getComputedStyle(a).backgroundColor);
        await otherLink.hover();
        const hoverBg = await colour(page, 'var(--wlx-hover-bg)');
        await checkLook(page, mode, { active: current, inactive: otherLink, backgrounds: [bg, hoverBg] });
        await page.mouse.move(0, 0);
        await checkGreyOnCurrent(page, mode, current, 'the current round in the pager');
        await context.close();
      });

    test(`the galleries' round menu: the same look, no filter menus, in ${mode}`, { tag: '@O-07' },
      async ({ contextAs, fixture, baseURL }) => {
        const context = await contextAs('organizer', { viewport: { width: 1440, height: 900 } });
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        const page = await context.newPage();
        await page.goto(galleryPath(fixture.roundBinaryId, 0));
        await expect(page.locator('#rounds-status-menu, #rounds-type-menu, #rounds-order-menu')).toHaveCount(0);
        const items = page.locator('nav .wlx-nav-filters .dropdown-item:has(.wlx-round-icon)');
        const menu = items.first().locator('xpath=ancestor::li[contains(@class, "dropdown")]');
        await menu.locator('> button').click();
        // the fixture's two rounds' items (other specs' rounds may be listed too)
        const item = (roundId) => items.and(page.locator(`[href*="/round/${roundId}/"]`));
        const active = item(fixture.roundBinaryId);
        const other = item(fixture.roundRatingId);
        await expect(active).toHaveCount(1);
        await expect(other).toHaveCount(1);
        expect(await slotOf(active)).toEqual({ type: 'binary', inactive: false, hidden: 'true',
          icons: ['wlx-round-yes', 'wlx-round-no'], title: 'Yes/No, Active' });
        expect((await slotOf(other)).icons).toEqual(['wlx-round-star']);
        await expect(active).toHaveAccessibleName(/^Yes\/No Round ?, Yes\/No, Active$/);
        expectLinedUp(await edges(items), `${mode}: the galleries' round menu`);
        await other.evaluate((a) => a.classList.add('wlx-round-inactive'));
        const menuBg = await other.evaluate((a) => getComputedStyle(a.closest('.dropdown-menu')).backgroundColor);
        await other.hover();
        const hoverBg = await colour(page, 'var(--wlx-hover-bg)');
        await checkLook(page, mode, { active, inactive: other, backgrounds: [menuBg, hoverBg] });
        await page.mouse.move(0, 0);
        await checkGreyOnCurrent(page, mode, menu.locator('.dropdown-item.active'), 'the current round in the round menu');
        await context.close();
      });
  });
}

// The galleries' round menu at a phone's width (390 px), with a long round name (put in the page:
// the fixture's rounds have short names, and renaming one would change the other tests' pages): the
// page doesn't scroll sideways and the open menu stays within the window.
test("the galleries' round menu fits a phone's width with a long round name", { tag: '@O-07' },
  async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    await page.goto(galleryPath(fixture.roundBinaryId, 0));
    const items = page.locator('nav .wlx-nav-filters .dropdown-item:has(.wlx-round-icon)');
    const long = "Wiki Loves Monuments 2026 in Ukraine, the national jury's final round of the best photos";
    await items.evaluateAll((as, name) => as.forEach((a) => { a.querySelector('.wlx-round-icon').nextSibling.textContent = name; }), long);
    const menu = items.first().locator('xpath=ancestor::li[contains(@class, "dropdown")]');
    // the toggle's current round, cut with an ellipsis (wlx-truncate), as long
    await menu.locator('> button .wlx-truncate').evaluate((el, name) => { el.textContent = name; }, long);
    await menu.locator('> button').click();
    await expect(menu.locator('.dropdown-menu')).toBeVisible();
    const box = await menu.locator('.dropdown-menu').boundingBox();
    const widths = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, window: document.documentElement.clientWidth }));
    expect(widths.page, 'no horizontal scroll of the page').toBeLessThanOrEqual(widths.window);
    expect(box.x, 'the menu starts within the window').toBeGreaterThanOrEqual(0);
    expect(box.x + box.width, 'the menu ends within the window').toBeLessThanOrEqual(widths.window);
    // wider than Bootstrap's 10rem: a name wraps by lines, not word by word
    expect(box.width, 'the round menu at least 20rem or the window less 1rem').toBeGreaterThanOrEqual(Math.min(320, widths.window - 16) - 1);
    // each item's icons inside the menu, before the wrapped name, lined up: the name's lines start
    // after the slot (flex), not under the icons
    for (const icon of await menu.locator('.dropdown-menu .wlx-round-icon').all()) {
      const b = await icon.boundingBox();
      expect(b.x).toBeGreaterThanOrEqual(box.x);
      expect(b.x + b.width).toBeLessThanOrEqual(box.x + box.width);
    }
    expectLinedUp(await edges(items), "the round menu at a phone's width");
    await context.close();
  });

// The galleries' round menu on a desktop (1280 px), a long round name in it: the toggle shows the
// whole name (cut only below 992 px), each item is one line and the menu is wider than its toggle.
test('the round menu of the galleries on a desktop: whole names on one line', { tag: '@O-07' },
  async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await page.goto(galleryPath(fixture.roundBinaryId, 0));
    const menu = page.locator('#round-menu');
    const name = 'Second round of the national jury, monuments of Kyiv';
    // the current round's name, long, in the toggle and its item, as the page would draw it
    await menu.locator('> button .wlx-truncate').evaluate((el, n) => { el.textContent = n; }, name);
    await menu.locator('.dropdown-item.active').evaluate((el, n) => { el.querySelector('.wlx-round-icon').nextSibling.textContent = n; }, name);
    const shown = await menu.locator('> button .wlx-truncate').evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth, display: getComputedStyle(el).display }));
    expect(shown.display, 'the name in the toggle inline, not cut').toBe('inline');
    await menu.locator('> button').click();
    const items = menu.locator('.dropdown-menu .dropdown-item');
    await expect(items.first()).toBeVisible();
    for (const item of await items.all()) {
      // its content no taller than one and a half lines: one line
      const box = await item.evaluate((el) => {
        const s = getComputedStyle(el);
        return { content: el.clientHeight - parseFloat(s.paddingTop) - parseFloat(s.paddingBottom), line: parseFloat(s.lineHeight) };
      });
      expect(box.content, 'a round menu item on one line').toBeLessThan(box.line * 1.5);
    }
    const menuBox = await menu.locator('.dropdown-menu').boundingBox();
    const toggleBox = await menu.locator('> button').boundingBox();
    expect(menuBox.width, 'the menu wider than its toggle').toBeGreaterThan(toggleBox.width);
    await context.close();
  });

// The galleries' round menu on a narrow desktop (800 px, above the phone's wrapping), a name
// longer than the window: no horizontal scrollbar in the menu, the name cut with an ellipsis, the
// icons whole and lined up. (The menu, as wide as the window less 1rem from the toggle's left
// edge, may still end past the window here, as before.)
test("the galleries' round menu cuts a name too long for a narrow desktop, no sideways scroll", { tag: '@O-07' },
  async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 800, height: 700 } });
    const page = await context.newPage();
    await page.goto(galleryPath(fixture.roundBinaryId, 0));
    const menu = page.locator('#round-menu');
    const long = "Wiki Loves Monuments 2026 in Ukraine, the national jury's final round of the best photos of every region";
    await menu.locator('.dropdown-item.active .wlx-item-text').evaluate((el, n) => { el.textContent = n; }, long);
    await menu.locator('> button').click();
    const list = menu.locator('.dropdown-menu');
    await expect(list).toBeVisible();
    const m = await list.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth, overflowX: getComputedStyle(el).overflowX }));
    expect(m.overflowX).toBe('hidden');
    expect(m.scroll, 'no horizontal scroll in the round menu').toBeLessThanOrEqual(m.client);
    const text = await menu.locator('.dropdown-item.active .wlx-item-text').evaluate((el) => ({
      cut: el.scrollWidth > el.clientWidth, ellipsis: getComputedStyle(el).textOverflow }));
    expect(text).toEqual({ cut: true, ellipsis: 'ellipsis' });
    expectLinedUp(await edges(menu.locator('.dropdown-item')), 'the round menu on a narrow desktop');
    await context.close();
  });

// The screenshots of the report (opt-in, this spec alone): the rating round stopped for real, the
// pages with a menu open in both modes and at a phone's width; the round started again after.
test('the rounds filter screenshots', { tag: '@A-28' }, async ({ contextAs, fixture, baseURL, browserName }) => {
  test.skip(!SHOTS || browserName !== 'chromium', 'ROUNDS_FILTER_SHOTS unset');
  fs.mkdirSync(SHOTS, { recursive: true });
  const context = await contextAs('admin', { viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const rounds = `/admin/rounds?contestId=${fixture.contestId}`;
  const toggle = async (round) => {
    await page.goto(rounds);
    await Promise.all([page.waitForURL(/\/admin\/rounds/),
      page.locator(`#set-active-form-${round} button[type=submit]`).click()]);
  };
  await toggle(fixture.roundRatingId); // stopped
  try {
    for (const mode of ['light', 'dark']) {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      await page.goto(rounds);
      await page.locator('#rounds-status-menu > button').click();
      await page.screenshot({ path: path.join(SHOTS, `rounds-${mode}.png`) });
      await page.goto(`/roundstat/${fixture.roundBinaryId}?order=active`);
      await page.locator('#rounds-type-menu > button').click();
      await page.screenshot({ path: path.join(SHOTS, `roundstat-${mode}.png`) });
      await page.goto(galleryPath(fixture.roundBinaryId, 0));
      const items = page.locator('nav .wlx-nav-filters .dropdown-item:has(.wlx-round-icon)');
      await items.first().locator('xpath=ancestor::li[contains(@class, "dropdown")]').locator('> button').click();
      await page.screenshot({ path: path.join(SHOTS, `gallery-round-menu-${mode}.png`) });
    }
    await context.addCookies([{ name: 'theme', value: 'light', url: baseURL }]);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${rounds}&order=active`);
    await page.locator('#rounds-order-menu > button').click();
    await page.screenshot({ path: path.join(SHOTS, 'rounds-phone.png'), fullPage: true });
    const widths = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, window: document.documentElement.clientWidth }));
    expect(widths.page, 'no horizontal scroll of the page at a phone\'s width').toBeLessThanOrEqual(widths.window);
  } finally {
    await page.setViewportSize({ width: 1440, height: 900 });
    await toggle(fixture.roundRatingId); // started again
    await context.close();
  }
});

// The round icons contest (roundsContestId: 30 rounds, yes/no, rated and comments-only, active and
// not, names of very different lengths), as root: on the Rounds page, in the statistics pager and in
// the galleries' round menu each round's icons and name start at the same place whatever its name's
// length (each slot's, first icon's and name's left edge measured); the round menu scrolls inside
// itself, as the region menu, within the window, and the keyboard keeps the focused item in view.
for (const mode of ['light', 'dark']) {
  test(`the round icons line up on every round page, in ${mode}`, { tag: '@A-28' },
    async ({ contextAs, fixture, baseURL }) => {
      test.skip(!fixture.roundsContestId, 'the fixture has no round icons contest (the guides\' screenshot mode)');
      const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      const page = await context.newPage();
      await page.goto(`/admin/rounds?contestId=${fixture.roundsContestId}`);
      const cells = page.locator('tbody tr.round-row .wlx-round-named');
      await expect(cells).toHaveCount(30);
      expectLinedUp(await edges(cells), `${mode}: the Rounds table`);
      const types = await page.locator('tbody tr.round-row .wlx-round-icon').evaluateAll((els) =>
        els.map((e) => `${e.dataset.type}${e.classList.contains('wlx-round-icon-inactive') ? '-off' : ''}`));
      for (const t of ['binary', 'binary-off', 'rated', 'rated-off', 'none', 'none-off']) expect(types).toContain(t);
      const ids = await page.locator('tbody tr.round-row').evaluateAll((trs) => trs.map((tr) => tr.dataset.round));

      await page.goto(`/roundstat/${ids[2]}`);
      // the pager's chips: a comments-only round's without the empty slot (its chip centred
      // anyway), the others' icons and names at one offset in each
      await expect(page.locator('#round-stat-rounds .page-link')).toHaveCount(30);
      const pager = page.locator('#round-stat-rounds .page-link:has(.wlx-round-icon)');
      await expect(pager).toHaveCount(24);
      await expect(page.locator('#round-stat-rounds .page-link:has(.wlx-round-icon[data-type=none])')).toHaveCount(0);
      expectSameOffsets(await edges(pager), `${mode}: the statistics pager`);

      await page.goto(galleryPath(ids[2], 0));
      const menu = page.locator('#round-menu');
      await menu.locator('> button').click();
      const items = menu.locator('.dropdown-menu .dropdown-item');
      await expect(items).toHaveCount(30);
      expectLinedUp(await edges(items), `${mode}: the galleries' round menu`);
      await context.close();
    });
}

// The statistics page's Status, Type and Order items (the maintainer's decision of 2026-11-19,
// "switch to first in order"): a choice that leaves out the round shown opens the first round it
// lists, in its order; one that keeps it (the order alone) stays; a round opened with a filter that
// leaves it out is shown selected, with a dashed outline and its tooltip. On the round icons contest
// (rounds 1-30 in creation order, every third from the first stopped: 1, 4, ..., 28), as root,
// with an axe scan of the marked page, its Status menu open (Chromium), in light and dark mode.
for (const mode of ['light', 'dark']) {
  test(`the statistics filter opens the first round it lists, in ${mode}`, { tag: '@O-07' },
    async ({ contextAs, fixture, baseURL, browserName }, testInfo) => {
      test.skip(!fixture.roundsContestId, 'the fixture has no round icons contest (the guides\' screenshot mode)');
      const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      const page = await context.newPage();
      await page.goto(`/admin/rounds?contestId=${fixture.roundsContestId}`);
      const ids = await page.locator('tbody tr.round-row').evaluateAll((trs) => trs.map((tr) => tr.dataset.round));
      expect(ids).toHaveLength(30);
      const selected = () => page.locator('#round-stat-rounds .page-item.active').getAttribute('data-round');
      const status = page.locator('#rounds-status-menu');
      const pickStatus = async (name, url) => {
        await status.locator('> button').click();
        await Promise.all([page.waitForURL(url), status.getByRole('link', { name }).click()]);
      };

      // round 2 (active): Not active leaves it out and opens round 1, the oldest stopped round
      await page.goto(`/roundstat/${ids[1]}`);
      await pickStatus(/^Not active/, new RegExp(`/roundstat/${ids[0]}\\?status=inactive$`));
      expect(await selected()).toBe(ids[0]);
      await expect(page.locator('#rounds-status-menu > button')).toHaveAccessibleName('Status: Not active');
      await expect(page.locator('#round-stat-rounds .wlx-round-outside')).toHaveCount(0);
      // the order alone keeps the round
      const order = page.locator('#rounds-order-menu');
      await order.locator('> button').click();
      await Promise.all([page.waitForURL(new RegExp(`/roundstat/${ids[0]}\\?status=inactive&order=newest$`)),
        order.getByRole('link', { name: 'Newest first' }).click()]);
      expect(await selected()).toBe(ids[0]);
      // newest first, from round 2: Not active opens round 28, the newest stopped round
      await page.goto(`/roundstat/${ids[1]}?order=newest`);
      await pickStatus(/^Not active/, new RegExp(`/roundstat/${ids[27]}\\?status=inactive&order=newest$`));
      expect(await selected()).toBe(ids[27]);
      // and back to Active: round 28 is stopped, so the newest active round, 30
      await pickStatus(/^Active/, new RegExp(`/roundstat/${ids[29]}\\?status=active&order=newest$`));

      // opened with a filter that leaves it out (an old link): shown, selected and marked
      await page.goto(`/roundstat/${ids[1]}?status=inactive`);
      expect(await selected()).toBe(ids[1]);
      const chip = page.locator('#round-stat-rounds .page-item.active > .page-link');
      await expect(chip).toHaveClass(/wlx-round-outside/);
      // the tooltip on the item; the words read once, in the link's name, not again as its description
      await expect(page.locator('#round-stat-rounds .page-item.active')).toHaveAttribute('title', 'Not in the chosen filter');
      await expect(chip).toHaveAccessibleName(/Not in the chosen filter$/);
      await expect(chip).toHaveAccessibleDescription('');
      // a dashed outline in the chip's text colour (--wlx-current-fg), 3:1 or more on its fill
      const look = await chip.evaluate((a) => {
        const cs = getComputedStyle(a);
        return { style: cs.outlineStyle, width: cs.outlineWidth, colour: cs.outlineColor, color: cs.color, bg: cs.backgroundColor };
      });
      expect(look.style).toBe('dashed');
      expect(look.width).toBe('2px');
      expect(look.colour).toBe(look.color);
      expect(look.colour).toBe(await colour(page, 'var(--wlx-current-fg)'));
      expect(contrast(look.colour, look.bg), `the outline on the chip's fill in ${mode}`).toBeGreaterThanOrEqual(3);
      // focused: solid (seen also in forced-colours mode, where the focus ring isn't drawn)
      await chip.focus();
      await page.keyboard.press('Shift+Tab');
      await page.keyboard.press('Tab');
      await expect(chip).toBeFocused();
      expect(await chip.evaluate((a) => getComputedStyle(a).outlineStyle)).toBe('solid');
      await page.mouse.move(0, 0);
      if (SHOTS_STAT) {
        fs.mkdirSync(SHOTS_STAT, { recursive: true });
        const row = page.locator('#round-stat-rounds');
        await row.screenshot({ path: path.join(SHOTS_STAT, `outside-focused-${mode}.png`) });
        await chip.evaluate((a) => a.blur());
        await row.screenshot({ path: path.join(SHOTS_STAT, `outside-selected-${mode}.png`) });
        await page.screenshot({ path: path.join(SHOTS_STAT, `roundstat-outside-${mode}.png`) });
      }
      await expect(page.locator('#round-stat-rounds .wlx-round-outside')).toHaveCount(1);
      await expect(page.locator('#rounds-filter-none')).toHaveCount(0);
      await status.locator('> button').click();
      await expect(status.locator('.dropdown-menu')).toBeVisible();
      // the statistics table's disabled view links: triaged as U20 (pages.spec.js KNOWN)
      if (browserName === 'chromium') await axe(page, testInfo, `round statistics outside the filter ${mode}`, { 'aria-prohibited-attr': 'U20' });
      await context.close();
    });
}

// The Rounds table at 1280 px (the maintainer, 2026-11-19): Number, File distribution, Rating
// type, Active and Images as wide as their contents, on one line; the names take the width left,
// none of the fixture's names (up to 85 characters) over two lines; at a phone's width the table
// scrolls in its own box, not the page.
// The round statistics' jurors table (the maintainer, 2026-11-19): each juror's and the Total
// row's icon links and the count beside them centred on one line (wlx-links-count), in both modes
// and at a phone's width; Codex's 28 px small buttons had left the icons at their top.
for (const [mode, width] of [['light', 1280], ['dark', 1280], ['light', 390]]) {
  test(`the round statistics' icon links and counts centred on one line, ${mode} at ${width} px`, { tag: '@O-01' },
    async ({ contextAs, fixture, baseURL }) => {
      const context = await contextAs('organizer', { viewport: { width, height: 900 } });
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      const page = await context.newPage();
      await page.goto(`/roundstat/${fixture.roundRatingId}`);
      const offsets = await page.locator('.wlx-links-count').evaluateAll((groups) => groups.flatMap((g) => {
        const n = g.querySelector('.wlx-links-count-n');
        const range = document.createRange();
        range.selectNodeContents(n);
        const t = range.getClientRects()[0];
        const centre = t.top + t.height / 2;
        const cell = g.closest('td, th').getBoundingClientRect();
        const cellCentre = cell.top + cell.height / 2;
        return [...g.querySelectorAll('.btn')].map((b) => {
          const r = b.getBoundingClientRect();
          const i = b.querySelector('i').getBoundingClientRect();
          return { count: n.textContent.trim(), icon: Math.abs(i.top + i.height / 2 - centre),
            square: Math.abs(r.width - r.height),
            inButton: Math.max(Math.abs(i.left + i.width / 2 - (r.left + r.width / 2)), Math.abs(i.top + i.height / 2 - (r.top + r.height / 2))),
            button: Math.abs(r.top + r.height / 2 - cellCentre), countInCell: Math.abs(centre - cellCentre), size: r.height };
        });
      }));
      expect(offsets.length, 'icon links with counts').toBeGreaterThan(4);
      for (const o of offsets) {
        expect(o.icon, `an icon beside the count ${o.count}`).toBeLessThanOrEqual(1);
        expect(o.square, `a square button beside the count ${o.count}`).toBeLessThanOrEqual(1);
        expect(o.inButton, `its icon in the middle of the button (${o.count})`).toBeLessThanOrEqual(1);
        expect(o.button, `the button in the middle of the cell (${o.count})`).toBeLessThanOrEqual(1);
        expect(o.countInCell, `the count ${o.count} in the middle of the cell`).toBeLessThanOrEqual(1);
      }
      // one size for all
      expect(new Set(offsets.map((o) => Math.round(o.size))).size).toBe(1);
      // the table scrolls in its own box, never the page
      const widths = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, window: window.innerWidth }));
      expect(widths.page, 'no sideways scroll of the page').toBeLessThanOrEqual(widths.window);
      await context.close();
    });
}

// The Rounds table's Active column (the maintainer, 2026-11-19): the start/stop button first, its
// Yes/No after it, so every button starts at one place whatever the word's width ("Так"/"Ні").
for (const lang of ['en', 'uk']) {
  test(`the Rounds table's start/stop buttons line up, in ${lang}`, { tag: '@A-28' }, async ({ contextAs, fixture }) => {
    test.skip(!fixture.roundsContestId, "the fixture has no round icons contest (the guides' screenshot mode)");
    const context = await contextAs('root', { viewport: { width: 1280, height: 900 }, locale: lang === 'uk' ? 'uk-UA' : 'en-US' });
    await context.addCookies([{ name: 'PLAY_LANG', value: lang, url: fixture.baseUrl }]);
    const page = await context.newPage();
    await page.goto(`/admin/rounds?contestId=${fixture.roundsContestId}`);
    await expect(page.locator('html')).toHaveAttribute('lang', lang);
    const cells = await page.locator('tbody tr.round-row').evaluateAll((trs) => trs.map((tr) => {
      const cell = tr.children[4];
      const button = cell.querySelector('form[id^=set-active-form-] button');
      const text = cell.querySelector('.wlx-start-stop-text');
      return { button: button.getBoundingClientRect().left, text: text.getBoundingClientRect().left,
        word: text.textContent.trim(), name: button.getAttribute('aria-label') };
    }));
    expect(cells.length).toBe(30);
    const buttons = cells.map((c) => c.button);
    expect(Math.max(...buttons) - Math.min(...buttons), `the buttons at one x: ${buttons}`).toBeLessThanOrEqual(0.5);
    for (const c of cells) expect(c.text, `${c.word} after its button`).toBeGreaterThan(c.button);
    expect(new Set(cells.map((c) => c.word)).size, 'both words, active and not').toBe(2);
    await context.close();
  });
}

test('the Rounds table gives the names the width at 1280 px', { tag: '@A-28' }, async ({ contextAs, fixture }) => {
  test.skip(!fixture.roundsContestId, "the fixture has no round icons contest (the guides' screenshot mode)");
  const context = await contextAs('root', { viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  await page.goto(`/admin/rounds?contestId=${fixture.roundsContestId}`);
  const rows = await page.locator('tbody tr.round-row').evaluateAll((trs) => trs.map((tr) => {
    const cells = [...tr.children];
    const lines = (el) => { const s = getComputedStyle(el); return Math.round((el.getBoundingClientRect().height - parseFloat(s.paddingTop) - parseFloat(s.paddingBottom)) / parseFloat(s.lineHeight)); };
    return { number: cells[0].getBoundingClientRect().width, name: cells[1].getBoundingClientRect().width,
      nameLines: lines(cells[1].querySelector('a.round-stat-link')),
      oneLine: [0, 2, 3, 5].map((i) => getComputedStyle(cells[i]).whiteSpace), active: cells[4].getBoundingClientRect().height };
  }));
  expect(rows.length).toBe(30);
  for (const r of rows) {
    expect(r.name, 'the Name column wider than Number').toBeGreaterThan(r.number * 3);
    expect(r.nameLines, 'a name on one or two lines').toBeLessThanOrEqual(2);
    expect(r.oneLine).toEqual(['nowrap', 'nowrap', 'nowrap', 'nowrap']);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  const widths = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, window: document.documentElement.clientWidth }));
  expect(widths.page, "no horizontal scroll of the page at a phone's width").toBeLessThanOrEqual(widths.window);
  // on a phone the names read by lines (14rem), not a word or two to a line: the longest (85
  // characters) in four lines at most
  const phoneLines = await page.locator('tbody tr.round-row a.round-stat-link').evaluateAll((as) => as.map((a) => {
    const s = getComputedStyle(a);
    return Math.round(a.getBoundingClientRect().height / parseFloat(s.lineHeight));
  }));
  expect(Math.max(...phoneLines), `the names' lines at 390 px: ${phoneLines}`).toBeLessThanOrEqual(4);
  await context.close();
});

for (const viewport of [{ width: 1280, height: 720 }, { width: 390, height: 664 }]) {
  test(`the galleries' round menu scrolls inside itself at ${viewport.width}x${viewport.height}`, { tag: '@O-07' },
    async ({ contextAs, fixture }) => {
      test.skip(!fixture.roundsContestId, 'the fixture has no round icons contest (the guides\' screenshot mode)');
      const context = await contextAs('root', { viewport });
      const page = await context.newPage();
      await page.goto(`/admin/rounds?contestId=${fixture.roundsContestId}`);
      const first = await page.locator('tbody tr.round-row').first().getAttribute('data-round');
      await page.goto(galleryPath(first, 0));
      const menu = page.locator('#round-menu');
      await menu.locator('> button').click();
      const list = menu.locator('.dropdown-menu');
      await expect(list).toBeVisible();
      const m = await list.evaluate((el) => {
        const r = el.getBoundingClientRect();
        const s = getComputedStyle(el);
        return { scroll: el.scrollHeight, client: el.clientHeight, top: r.top, bottom: r.bottom, overflowY: s.overflowY,
          window: window.innerHeight, pageScroll: document.documentElement.scrollHeight };
      });
      expect(m.overflowY, 'the round menu scrolls as the region menu').toBe('auto');
      expect(m.scroll, 'the round menu longer than it shows: it scrolls').toBeGreaterThan(m.client);
      expect(m.bottom, 'the round menu ends within the window').toBeLessThanOrEqual(m.window);
      // the same rule as the region menu's (main.css): at most 70% of the window's height
      expect(m.bottom - m.top).toBeLessThanOrEqual(m.window * 0.7 + 1);
      // the keyboard: down to the last item, which is scrolled into the menu's view
      await page.keyboard.press('ArrowDown');
      const count = await menu.locator('.dropdown-item').count();
      for (let i = 0; i < count + 2; i++) await page.keyboard.press('ArrowDown');
      const focused = menu.locator('.dropdown-item:focus');
      await expect(focused).toHaveCount(1);
      await expect(focused).toHaveAttribute('href', await menu.locator('.dropdown-item').last().getAttribute('href'));
      const inView = await page.evaluate(() => {
        const f = document.activeElement.getBoundingClientRect();
        const l = document.activeElement.closest('.dropdown-menu').getBoundingClientRect();
        return f.top >= l.top - 1 && f.bottom <= l.bottom + 1;
      });
      expect(inView, 'the focused item within the menu\'s view').toBe(true);
      expect(await list.evaluate((el) => el.scrollTop), 'the menu scrolled').toBeGreaterThan(0);
      await context.close();
    });
}

// The report's screenshots of the round icons (opt-in, Chromium): the Rounds page, the statistics
// page and the galleries' round menu open, light and dark, and the Rounds page at a phone's width.
test('the round icons screenshots', { tag: '@A-28' }, async ({ contextAs, fixture, baseURL, browserName }) => {
  test.skip(!ICON_SHOTS || browserName !== 'chromium' || !fixture.roundsContestId, 'ROUND_ICONS_SHOTS unset');
  fs.mkdirSync(ICON_SHOTS, { recursive: true });
  const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const rounds = `/admin/rounds?contestId=${fixture.roundsContestId}`;
  await page.goto(rounds);
  const ids = await page.locator('tbody tr.round-row').evaluateAll((trs) => trs.map((tr) => tr.dataset.round));
  for (const mode of ['light', 'dark']) {
    await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
    await page.goto(rounds);
    await page.screenshot({ path: path.join(ICON_SHOTS, `rounds-${mode}.png`), fullPage: true });
    await page.locator('#rounds-type-menu > button').click();
    await page.locator('#rounds-type-menu .dropdown-item').nth(1).hover();
    await page.screenshot({ path: path.join(ICON_SHOTS, `rounds-type-legend-${mode}.png`) });
    await page.goto(`/roundstat/${ids[2]}`);
    await page.screenshot({ path: path.join(ICON_SHOTS, `roundstat-${mode}.png`) });
    // the fixture's rated round, with its jurors: the icon links and counts of the jurors table
    await page.goto(`/roundstat/${fixture.roundRatingId}`);
    await page.screenshot({ path: path.join(ICON_SHOTS, `roundstat-jurors-${mode}.png`), fullPage: true });
    await page.goto(galleryPath(ids[2], 0));
    await page.locator('#round-menu > button').click();
    await page.locator('#round-menu .dropdown-item').nth(4).hover();
    await page.screenshot({ path: path.join(ICON_SHOTS, `gallery-round-menu-${mode}.png`) });
  }
  await context.addCookies([{ name: 'theme', value: 'light', url: baseURL }]);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(rounds);
  await page.screenshot({ path: path.join(ICON_SHOTS, 'rounds-phone.png'), fullPage: true });
  await page.goto(`/roundstat/${ids[2]}`);
  await page.screenshot({ path: path.join(ICON_SHOTS, 'roundstat-phone.png') });
  await page.goto(galleryPath(ids[2], 0));
  await page.locator('#round-menu > button').click();
  await page.screenshot({ path: path.join(ICON_SHOTS, 'gallery-round-menu-phone.png') });
  await context.close();
});

test('the Status and Type menus list no item of 0 but the chosen one and All', { tag: '@A-28' }, async ({ contextAs, fixture }) => {
  const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const MENUS = ['rounds-status-menu', 'rounds-type-menu'];
  const pages = [`/admin/rounds?contestId=${fixture.contestId}`, `/roundstat/${fixture.roundBinaryId}`];
  if (fixture.roundsContestId) pages.push(`/admin/rounds?contestId=${fixture.roundsContestId}`);
  for (const base of pages) {
    for (const query of ['', 'status=active', 'status=inactive', 'type=binary', 'type=rated', 'status=inactive&type=rated']) {
      const path = query ? `${base}${base.includes('?') ? '&' : '?'}${query}` : base;
      await page.goto(path);
      expect(await emptyItems(page, MENUS), `${path}: items of 0`).toEqual([]);
      // each menu's choice listed and marked, whatever its count
      for (const id of MENUS) await expect(page.locator(`#${id} .dropdown-item.active`), `${path}: ${id}`).toHaveCount(1);
    }
  }
  await context.close();
});
