// The contests page's Type, Country, Year and Order menus (the maintainer's request of 2026-11-19):
// the toggles read "Type: All", "Year: 2026" (the latest year of the fixture's contests), a choice
// opens the page with it and every menu's links keep the others; the Country menu's long list
// scrolls inside it, as the galleries' region menu (the fixture has few countries: the list is
// lengthened in the page only, which changes no other test's page); an axe scan with each menu open
// (Chromium), in light and dark mode, and a phone's width with no horizontal scroll.
// CONTESTS_FILTER_SHOTS=<dir> saves screenshots with the menus open.
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';

const SHOTS = process.env.CONTESTS_FILTER_SHOTS;

async function shot(page, name) {
  if (!SHOTS) return;
  fs.mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`) });
}

/** An axe scan: no serious or critical finding. */
async function axe(page, testInfo, name) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const serious = results.violations.filter((v) => ['serious', 'critical'].includes(v.impact))
    .map((v) => ({ rule: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }));
  await testInfo.attach(`axe ${name}`, { body: JSON.stringify(serious, null, 1), contentType: 'application/json' });
  expect(serious, `${name}: serious or critical accessibility findings`).toEqual([]);
}

/** Adds made-up countries to the open Country menu (in the page only), so that it is long. */
async function lengthen(menu) {
  await menu.locator('.dropdown-menu').evaluate((ul) => {
    const last = ul.lastElementChild;
    for (let i = 1; i <= 30; i += 1) {
      const li = last.cloneNode(true);
      const a = li.querySelector('a');
      a.classList.remove('active');
      a.removeAttribute('aria-current');
      a.querySelector('span').textContent = `Country ${String(i).padStart(2, '0')}`;
      ul.append(li);
    }
  });
}

/** The list's contests' names. */
const names = (page) => page.locator('tr.contest-row > td:first-child').allTextContents();

for (const mode of ['light', 'dark']) {
  test(`the contests page's menus in ${mode} mode`, { tag: '@R-01' }, async ({ contextAs, baseURL, browserName }, testInfo) => {
    const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
    await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
    const page = await context.newPage();
    await page.goto('/contests');
    const type = page.locator('#contests-type-menu');
    const country = page.locator('#contests-country-menu');
    const year = page.locator('#contests-year-menu');
    const order = page.locator('#contests-order-menu');
    await expect(type.locator('> button')).toHaveAccessibleName('Type: All');
    await expect(country.locator('> button')).toHaveAccessibleName('Country: All countries');
    await expect(year.locator('> button')).toHaveAccessibleName('Year: 2026');
    await expect(order.locator('> button')).toHaveAccessibleName('Order: Oldest first');
    // the latest year's only: the fixture's 2025 contest isn't listed
    expect(await names(page)).not.toContain('Wiki Loves Monuments');

    // each menu open: its items with their counts; an axe scan
    for (const [menu, label] of [[type, 'type'], [year, 'year'], [order, 'order']]) {
      await menu.locator('> button').click();
      await expect(menu.locator('.dropdown-menu')).toBeVisible();
      if (browserName === 'chromium') await axe(page, testInfo, `contests ${label} ${mode}`);
      await shot(page, `contests-${label}-${mode}`);
      await page.keyboard.press('Escape');
      await expect(menu.locator('.dropdown-menu')).toBeHidden();
    }
    await expect(year.locator('.dropdown-item.active')).toContainText('2026');
    await expect(year.locator('.dropdown-item .wlx-menu-count').first()).toBeAttached();

    // the Country menu: International listed; long, it scrolls inside its box within the window
    await country.locator('> button').click();
    const list = country.locator('.dropdown-menu');
    await expect(list).toBeVisible();
    await expect(list.getByRole('link', { name: /^International/ })).toHaveCount(1);
    await lengthen(country);
    const style = await list.evaluate((ul) => ({ overflowY: getComputedStyle(ul).overflowY, maxHeight: getComputedStyle(ul).maxHeight,
      scroll: ul.scrollHeight, client: ul.clientHeight }));
    expect(style.overflowY).toBe('auto');
    expect(style.maxHeight).not.toBe('none');
    expect(style.scroll, 'the long list scrolls inside the menu').toBeGreaterThan(style.client);
    const box = await list.boundingBox();
    expect(box.y + box.height).toBeLessThanOrEqual(900);
    if (browserName === 'chromium') await axe(page, testInfo, `contests country ${mode}`);
    await shot(page, `contests-country-${mode}`);
    await page.keyboard.press('Escape');

    // the choices compose: All years, then a type, then the newest first
    await year.locator('> button').click();
    await Promise.all([page.waitForURL(/\/contests\?year=all$/), year.getByRole('link', { name: /^All years/ }).click()]);
    await expect(year.locator('> button')).toHaveAccessibleName('Year: All years');
    expect(await names(page)).toContain('Wiki Loves Monuments');
    await type.locator('> button').click();
    await Promise.all([page.waitForURL(/contestType=Wiki\+Loves\+Monuments&year=all$/),
      type.getByRole('link', { name: /^Wiki Loves Monuments/ }).click()]);
    expect(new Set(await names(page))).toEqual(new Set(['Wiki Loves Monuments']));
    await order.locator('> button').click();
    await Promise.all([page.waitForURL(/contestType=Wiki\+Loves\+Monuments&year=all&order=newest$/),
      order.getByRole('link', { name: 'Newest first' }).click()]);
    await expect(order.locator('> button')).toHaveAccessibleName('Order: Newest first');
    await expect(type.locator('> button')).toHaveAccessibleName('Type: Wiki Loves Monuments');
    // By country, A to Z only: the third choice, the other menus' choices kept
    await order.locator('> button').click();
    await expect(order.locator('.dropdown-item')).toHaveCount(3);
    await Promise.all([page.waitForURL(/contestType=Wiki\+Loves\+Monuments&year=all&order=country$/),
      order.getByRole('link', { name: 'By country' }).click()]);
    await expect(order.locator('> button')).toHaveAccessibleName('Order: By country');
    await context.close();
  });
}

test("the contests page's menus fit a phone's width", { tag: '@R-01' }, async ({ contextAs, browserName }, testInfo) => {
  const context = await contextAs('root', { viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto('/contests');
  const fits = () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
  expect(await fits(), 'no horizontal scroll of the page').toBe(true);
  await shot(page, 'contests-phone');
  for (const id of ['contests-type-menu', 'contests-country-menu', 'contests-year-menu', 'contests-order-menu']) {
    const menu = page.locator(`#${id}`);
    await expect(menu.locator('> button')).toBeVisible();
    await menu.locator('> button').click();
    const list = menu.locator('.dropdown-menu');
    await expect(list).toBeVisible();
    if (id === 'contests-country-menu') await lengthen(menu);
    const box = await list.boundingBox();
    expect(box.x, `${id} starts within the window`).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width, `${id} ends within the window`).toBeLessThanOrEqual(390);
    expect(await fits(), `${id}: no horizontal scroll of the page`).toBe(true);
    if (id === 'contests-country-menu') {
      if (browserName === 'chromium') await axe(page, testInfo, 'contests country phone');
      await shot(page, 'contests-country-phone');
    }
    await page.keyboard.press('Escape');
  }
  await context.close();
});
