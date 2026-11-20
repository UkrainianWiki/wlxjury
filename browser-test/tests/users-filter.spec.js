// Root's all-users page's Type, Country, Year and Role menus (the maintainer's decision of
// 2026-11-19): every account by default, the toggles reading "Type: All", "Year: All years",
// "Role: All"; a choice opens the page with it, every menu's links keep the others, and each item's
// count is the number of accounts its link lists; an axe scan with each menu open (Chromium), in
// light and dark mode, and a phone's width with no horizontal scroll.
// USERS_FILTER_SHOTS=<dir> saves screenshots with the menus open.
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';

const SHOTS = process.env.USERS_FILTER_SHOTS;
const MENUS = ['users-type-menu', 'users-country-menu', 'users-year-menu', 'users-role-menu'];

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


/** The menus' items that read 0, but each menu's first (All) and its chosen item (the maintainer's
  * decision of 2026-11-19: UiSelectionDto.hideEmpty), as "menu: item"; and the menus whose chosen
  * item, if any is marked, is not listed. */
async function emptyItems(page, ids) {
  return page.evaluate((menuIds) => menuIds.flatMap((id) => [...document.querySelectorAll(`#${id} .dropdown-item`)]
    .filter((a, i) => i > 0 && !a.classList.contains('active'))
    .filter((a) => (a.querySelector('.wlx-menu-count')?.textContent ?? '').trim() === '0')
    .map((a) => `${id}: ${a.textContent.trim()}`)), ids);
}

/** The listed accounts' rows. */
const rows = (page) => page.locator('div.table-responsive > table.table-striped > tbody > tr');

/** A menu item's count. */
async function count(item) {
  return Number((await item.locator('.wlx-menu-count').textContent()).replace(/\D/g, ''));
}

for (const mode of ['light', 'dark']) {
  test(`the all-users page's menus in ${mode} mode`, { tag: '@R-03' }, async ({ contextAs, baseURL, browserName }, testInfo) => {
    const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
    await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
    const page = await context.newPage();
    await page.goto('/admin/allUsers');
    const [type, country, year, role] = MENUS.map((id) => page.locator(`#${id}`));
    await expect(type.locator('> button')).toHaveAccessibleName('Type: All');
    await expect(country.locator('> button')).toHaveAccessibleName('Country: All countries');
    await expect(year.locator('> button')).toHaveAccessibleName('Year: All years');
    await expect(role.locator('> button')).toHaveAccessibleName('Role: All');
    // every account: the Role menu's All counts them
    const everyone = await rows(page).count();
    expect(everyone).toBeGreaterThan(1);
    expect(await count(role.locator('.dropdown-item').first())).toBe(everyone);

    // each menu open: its items with their counts; an axe scan
    for (const [menu, label] of [[type, 'type'], [country, 'country'], [year, 'year'], [role, 'role']]) {
      await menu.locator('> button').click();
      await expect(menu.locator('.dropdown-menu')).toBeVisible();
      await expect(menu.locator('.dropdown-item .wlx-menu-count').first()).toBeAttached();
      if (browserName === 'chromium') await axe(page, testInfo, `users ${label} ${mode}`);
      await shot(page, `users-${label}-${mode}`);
      await page.keyboard.press('Escape');
      await expect(menu.locator('.dropdown-menu')).toBeHidden();
    }

    // the choices compose: Juror, then the newest year; each lists what its item counted
    await role.locator('> button').click();
    const juror = role.getByRole('link', { name: /^Juror/ });
    const jurors = await count(juror);
    await Promise.all([page.waitForURL(/\/admin\/allUsers\?role=jury$/), juror.click()]);
    await expect(role.locator('> button')).toHaveAccessibleName('Role: Juror');
    await expect(rows(page)).toHaveCount(jurors);
    expect(jurors).toBeLessThan(everyone);
    await year.locator('> button').click();
    const newest = year.locator('.dropdown-item').nth(1);
    const yearText = (await newest.locator('span:not(.wlx-menu-count)').first().textContent()).trim();
    const inYear = await count(newest);
    await Promise.all([page.waitForURL(new RegExp(`year=${yearText}&role=jury$`)), newest.click()]);
    await expect(year.locator('> button')).toHaveAccessibleName(`Year: ${yearText}`);
    await expect(role.locator('> button')).toHaveAccessibleName('Role: Juror');
    await expect(rows(page)).toHaveCount(inYear);
    // All years again keeps the role
    await year.locator('> button').click();
    await Promise.all([page.waitForURL(/\/admin\/allUsers\?role=jury$/),
      year.getByRole('link', { name: /^All years/ }).click()]);
    await context.close();
  });
}

test("the all-users page's menus fit a phone's width", { tag: '@R-03' }, async ({ contextAs, browserName }, testInfo) => {
  const context = await contextAs('root', { viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto('/admin/allUsers');
  const fits = () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
  expect(await fits(), 'no horizontal scroll of the page').toBe(true);
  await shot(page, 'users-phone');
  for (const id of MENUS) {
    const menu = page.locator(`#${id}`);
    await expect(menu.locator('> button')).toBeVisible();
    await menu.locator('> button').click();
    const list = menu.locator('.dropdown-menu');
    await expect(list).toBeVisible();
    const box = await list.boundingBox();
    expect(box.x, `${id} starts within the window`).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width, `${id} ends within the window`).toBeLessThanOrEqual(390);
    expect(await fits(), `${id}: no horizontal scroll of the page`).toBe(true);
    if (browserName === 'chromium') await axe(page, testInfo, `users ${id} phone`);
    await shot(page, `users-${id}-phone`);
    await page.keyboard.press('Escape');
  }
  await context.close();
});

// a contest's users page: no menus, and its edit links (changed with the menus: a named link, no
// button inside a link) pass the axe scan, in light and dark mode
for (const mode of ['light', 'dark']) {
  test(`a contest's users page in ${mode} mode`, { tag: '@R-03' }, async ({ contextAs, baseURL, browserName, fixture }, testInfo) => {
    const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
    await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
    const page = await context.newPage();
    await page.goto(`/admin/users?contestId=${fixture.contestId}`);
    await expect(rows(page).first()).toBeVisible();
    for (const id of MENUS) await expect(page.locator(`#${id}`)).toHaveCount(0);
    // the table's edit links (the account menu has its own profile link)
    await expect(rows(page).locator('a button')).toHaveCount(0);
    await expect(rows(page).locator('a[href*="/users/edit/"]').first()).toHaveAccessibleName('Edit');
    if (browserName === 'chromium') await axe(page, testInfo, `contest users ${mode}`);
    await shot(page, `contest-users-${mode}`);
    await context.close();
  });
}

test('the all-users page lists no menu item of 0 but the chosen one and All', { tag: '@R-03' }, async ({ contextAs }) => {
  const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await page.goto('/admin/allUsers');
  const params = async (id) => (await page.locator(`#${id} .dropdown-item`).evaluateAll((as) => as.slice(1).map((a) => a.getAttribute('href'))))
    .map((href) => new URL(href, 'http://x').searchParams);
  const types = (await params('users-type-menu')).map((q) => q.get('contestType'));
  const roles = (await params('users-role-menu')).map((q) => q.get('role'));
  // the default, and a type with a role, each choice listed, its count 0 or not
  const paths = ['/admin/allUsers'];
  for (const type of [types[0], types[types.length - 1]]) {
    for (const role of roles) paths.push(`/admin/allUsers?${new URLSearchParams({ contestType: type, role })}`);
  }
  for (const path of paths) {
    await page.goto(path);
    expect(await emptyItems(page, MENUS), `${path}: items of 0`).toEqual([]);
    for (const id of MENUS) await expect(page.locator(`#${id} .dropdown-item.active`), `${path}: ${id}`).toHaveCount(1);
  }
  await context.close();
});
