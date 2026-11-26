// The round tabs (views.RoundTabs, snippets/roundTabs; the maintainer's decision for 26.11.18): one
// component on the round statistics and the contest's authors pages, so the two bars look the same
// (the same tabs, left-aligned); compact, with no Track chip inside a tab (its track is its
// tooltip); grouped by track, each track's name once as a small label before its tabs (Main, then
// the tracks that feed it as they join, then the others), on by default where the contest's rounds
// are on tracks, switched by the Order menu's "Group by track". On the fixture's "Round tracks"
// contest (tracksContest: Q-00 Quality, Qn-00 Quantity, Q-01 Quality, Q-02 the merge on Main,
// Kha-00 a nomination's), in light and dark mode. The galleries' round menu (#round-menu) is
// grouped the same way (the maintainer's decision of 2026-11-26): each track's name a dropdown
// header before its rounds, no chips, the current round marked, the arrow keys over the rounds.
import fs from 'node:fs';
import path from 'node:path';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

// ROUND_TABS_SHOTS=<dir>: the two bars, grouped and not, in both modes (Chromium)
const SHOTS = process.env.ROUND_TABS_SHOTS;

/** WCAG contrast of two opaque rgb() strings. */
function contrast(fg, bg) {
  const lum = (c) => {
    const [x, y, z] = c.match(/[\d.]+/g).slice(0, 3).map(Number)
      .map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * x + 0.7152 * y + 0.0722 * z;
  };
  const [hi, lo] = [lum(fg), lum(bg)].sort((p, q) => q - p);
  return (hi + 0.05) / (lo + 0.05);
}

const LABELS = ['Main', 'Quality', 'Quantity', 'Харків'];
const OLDEST_FIRST = ['Q-00', 'Qn-00', 'Q-01', 'Q-02', 'Kha-00'];

/** A bar's groups: each label and its tabs' names; its tabs' look (the first tab's box and font). */
async function readBar(bar) {
  return bar.evaluate((nav) => {
    const name = (a) => { const c = a.cloneNode(true); c.querySelectorAll('.wlx-round-spoken, .wlx-round-icon').forEach((e) => e.remove()); return c.textContent.trim(); };
    const tabs = [...nav.querySelectorAll('.page-item[data-round] > .page-link')];
    const first = getComputedStyle(tabs[0]);
    const page = document.body.getBoundingClientRect();
    return {
      groups: [...nav.querySelectorAll('.wlx-round-tabs-group')].map((g) =>
        [g.querySelector('.wlx-round-tabs-label').textContent.trim(), [...g.querySelectorAll('.page-link')].map(name)]),
      names: tabs.map(name),
      titles: Object.fromEntries(tabs.map((a) => [name(a), a.getAttribute('title')])),
      chips: nav.querySelectorAll('.wlx-track').length,
      // each group's list named by its label; the tracks said after the tabs' names when ungrouped
      labelled: [...nav.querySelectorAll('.wlx-round-tabs-group')].every((g) =>
        g.querySelector('ul').getAttribute('aria-labelledby') === g.querySelector('.wlx-round-tabs-label').id),
      notes: [...nav.querySelectorAll('.wlx-round-track-note')].map((n) => n.textContent.trim()),
      look: { font: first.fontSize, padding: first.padding, height: Math.round(tabs[0].getBoundingClientRect().height) },
      // left-aligned: the bar's first item (a group's label, a tab) starts at the bar's left edge
      leftGap: Math.round(nav.querySelector('.wlx-round-tabs-label, .page-item').getBoundingClientRect().left - nav.getBoundingClientRect().left),
      inPage: nav.getBoundingClientRect().right <= page.right + 1,
    };
  });
}

for (const mode of ['light', 'dark']) {
  test(`the round tabs: one look on the statistics and the authors pages, grouped by track, in ${mode}`, { tag: '@O-07' },
    async ({ contextAs, fixture, baseURL }) => {
      test.skip(!fixture.tracksContest, "the fixture has no round tracks contest (the guides' screenshot mode)");
      const { contestId, roundIds } = fixture.tracksContest;
      const context = await contextAs('root', { viewport: { width: 1366, height: 900 } });
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      const page = await context.newPage();

      await page.goto(`/roundstat/${roundIds[3]}`);
      const stat = await readBar(page.locator('#round-stat-rounds'));
      expect(stat.groups).toEqual([['Main', ['Q-02']], ['Quality', ['Q-00', 'Q-01']], ['Quantity', ['Qn-00']], ['Харків', ['Kha-00']]]);
      expect(stat.chips, 'no Track chip inside a tab').toBe(0);
      expect(stat.labelled, 'each group a list named by its label').toBe(true);
      expect(stat.notes, 'grouped: the labels say the tracks').toEqual([]);
      expect(stat.titles['Q-01']).toBe('Track: Quality');
      expect(stat.leftGap).toBe(0);
      expect(stat.inPage).toBe(true);
      // the labels: small, secondary, readable (4.5:1 on the page)
      const label = page.locator('#round-stat-rounds .wlx-round-tabs-label').first();
      const { color, bg } = await label.evaluate((l) => ({ color: getComputedStyle(l).color, bg: getComputedStyle(document.body).backgroundColor }));
      expect(contrast(color, bg), `${mode}: a group's label on the page`).toBeGreaterThanOrEqual(4.5);
      if (SHOTS) {
        fs.mkdirSync(SHOTS, { recursive: true });
        await page.locator('#round-stat-rounds').screenshot({ path: path.join(SHOTS, `roundstat-grouped-${mode}.png`) });
      }

      // the authors page: the same bar, "Whole contest" first
      await page.goto(`/contest/${contestId}/authors`);
      const authors = await readBar(page.locator('#authors-rounds'));
      await expect(page.locator('#authors-rounds .wlx-round-tabs-lead .page-link')).toHaveText('Whole contest');
      expect(authors.groups).toEqual(stat.groups);
      expect(authors.look, `${mode}: the same tabs on both pages`).toEqual(stat.look);
      expect(authors.leftGap).toBe(0);
      expect(authors.chips).toBe(0);
      if (SHOTS) await page.locator('#authors-rounds').screenshot({ path: path.join(SHOTS, `authors-grouped-${mode}.png`) });

      // the Order menu's "Group by track": off, the tabs in the menu's order, no labels; on again
      await page.goto(`/roundstat/${roundIds[3]}`);
      const order = page.locator('#rounds-order-menu');
      await order.locator('> button').click();
      const toggle = page.locator('#rounds-group-track');
      await expect(toggle).toHaveAccessibleName(/^Group by track ?, on$/);
      await Promise.all([page.waitForURL(/group=none/), toggle.click()]);
      const flat = await readBar(page.locator('#round-stat-rounds'));
      expect(flat.groups).toEqual([]);
      expect(flat.names).toEqual(OLDEST_FIRST);
      expect(flat.notes, 'ungrouped: each tab says its track to screen readers').toEqual(
        [', track Quality', ', track Quantity', ', track Quality', ', track Main', ', track Харків']);
      expect(flat.look).toEqual(stat.look);
      if (SHOTS) await page.locator('#round-stat-rounds').screenshot({ path: path.join(SHOTS, `roundstat-ungrouped-${mode}.png`) });
      // kept by the tabs' links: another round's statistics stay ungrouped
      await Promise.all([page.waitForURL(new RegExp(`/roundstat/${roundIds[0]}\\?group=none`)),
        page.locator(`#round-stat-rounds .page-item[data-round="${roundIds[0]}"] > .page-link`).click()]);
      await expect(page.locator('#round-stat-rounds .wlx-round-tabs-group')).toHaveCount(0);
      await order.locator('> button').click();
      await expect(toggle).toHaveAccessibleName(/^Group by track ?, off$/);
      await Promise.all([page.waitForURL(/group=track/), toggle.click()]);
      expect((await readBar(page.locator('#round-stat-rounds'))).groups.map((g) => g[0])).toEqual(LABELS);

      await page.goto(`/contest/${contestId}/authors?group=none`);
      expect((await readBar(page.locator('#authors-rounds'))).names).toEqual(OLDEST_FIRST);
      if (SHOTS) await page.locator('#authors-rounds').screenshot({ path: path.join(SHOTS, `authors-ungrouped-${mode}.png`) });
      await context.close();
    });
}

for (const mode of ['light', 'dark']) {
  test(`the galleries' round menu: grouped by track under headers, no chips, in ${mode}`, { tag: '@O-07' },
    async ({ contextAs, fixture, baseURL }) => {
      test.skip(!fixture.tracksContest, "the fixture has no round tracks contest (the guides' screenshot mode)");
      const { roundIds } = fixture.tracksContest;
      const context = await contextAs('root', { viewport: { width: 1366, height: 900 } });
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      const page = await context.newPage();
      await page.goto(galleryPath(roundIds[0], 0));
      const menu = page.locator('#round-menu');
      await menu.locator('> button').click();
      const list = menu.locator('.dropdown-menu');
      await expect(list).toBeVisible();
      const read = () => list.evaluate((ul) => {
        const name = (a) => { const c = a.cloneNode(true); c.querySelectorAll('.wlx-round-spoken, .wlx-round-icon').forEach((e) => e.remove()); return c.textContent.trim(); };
        return {
          groups: [...ul.querySelectorAll('.wlx-menu-group')].map((g) => {
            const h = g.querySelector('h6.dropdown-header');
            const inner = g.querySelector('ul');
            return [h.textContent.trim(), [...inner.querySelectorAll('.dropdown-item')].map(name),
              inner.getAttribute('aria-labelledby') === h.id];
          }),
          names: [...ul.querySelectorAll('.dropdown-item')].map(name),
          chips: ul.querySelectorAll('.wlx-track').length,
          current: [...ul.querySelectorAll('.dropdown-item.active[aria-current=page]')].map(name),
        };
      });
      const grouped = await read();
      expect(grouped.groups).toEqual([['Main', ['Q-02'], true], ['Quality', ['Q-00', 'Q-01'], true],
        ['Quantity', ['Qn-00'], true], ['Харків', ['Kha-00'], true]]);
      expect(grouped.chips, 'no Track chip in the menu').toBe(0);
      expect(grouped.current).toEqual(['Q-00']);
      // the headers readable on the menu (4.5:1)
      const header = list.locator('.dropdown-header').first();
      const { color, bg } = await header.evaluate((h) => ({ color: getComputedStyle(h).color,
        bg: getComputedStyle(h.closest('.dropdown-menu')).backgroundColor }));
      expect(contrast(color, bg), `${mode}: a track's header in the round menu`).toBeGreaterThanOrEqual(4.5);
      // the keyboard: the arrow keys go from round to round in the menu's order, over the headers
      await list.locator('.dropdown-item').first().focus();
      const focused = [];
      for (let i = 0; i < grouped.names.length; i++) {
        focused.push(await page.evaluate(() => {
          const c = document.activeElement.cloneNode(true);
          c.querySelectorAll('.wlx-round-spoken, .wlx-round-icon').forEach((e) => e.remove());
          return c.textContent.trim();
        }));
        await page.keyboard.press('ArrowDown');
      }
      expect(focused).toEqual(['Q-02', 'Q-00', 'Q-01', 'Qn-00', 'Kha-00']);
      if (SHOTS) {
        fs.mkdirSync(SHOTS, { recursive: true });
        await page.screenshot({ path: path.join(SHOTS, `gallery-round-menu-grouped-${mode}.png`) });
      }
      // ?group=none: one list in the menu's order, no headers
      await page.goto(`${galleryPath(roundIds[0], 0)}?group=none`);
      await menu.locator('> button').click();
      await expect(list).toBeVisible();
      const flat = await read();
      expect(flat.groups).toEqual([]);
      expect(flat.names).toEqual(OLDEST_FIRST);
      expect(flat.notes, 'ungrouped: each tab says its track to screen readers').toEqual(
        [', track Quality', ', track Quantity', ', track Quality', ', track Main', ', track Харків']);
      expect(flat.chips).toBe(0);
      await context.close();
    });
}
