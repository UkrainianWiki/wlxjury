// The Rounds page's and the round statistics page's Status, Type and Order menus (the maintainer's
// decisions of 2026-11-18): a choice opens the page with it, kept in the URL and by the links between
// the two pages; the rounds' look, the same on both pages and in the galleries' round menu: an active
// round's pale green "Active" badge, each round's grey type badge, an inactive round's text and type
// badge in the secondary grey at 4.5:1 or more, its links still in the link colour, in light and dark
// mode; an axe scan of each page with a menu open (Chromium).
//
// The fixture's two rounds are both active, and stopping one would change what the other tests see
// (they run in parallel); other specs add rounds to the contest as they run (review.spec.js: two per
// engine), so the round menus are checked on the fixture's two only, whatever else they list. The inactive look is checked by giving a row or an item the class the
// server gives an inactive round (wlx-round-inactive, RoundsFilterSpec asserts the server's side),
// so its computed colours are main.css's. ROUNDS_FILTER_SHOTS=<dir> (a run of this spec alone)
// stops the rating round for real, saves the screenshots and starts it again.
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

const SHOTS = process.env.ROUNDS_FILTER_SHOTS;

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

/** An axe scan: no serious or critical finding but those triaged for the page (`known`: rule ->
  * its triage entry, as pages.spec.js's KNOWN). */
async function axe(page, testInfo, name, known = {}) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const serious = results.violations.filter((v) => ['serious', 'critical'].includes(v.impact) && !known[v.id])
    .map((v) => ({ rule: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }));
  await testInfo.attach(`axe ${name}`, { body: JSON.stringify(serious, null, 1), contentType: 'application/json' });
  expect(serious, `${name}: serious or critical accessibility findings`).toEqual([]);
}

/** Checks a round's look in `scope`: the Active badge (pale green, 4.5:1), the type badge (grey,
  * 4.5:1, a fill other than the Active badge's), and an inactive one's (`inactive`) text and type
  * badge in grey at 4.5:1 on each background it may have (`backgrounds`), its links in the link
  * colour. */
async function checkLook(page, mode, { active, inactive, backgrounds, link }) {
  const success = active.locator('.wlx-round-active');
  await expect(success).toBeVisible();
  const ok = await textOn(success);
  expect(contrast(ok.color, ok.bg), `${mode}: the Active badge's text`).toBeGreaterThanOrEqual(4.5);
  const type = active.locator('.wlx-round-type');
  await expect(type).toBeVisible();
  const t = await textOn(type);
  expect(contrast(t.color, t.bg), `${mode}: the type badge's text`).toBeGreaterThanOrEqual(4.5);
  expect(t.bg, `${mode}: the type badge is grey, not the Active badge's green`).not.toBe(ok.bg);
  expect(t.bg).toBe(await colour(page, 'var(--background-color-neutral)'));
  expect(ok.bg).toBe(await colour(page, 'var(--background-color-success-subtle)'));
  // the type badge's subtle border sets it apart where its grey fill is the ground's (the dark
  // stripes, the menus' hover grey): 1.3:1 or more on each background and on its own fill
  const ring = (await type.evaluate((el) => getComputedStyle(el).boxShadow)).match(/rgba?\([^)]*\)/);
  expect(ring, `${mode}: the type badge has a border`).not.toBeNull();
  expect(ring[0]).toBe(await colour(page, 'var(--border-color-subtle)'));
  for (const bg of [...backgrounds, t.bg]) {
    expect(contrast(ring[0], bg), `${mode}: the type badge's border on ${bg}`).toBeGreaterThanOrEqual(1.3);
  }

  const grey = await colour(page, 'var(--bs-secondary-color)');
  const text = await inactive.evaluate((el) => getComputedStyle(el).color);
  expect(text, `${mode}: the inactive round's text in the secondary grey`).toBe(grey);
  for (const bg of backgrounds) {
    expect(contrast(text, bg), `${mode}: the inactive round's text on ${bg}`).toBeGreaterThanOrEqual(4.5);
  }
  const itsType = inactive.locator('.wlx-round-type');
  if (await itsType.count()) {
    const g = await textOn(itsType);
    expect(g.color, `${mode}: the inactive round's type badge in grey`).toBe(grey);
    expect(contrast(g.color, g.bg), `${mode}: the inactive round's type badge`).toBeGreaterThanOrEqual(4.5);
  }
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

for (const mode of ['light', 'dark']) {
  test.describe(`the rounds' menus and look in ${mode} mode`, () => {
    test(`the Rounds page: Status, Type and Order menus, the badges and an inactive row in ${mode}`, { tag: '@A-28' },
      async ({ contextAs, fixture, baseURL, browserName }, testInfo) => {
        const context = await contextAs('admin', { viewport: { width: 1440, height: 900 } });
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        const page = await context.newPage();
        const rounds = `/admin/rounds?contestId=${fixture.contestId}`;
        await page.goto(rounds);
        const status = page.locator('#rounds-status-menu');
        await expect(status.locator('> button')).toHaveAccessibleName('Status: All');
        await expect(page.locator('#rounds-type-menu > button')).toHaveAccessibleName('Type: All');
        await expect(page.locator('#rounds-order-menu > button')).toHaveAccessibleName('Order: Oldest first');

        // the binary round's row: its badges; given the inactive look, grey with its link
        const row = page.locator(`tr.round-row[data-round="${fixture.roundBinaryId}"]`);
        const other = page.locator(`tr.round-row[data-round="${fixture.roundRatingId}"]`);
        await expect(row.locator('.wlx-round-type')).toHaveText('Yes/No');
        await expect(other.locator('.wlx-round-type')).toHaveText('Rated');
        await other.evaluate((tr) => tr.classList.add('wlx-round-inactive'));
        const pageBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        const stripe = await colour(page, 'var(--background-color-neutral-subtle)'); // the stripes (codex-theme.css)
        await checkLook(page, mode, { active: row, inactive: other.locator('td').nth(1), backgrounds: [pageBg, stripe], link: true });
        await other.evaluate((tr) => tr.classList.remove('wlx-round-inactive'));

        // a choice opens the page with it; the statistics link carries it
        await status.locator('> button').click();
        await expect(status.locator('.dropdown-menu')).toBeVisible();
        await expect(status.locator('.dropdown-item.active')).toContainText('All');
        if (browserName === 'chromium') await axe(page, testInfo, `rounds ${mode}`);
        await Promise.all([page.waitForURL(/status=active/), status.getByRole('link', { name: /^Active/ }).click()]);
        await expect(status.locator('> button')).toHaveAccessibleName('Status: Active');
        await expect(page.locator('tr.round-row.wlx-round-inactive')).toHaveCount(0);
        await Promise.all([page.waitForURL(/\/roundstat\/.*status=active/),
          row.getByRole('link', { name: /Yes\/No Round/ }).click()]);
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
        // the name and its badges read apart (", " for screen readers only, snippets/roundBadges)
        // (Chromium puts a space around the hidden text, out of the line's flow: "Rating Round , Active , Rated")
        await expect(current).toHaveAccessibleName(/^Rating Round ?, Active ?, Rated$/);
        const otherLink = page.locator(`#round-stat-rounds .page-item[data-round="${fixture.roundBinaryId}"] > .page-link`);
        await otherLink.evaluate((a) => a.classList.add('wlx-round-inactive'));
        const bg = await otherLink.evaluate((a) => getComputedStyle(a).backgroundColor);
        await otherLink.hover();
        const hoverBg = await colour(page, 'var(--wlx-hover-bg)');
        await checkLook(page, mode, { active: current, inactive: otherLink, backgrounds: [bg, hoverBg] });
        await context.close();
      });

    test(`the galleries' round menu: the same look, no filter menus, in ${mode}`, { tag: '@O-07' },
      async ({ contextAs, fixture, baseURL }) => {
        const context = await contextAs('organizer', { viewport: { width: 1440, height: 900 } });
        await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
        const page = await context.newPage();
        await page.goto(galleryPath(fixture.roundBinaryId, 0));
        await expect(page.locator('#rounds-status-menu, #rounds-type-menu, #rounds-order-menu')).toHaveCount(0);
        const items = page.locator('nav .wlx-nav-filters .dropdown-item:has(.wlx-round-badges)');
        const menu = items.first().locator('xpath=ancestor::li[contains(@class, "dropdown")]');
        await menu.locator('> button').click();
        // the fixture's two rounds' items (other specs' rounds may be listed too)
        const item = (roundId) => items.and(page.locator(`[href*="/round/${roundId}/"]`));
        const active = item(fixture.roundBinaryId);
        const other = item(fixture.roundRatingId);
        await expect(active).toHaveCount(1);
        await expect(other).toHaveCount(1);
        await expect(active.locator('.wlx-round-type')).toHaveText('Yes/No');
        await expect(other.locator('.wlx-round-type')).toHaveText('Rated');
        await expect(active).toHaveAccessibleName(/^Yes\/No Round ?, Active ?, Yes\/No$/);
        await other.evaluate((a) => a.classList.add('wlx-round-inactive'));
        const menuBg = await other.evaluate((a) => getComputedStyle(a.closest('.dropdown-menu')).backgroundColor);
        await other.hover();
        const hoverBg = await colour(page, 'var(--wlx-hover-bg)');
        await checkLook(page, mode, { active, inactive: other, backgrounds: [menuBg, hoverBg] });
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
    const items = page.locator('nav .wlx-nav-filters .dropdown-item:has(.wlx-round-badges)');
    const long = "Wiki Loves Monuments 2026 in Ukraine, the national jury's final round of the best photos";
    await items.evaluateAll((as, name) => as.forEach((a) => { a.firstChild.textContent = name; }), long);
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
    // each item's badges stay visible inside the menu
    for (const badge of await menu.locator('.dropdown-menu .wlx-round-type').all()) {
      const b = await badge.boundingBox();
      expect(b.x + b.width).toBeLessThanOrEqual(box.x + box.width);
    }
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
    await menu.locator('.dropdown-item.active').evaluate((el, n) => { el.firstChild.textContent = n; }, name);
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
      const items = page.locator('nav .wlx-nav-filters .dropdown-item:has(.wlx-round-badges)');
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
