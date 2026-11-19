// The large view's side column for all the jurors' votes on the rated round (the maintainer's browser
// check of 26.11.13, 2026-11-19), as an organizer, from the by-rate gallery's top file: the jurors'
// vote list's rank numbers ("1.", "2.", ...) inside the window, not cut at its left edge, at 1280 and
// 1920 px; the score written once (the bold number), the line under it only what the number doesn't
// say, its denominator; the full resolution link opening in a new tab, as the image's link to Commons.
// LARGE_SIDE_SHOTS=<dir> (Chromium) saves the screenshots, whatever the code (also before a fix).
import fs from 'node:fs';
import path from 'node:path';
import { test, expect } from '../lib/fixtures.js';

const SHOTS = process.env.LARGE_SIDE_SHOTS;

/** The large view of the by-rate gallery's top file of the rated round, for all the jurors. */
async function openTopFile(page, fixture) {
  await page.goto(`/byrate/round/${fixture.roundRatingId}/user/0/page/1`);
  const href = await page.locator('a[href*="/large/round/"]').first().getAttribute('href');
  await page.goto(href);
  await expect(page.locator('#rate-number')).toBeVisible();
}

/** Each rank marker's left edge in the window: the item's left edge less the marker's width ("10. "
  * in the item's font), as an outside marker is drawn; numbered from `start` (100: three digits,
  * set on the list as a round of 100 jurors would number it). */
async function markerLefts(page, start = 1) {
  return page.locator('#large-info .large-line ol').first().evaluate((ol, first) => {
    ol.start = first;
    // the class the server gives a list of a hundred jurors or more (juryStat.scala.html)
    ol.classList.toggle('wlx-ranks-3', first + ol.children.length > 100);
    const canvas = document.createElement('canvas').getContext('2d');
    return [...ol.children].map((li, i) => {
      const s = getComputedStyle(li);
      canvas.font = `${s.fontWeight} ${s.fontSize} ${s.fontFamily}`;
      return li.getBoundingClientRect().left - canvas.measureText(`${first + i}. `).width;
    });
  }, start);
}

for (const width of [1280, 1920]) {
  test(`the jurors' vote list's rank numbers stay in the window at ${width} px`, { tag: '@O-08' },
    async ({ contextAs, fixture }) => {
      const context = await contextAs('organizer', { viewport: { width, height: 900 } });
      const page = await context.newPage();
      await openTopFile(page, fixture);
      const lefts = await markerLefts(page);
      expect(lefts.length, 'the jurors\' votes listed').toBeGreaterThan(1);
      for (const [i, x] of lefts.entries()) expect(x, `rank ${i + 1}'s marker`).toBeGreaterThanOrEqual(0);
      // three digits ("100.") fit too
      for (const [i, x] of (await markerLefts(page, 100)).entries()) expect(x, `rank ${100 + i}'s marker`).toBeGreaterThanOrEqual(0);
      await context.close();
    });
}

test('the large view writes the score once, its denominator under it, and opens the full resolution in a new tab', { tag: '@O-08' },
  async ({ contextAs, fixture }) => {
    const context = await contextAs('organizer', { viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    await openTopFile(page, fixture);
    const number = (await page.locator('#rate-number').innerText()).trim();
    const line = page.locator('.wlx-overall-rate');
    // the top file has several jurors' votes: a line under the number
    await expect(line).toHaveCount(1);
    const text = (await line.innerText()).trim();
    // its denominator alone, "(90 / 10 jurors)", not "9.00 (90 / 10 jurors)" after the bold 9
    expect(number).toMatch(/^\d/);
    expect(text.startsWith(number), `the line under the number doesn't repeat it: ${text}`).toBe(false);
    expect(text).toMatch(/^\(\d.* jurors?\)/);
    const full = page.locator('#large-info a', { hasText: /resolution/i }).first();
    await expect(full).toHaveAttribute('target', '_blank');
    await expect(full).toHaveAttribute('rel', /noopener/);
    await expect(page.locator('#mainContent > a[href*="commons.wikimedia.org"]').first()).toHaveAttribute('rel', /noopener/);
    // the full resolution's file is on Commons: its mark, the Commons logo in the link's colour, on
    // the text's line, and the new tab said to screen readers
    const mark = full.locator('.wlx-ext-mark');
    await expect(mark).toHaveAttribute('data-site', 'commons');
    await expect(full).toHaveAccessibleName(/\(opens in a new tab\)$/);
    const m = await mark.evaluate((el) => {
      const a = el.closest('a');
      const r = el.getBoundingClientRect();
      const range = document.createRange();
      // the text before the tail (the no-break space, the mark, the hidden words): its last word
      const before = el.closest('.wlx-ext-tail').previousSibling;
      range.setStart(before, Math.max(0, before.length - 2));
      range.setEnd(before, before.length);
      const last = range.getClientRects();
      return { fill: getComputedStyle(el.querySelector('.wlx-ext-logo')).fill, colour: getComputedStyle(a).color,
        top: r.top, lastTop: last[last.length - 1].top, size: r.height, font: parseFloat(getComputedStyle(a).fontSize) };
    });
    expect(m.fill, "the logo in the link's colour").toBe(m.colour);
    expect(Math.abs(m.top - m.lastTop), "the mark on the line of the text's last word").toBeLessThan(m.font);
    expect(m.size).toBeLessThan(m.font * 1.2);
    // at any width the mark stays on the line of the text's last word (it wrapped alone on Linux's
    // fonts): the side column narrowed step by step, each link's mark against its last word
    // a long title without spaces too, marked as the server marks it (its last character and the
    // mark in the span that doesn't wrap): the rest of it wraps inside the column
    const LONG = 'File:Київ-Печерська-лавра-вид-з-Дніпра-2025-літо-панорама-найкраща.jpg';
    const alone = await page.locator('.large-side').evaluate((side, long) => {
      const model = side.querySelector('#author a.author-commons');
      const title = model.cloneNode(true);
      title.removeAttribute('class');
      title.firstChild.textContent = long.slice(0, -1);
      title.querySelector('.wlx-ext-nowrap').firstChild.textContent = long.slice(-1);
      const line = document.createElement('div');
      line.className = 'large-line';
      line.append(title);
      side.querySelector('.large-line').before(line);
      const found = [];
      for (let w = 100; w <= 320; w += 3) {
        side.style.minWidth = side.style.width = side.style.maxWidth = `${w}px`;
        if (w >= 220 && side.scrollWidth > side.clientWidth) found.push(`${w}px: the column overflows sideways`);
        for (const mark of side.querySelectorAll('.wlx-ext-mark')) {
          const word = mark.closest('.wlx-ext-nowrap').firstChild;
          // the character before the mark, a text node (not a vacuous pass on an element)
          if (!word || word.nodeType !== Node.TEXT_NODE || !word.textContent.trim()) {
            found.push(`${w}px: no character before the mark in ${mark.closest('a').textContent.trim()}`);
            continue;
          }
          const range = document.createRange();
          range.selectNodeContents(word);
          const rects = range.getClientRects();
          const last = rects[rects.length - 1];
          if (Math.abs(mark.getBoundingClientRect().top - last.top) >= last.height) found.push(`${w}px: ${mark.closest('a').textContent.trim()}`);
        }
      }
      side.style.minWidth = side.style.width = side.style.maxWidth = '';
      line.remove();
      return found;
    }, LONG);
    expect(alone, 'a mark alone on a line').toEqual([]);
    // copying a marked link's text copies the text alone: not the no-break space, the mark or the
    // words for screen readers (wlx-ext-tail can't be selected)
    const copied = async (link) => link.evaluate((a) => {
      const range = document.createRange();
      range.selectNodeContents(a);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      const text = selection.toString();
      selection.removeAllRanges();
      const own = a.cloneNode(true);
      own.querySelectorAll('.wlx-ext-tail').forEach((t) => t.remove());
      return { text, own: own.textContent.trim() };
    });
    const author = page.locator('#author a.author-commons');
    await expect(author).toHaveCount(1);
    const a = await copied(author);
    expect(a.text).toBe(a.own);
    expect(a.text).toBe('Commons');
    await context.close();
  });

test('the large view side screenshots', { tag: '@O-08' }, async ({ contextAs, fixture, baseURL, browserName }) => {
  test.skip(!SHOTS || browserName !== 'chromium', 'LARGE_SIDE_SHOTS unset');
  test.setTimeout(180_000); // eight pages, each a large view
  fs.mkdirSync(SHOTS, { recursive: true });
  for (const width of [1280, 1920]) {
    const context = await contextAs('organizer', { viewport: { width, height: 900 } });
    for (const mode of ['light', 'dark']) {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      const page = await context.newPage();
      await openTopFile(page, fixture);
      await page.locator('.large-side').screenshot({ path: path.join(SHOTS, `large-side-${width}-${mode}.png`) });
      // a contest file's large view: its file info (the title's Commons link, the full resolution)
      if (width === 1280) {
        const pageId = new URL(page.url()).pathname.split('/').pop();
        await page.goto(`/contest/${fixture.contestId}/files/${pageId}`);
        await page.locator('.large-side').screenshot({ path: path.join(SHOTS, `contest-file-side-${mode}.png`) });
      }
      await page.close();
    }
    await context.close();
  }
});
