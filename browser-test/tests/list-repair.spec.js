// Root's file list repair pages (services.FileListRepair, V114) on UiShotsServer's shared untitled
// list (services.FileListRepairFixture): the overview lists contest A on the untitled row and the
// contest already on its own row; A's repair page pre-unticks its whole-list round, counts 5 files
// that stay and 15 that leave, and its "View gallery" link opens the files gallery's preview of the
// files that stay; an axe-core scan of both pages in Chromium. Nothing is confirmed (the fixture is
// shared by the engines). LIST_REPAIR_SHOTS=<dir> (Chromium) saves the screenshots at 1280 px.
import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';

const SHOTS = process.env.LIST_REPAIR_SHOTS;

/** An axe scan: no serious or critical finding (as health.spec.js's). */
async function axe(page, testInfo, name) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const serious = results.violations.filter((v) => ['serious', 'critical'].includes(v.impact))
    .map((v) => ({ rule: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }));
  await testInfo.attach(`axe ${name}`, { body: JSON.stringify(serious, null, 1), contentType: 'application/json' });
  expect(serious, `${name}: serious or critical accessibility findings`).toEqual([]);
}

async function shot(page, browserName, name) {
  if (!SHOTS || browserName !== 'chromium') return;
  fs.mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`), fullPage: true });
}

test('root previews a shared untitled list\'s repair, with the files gallery\'s preview', { tag: '@R-07' },
  async ({ contextAs, fixture, browserName }, testInfo) => {
    test.skip(!fixture.listRepair, 'the fixture has no repair case (the guides\' screenshot mode)');
    const { contestId, ownContestId, blankRow, wholeRound } = fixture.listRepair;
    const context = await contextAs('root', { viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();

    await page.goto('/admin/file-lists');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Repair file lists');
    const row = page.locator(`tr.list-repair-contest[data-contest="${contestId}"]`);
    await expect(row).toHaveAttribute('data-row', String(blankRow));
    await expect(row).toContainText('untitled');
    await expect(page.locator(`tr.list-repair-own-contest[data-contest="${ownContestId}"]`)).toBeVisible();
    if (browserName === 'chromium') await axe(page, testInfo, 'file lists overview');
    await shot(page, browserName, 'list-repair-overview');

    await row.getByRole('link', { name: /^Repair the file list of/ }).click();
    await expect(page).toHaveURL((url) => url.pathname === `/admin/contests/${contestId}/file-list/repair`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Repair file list');
    const whole = page.locator(`tr.list-repair-round[data-round="${wholeRound}"]`);
    await expect(whole).toHaveAttribute('data-mixed', 'true');
    await expect(whole.getByRole('checkbox')).not.toBeChecked();
    await expect(page.locator('#list-repair-stays')).toHaveAttribute('data-count', '5');
    await expect(page.locator('#list-repair-leaves')).toHaveAttribute('data-count', '15');
    await expect(page.locator('#list-repair-confirm-button')).toBeVisible();
    if (browserName === 'chromium') await axe(page, testInfo, 'file list repair page');
    await shot(page, browserName, 'list-repair-page');

    await page.locator('#list-repair-stays-gallery').click();
    await expect(page.locator('#repair-side')).toHaveAttribute('data-side', 'stays');
    await expect(page.locator('#gallery img')).toHaveCount(5);
    await page.locator('#repair-back').click();
    await expect(page).toHaveURL((url) => url.pathname === `/admin/contests/${contestId}/file-list/repair`);
    await context.close();
  });
