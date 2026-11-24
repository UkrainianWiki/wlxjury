// A juror votes in the binary and the rated round, in the large view by key and by click and in
// the gallery by click; after a vote in the large view, Back shows the stored vote (the pages
// are no-store), as a fresh load of the file does. Moved from scripts/ui_checks.py
// (vote_then_back), with the buttons found by role and name instead of their classes (U1).
// The large-view tests run in the routeless browser (lib/fixtures.js routelessAs): the guard's
// routes turn the browser's cache off and Playwright's Chromium has no back/forward cache, so
// with them a page without no-store would pass too. Without no-store they fail in Firefox. In
// Chromium, Back restores the no-store large view from the back/forward cache with the old vote
// (U23): there they assert just that (the page restored, the old vote shown, a fresh load showing
// the stored one), so they fail once Chromium stops doing it, the signal to revisit U23. Only
// off CI, when Chromium says (over CDP) that it dropped the cache for the host's critical memory
// pressure, as in a full parallel run on a busy 16 GB Windows host, do they check the reloaded
// page instead, as in the other engines (U23 annotated "not checked", the reasons on the
// console); on CI, or for any other reason, they fail, with Chromium's reasons in the message.
// Each engine votes as its own juror (lib/server.js), so the engines can share a server; each
// test reads the file's vote first and casts one that changes it, so a rerun votes again.
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath, one } from '../lib/actions.js';
import { png } from '../lib/placeholder.js';

test.use({ role: 'juror' });

const selectButton = (page) => page.getByRole('button', { name: /^Select image/ });
const rejectButton = (page) => page.getByRole('button', { name: /^Reject image/ });
/** A tile pointed at away from its buttons (around its middle) and chips (at its foot). */
const TILE_CORNER = { position: { x: 4, y: 4 } };
/** Bootstrap's border width (a btn's, the navigation buttons'), as computed: "1px". */
const bootstrapBorder = (page) => page.evaluate(() => {
  const probe = document.createElement('span');
  probe.style.borderTop = 'var(--bs-border-width) solid';
  document.body.append(probe);
  const width = getComputedStyle(probe).borderTopWidth;
  probe.remove();
  return width;
});

/** The binary round's vote the large view shows, by its pressed button: 1 (✓ pressed), -1 (✗
  * pressed), 0 (neither); both buttons always there (the maintainer's notes of 2026-11-16). */
async function binaryVote(page) {
  expect(await selectButton(page).count(), 'the large view has its ✓').toBe(1);
  expect(await rejectButton(page).count(), 'the large view has its ✗').toBe(1);
  const select = await selectButton(page).getAttribute('aria-pressed');
  const reject = await rejectButton(page).getAttribute('aria-pressed');
  expect(select === 'true' && reject === 'true', 'one button pressed at most').toBe(false);
  return select === 'true' ? 1 : reject === 'true' ? -1 : 0;
}

/** The rated round's rate the large view says ("Rating: 4"; 0 not rated). */
async function ratedVote(page) {
  const text = await (await one(page.getByText(/^(Rating: [\d.]+|Not rated)$/))).textContent();
  const rate = text.match(/Rating: ([\d.]+)/);
  return rate ? Number(rate[1]) : 0;
}

/** Waits for the vote's POST and the next file's page it leads to, after `act`; returns the POST's URL. */
async function voteAndMove(page, act) {
  const from = page.url();
  const posted = page.waitForRequest((r) => r.method() === 'POST' && /\/select\/-?\d+/.test(r.url()));
  await act();
  const request = await posted;
  await page.waitForURL((u) => u.href !== from);
  return new URL(request.url());
}

/** Waits for the vote's POST and the page it leads to, after `act`, whichever file that is;
  * returns the POST's URL. */
async function voteAndLoad(page, act) {
  const posted = page.waitForRequest((r) => r.method() === 'POST' && /\/select\/-?\d+/.test(r.url()));
  const loaded = page.waitForEvent('load');
  await act();
  const request = await posted;
  await loaded;
  return new URL(request.url());
}

/** Chromium's reason for dropping its whole back/forward cache under the host's critical memory
  * pressure (the 16 GB Windows host of a full parallel run, its free memory low): nothing about
  * the page. Accepted locally only: on CI it fails as any other reason. */
const MEMORY_PRUNED = 'CacheLimitPrunedOnCriticalMemoryPressure';

/** Back after the vote: the voted file, showing the stored vote `expected`, as a fresh load of
  * it does; in Chromium (U23), the page restored from the back/forward cache with the vote it had
  * `before`, unless, off CI, Chromium says it dropped the cache only for the host's critical memory
  * pressure: then the page is loaded again, and shows `expected` as in the other engines. */
async function expectBackShowsVote(page, url, read, expected, before, browserName) {
  // 'commit': a page the back/forward cache restores fires no load event
  await page.goBack({ waitUntil: 'commit' });
  await expect(page).toHaveURL(url);
  let restored = false;
  if (browserName === 'chromium') {
    // not restored: Chromium's reasons (largeView's CDP session) in the message
    let outcome;
    await expect.poll(async () => {
      const shown = await page.evaluate(() => window.wlxPageshow);
      const reasons = notRestored.get(page) || [];
      if (shown && shown.persisted) outcome = 'restored';
      else if (!process.env.CI && shown && reasons.length && reasons.every((r) => r === MEMORY_PRUNED)) outcome = 'pruned';
      else outcome = JSON.stringify({ ...shown, notRestored: reasons });
      return outcome;
    }, { message: 'U23: restored from the back/forward cache' }).toMatch(/^(restored|pruned)$/);
    restored = outcome === 'restored';
    if (!restored) console.warn(`U23 not checked (${test.info().title}): Chromium didn't restore the page: ${notRestored.get(page).join(', ')}`);
    test.info().annotations.push({ type: 'U23', description: restored
      ? 'Chromium restores the no-store large view from its back/forward cache (UI practices §9)'
      : `not checked: Chromium dropped its back/forward cache for the host's memory pressure (${notRestored.get(page).join(', ')})` });
    if (restored) expect(await read(page), 'U23: the restored page shows the vote from before').toBe(before);
  }
  if (!restored) {
    // the reloaded page parsed: its buttons are there (expect.poll doesn't retry a reader that throws)
    await page.waitForLoadState('domcontentloaded');
    await expect.poll(() => read(page), { message: 'Back shows the stored vote' }).toBe(expected);
  }
  const fresh = await page.context().newPage();
  await fresh.goto(url);
  expect(await read(fresh), 'a fresh load shows the stored vote').toBe(expected);
  await fresh.close();
}

/** Chromium's reasons for not restoring a page from the back/forward cache, by page (CDP). */
const notRestored = new WeakMap();

/** The juror's large view of `url`, in the routeless browser. */
async function largeView(routelessAs, url, browserName) {
  const context = await routelessAs('juror');
  // each pageshow: persisted is true for a page the back/forward cache brought back
  await context.addInitScript(() => {
    window.addEventListener('pageshow', (e) => { window.wlxPageshow = { persisted: e.persisted }; });
  });
  const page = await context.newPage();
  if (browserName === 'chromium') {
    const cdp = await context.newCDPSession(page);
    await cdp.send('Page.enable');
    cdp.on('Page.backForwardCacheNotUsed', (e) => notRestored.set(page, e.notRestoredExplanations.map((r) => r.reason)));
  }
  await page.goto(url);
  return page;
}

/** How far the glyph of the vote button `button` is from its content box's centre, { dx, dy } in
  * CSS px, the glyph as drawn: its pixels in a screenshot of the button, those at least half way
  * from the button's background (the commonest colour inside its border) to the glyph's colour
  * (its icon's `color`), their bounding box's centre. Not the icon's box: a font's glyph sits in it by
  * the font's metrics and the line's baseline, which is what put the marks off centre. The
  * screenshot's pixels are the device's (WebKit's Desktop Safari has 2 to a CSS pixel). */
async function glyphOffset(page, button) {
  const g = await button.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const c = getComputedStyle(el);
    const px = (v) => parseFloat(v) || 0;
    return {
      outer: r.toJSON(),
      left: r.left + px(c.borderLeftWidth) + px(c.paddingLeft), right: r.right - px(c.borderRightWidth) - px(c.paddingRight),
      top: r.top + px(c.borderTopWidth) + px(c.paddingTop), bottom: r.bottom - px(c.borderBottomWidth) - px(c.paddingBottom),
      // inside the border, less the pressed button's ring of the glyph's colour (2 px, main.css
      // --wlx-btn-inset) and a pixel for the ring's rounded corners: where the glyph is looked for
      inner: {
        left: r.left + px(c.borderLeftWidth) + 3, right: r.right - px(c.borderRightWidth) - 3,
        top: r.top + px(c.borderTopWidth) + 3, bottom: r.bottom - px(c.borderBottomWidth) - 3,
      },
      glyph: getComputedStyle(el.querySelector('i')).color,
    };
  });
  const x0 = Math.floor(g.outer.left);
  const y0 = Math.floor(g.outer.top);
  const clip = { x: x0, y: y0, width: Math.ceil(g.outer.right) - x0, height: Math.ceil(g.outer.bottom) - y0 };
  const shot = await page.screenshot({ clip, animations: 'disabled' });
  const ink = await page.evaluate(async ({ png, inner, width, glyph }) => {
    const image = new Image();
    image.src = `data:image/png;base64,${png}`;
    await image.decode();
    // the device's pixels to a CSS pixel; the box in the screenshot's pixels, those wholly inside it
    const scale = image.width / width;
    const box = {
      left: Math.ceil(inner.left * scale), right: Math.floor(inner.right * scale),
      top: Math.ceil(inner.top * scale), bottom: Math.floor(inner.bottom * scale),
    };
    // a page's canvas, not an OffscreenCanvas (WebKit on Windows has none)
    const canvas = Object.assign(document.createElement('canvas'), { width: image.width, height: image.height });
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, image.width, image.height).data;
    const at = (x, y) => Array.from(data.subarray((y * image.width + x) * 4, (y * image.width + x) * 4 + 3));
    const counts = new Map();
    for (let y = box.top; y < box.bottom; y++) {
      for (let x = box.left; x < box.right; x++) {
        const key = at(x, y).join();
        counts.set(key, (counts.get(key) || 0) + 1);
      }
    }
    const bg = [...counts.entries()].sort((a, b) => b[1] - a[1])[0][0].split(',').map(Number);
    const fg = glyph.match(/[\d.]+/g).slice(0, 3).map(Number);
    const v = fg.map((f, i) => f - bg[i]);
    const vv = v.reduce((s, d) => s + d * d, 0);
    let [left, right, top, bottom] = [Infinity, -Infinity, Infinity, -Infinity];
    for (let y = box.top; y < box.bottom; y++) {
      for (let x = box.left; x < box.right; x++) {
        const p = at(x, y);
        if (p.reduce((s, c, i) => s + (c - bg[i]) * v[i], 0) / vv >= 0.5) {
          [left, right, top, bottom] = [Math.min(left, x), Math.max(right, x + 1), Math.min(top, y), Math.max(bottom, y + 1)];
        }
      }
    }
    // back in CSS pixels
    return { left: left / scale, right: right / scale, top: top / scale, bottom: bottom / scale, vv };
  }, {
    png: shot.toString('base64'),
    glyph: g.glyph,
    width: clip.width,
    // in CSS pixels from the screenshot's corner
    inner: { left: g.inner.left - x0, right: g.inner.right - x0, top: g.inner.top - y0, bottom: g.inner.bottom - y0 },
  });
  expect(ink.vv, 'the glyph\'s colour apart from the button\'s background').toBeGreaterThan(0);
  expect(ink.right - ink.left, 'the glyph found in the button').toBeGreaterThan(4);
  return {
    dx: x0 + (ink.left + ink.right) / 2 - (g.left + g.right) / 2,
    dy: y0 + (ink.top + ink.bottom) / 2 - (g.top + g.bottom) / 2,
  };
}

/** WCAG's contrast ratios against the photo beside the tile button `button` (5 px above its
  * middle) of what edges it, as drawn: the ring just outside its border, its border, and its
  * backing (4 px inside its top edge, above the glyph); and that photo's colour. Each read at
  * the middle of its CSS pixel, in the screenshot's device pixels (2 to one in WebKit). */
async function edgeContrast(page, button) {
  const b = await button.boundingBox();
  const x0 = Math.floor(b.x) - 8;
  const y0 = Math.floor(b.y) - 8;
  const width = Math.ceil(b.width) + 16;
  const shot = await page.screenshot({ clip: { x: x0, y: y0, width, height: Math.ceil(b.height) + 16 }, animations: 'disabled' });
  return page.evaluate(async ({ png, x, top, width }) => {
    const image = new Image();
    image.src = `data:image/png;base64,${png}`;
    await image.decode();
    const scale = image.width / width;
    // a page's canvas, not an OffscreenCanvas (WebKit on Windows has none)
    const canvas = Object.assign(document.createElement('canvas'), { width: image.width, height: image.height });
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    // the colour at CSS row `y` (from the screenshot's top), in the middle of its pixel
    const at = (y) => Array.from(context.getImageData(Math.floor(x * scale), Math.floor((y + 0.5) * scale), 1, 1).data.subarray(0, 3));
    const lum = (rgb) => rgb.map((v) => { const c = v / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; })
      .reduce((sum, c, i) => sum + c * [0.2126, 0.7152, 0.0722][i], 0);
    const ratio = (a, b) => { const [l1, l2] = [lum(a), lum(b)].sort((p, q) => q - p); return (l1 + 0.05) / (l2 + 0.05); };
    const photo = at(top - 5);
    return { photo, ring: ratio(at(top - 1), photo), border: ratio(at(top), photo), backing: ratio(at(top + 4), photo) };
  }, { png: shot.toString('base64'), x: b.x + b.width / 2 - x0, top: b.y - y0, width });
}

test.describe('a juror votes', () => {
  test('in the binary round\'s large view by key, then Back shows the vote', { tag: ['@J-21', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    const page = await largeView(routelessAs, url, browserName);
    const before = await binaryVote(page);
    // Space selects unless selected; Delete rejects
    const [key, expected] = before === 1 ? ['Delete', -1] : ['Space', 1];
    const posted = await voteAndMove(page, () => page.keyboard.press(key));
    expect(posted.origin, 'the vote posts to this host').toBe(new URL(baseURL).origin);
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${juror.pageId}/select/${expected}$`));
    await expectBackShowsVote(page, url, binaryVote, expected, before, browserName);
  });

  test('in the binary round\'s large view by its button, then Back shows the vote', { tag: ['@J-20', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    const page = await largeView(routelessAs, url, browserName);
    const before = await binaryVote(page);
    const [button, expected] = before === -1 ? [selectButton(page), 1] : [rejectButton(page), -1];
    const posted = await voteAndMove(page, () => button.click());
    expect(posted.pathname).toMatch(new RegExp(`/select/${expected}$`));
    await expectBackShowsVote(page, url, binaryVote, expected, before, browserName);
  });

  test('in the rated round\'s large view by key, then Back shows the rate', { tag: ['@J-21', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    const page = await largeView(routelessAs, url, browserName);
    const before = await ratedVote(page);
    const expected = before === 3 ? 4 : 3;
    const posted = await voteAndMove(page, () => page.keyboard.press(String(expected)));
    expect(posted.origin).toBe(new URL(baseURL).origin);
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${juror.ratedPageId}/select/${expected}$`));
    await expectBackShowsVote(page, url, ratedVote, expected, before, browserName);
  });

  test('in the rated round\'s large view by a star, then Back shows the rate', { tag: ['@J-20', '@J-24'] }, async ({ routelessAs, fixture, baseURL, browserName }) => {
    const { juror } = fixture.accounts;
    const url = baseURL + largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    const page = await largeView(routelessAs, url, browserName);
    const before = await ratedVote(page);
    const expected = before === 6 ? 7 : 6;
    // raty's stars: <i data-alt="n"> in the stars' element (no role: see the accessibility report)
    const star = page.locator(`#rate-image [data-alt="${expected}"]`);
    await expect(star).toHaveCount(1);
    const posted = await voteAndMove(page, () => star.click());
    expect(posted.pathname).toMatch(new RegExp(`/select/${expected}$`));
    await expectBackShowsVote(page, url, ratedVote, expected, before, browserName);
  });

  // The juror's large view of a yes/no file (the maintainer's notes of 2026-11-16): every button
  // of its row (gallery, previous, next, ✓, ✗) with its own complete border, all four sides one
  // colour at rest and pointed at, none overlapping another (a Bootstrap button group shared their
  // borders); the ✓ and ✗ with the navigation buttons' border width, height and radius (the
  // maintainer's notes on 26.11.11: their 3 px border was much heavier than the buttons beside
  // them), the border in the vote's colour, filled with it pointed at; at rest, pointed at and
  // focused, in both colour modes
  test('the large view\'s buttons each have their own border, the ✓ and ✗ the navigation buttons\' weight', { tag: '@J-20' }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(largePath(fixture.roundBinaryId, juror.id, juror.pageId));
    const nav = page.locator('.large-nav [data-nav=gallery]');
    const buttons = page.locator('.large-nav > a');
    const votes = page.locator('.large-nav a[data-vote-url]');
    await expect(nav).toBeVisible();
    await expect(votes).toHaveCount(2);
    const style = (locator) => locator.evaluate((el) => {
      const c = getComputedStyle(el);
      const sides = ['Top', 'Right', 'Bottom', 'Left'];
      return {
        colours: sides.map((s) => c[`border${s}Color`]), widths: sides.map((s) => c[`border${s}Width`]),
        height: el.getBoundingClientRect().height, radius: c.borderTopLeftRadius, background: c.backgroundColor,
        check: el.querySelector('.bi-check-lg') !== null, glyph: getComputedStyle(el.querySelector('i')).color,
        shadow: c.boxShadow, text: getComputedStyle(document.body).color, page: getComputedStyle(document.body).backgroundColor,
      };
    });
    const overlap = (a, b) => a.x < b.x + b.width - 0.5 && b.x < a.x + a.width - 0.5
      && a.y < b.y + b.height - 0.5 && b.y < a.y + a.height - 0.5;
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      for (const state of ['at rest', 'pointed at', 'focused']) {
        await page.mouse.move(1, 1);
        const reference = await style(nav);
        const count = await buttons.count();
        for (let i = 0; i < count; i++) {
          const button = buttons.nth(i);
          if (state === 'pointed at') await button.hover();
          if (state === 'focused') await button.focus();
          await page.waitForTimeout(250);
          const b = await style(button);
          const isVote = (await button.getAttribute('data-vote-url')) !== null;
          const where = `${mode} mode, ${state}, button ${i + 1}${isVote ? (b.check ? ' (✓)' : ' (✗)') : ''}`;
          expect(new Set(b.colours).size, `${where}: its four border colours equal (${b.colours})`).toBe(1);
          expect(new Set(b.widths).size, `${where}: its four border widths equal`).toBe(1);
          expect(Math.abs(b.height - reference.height), `${where}: height`).toBeLessThanOrEqual(1);
          expect(b.radius, `${where}: radius`).toBe(reference.radius);
          // no other button covers it, nor it them, in any state
          const boxes = await buttons.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()));
          for (let j = 0; j < boxes.length; j++) {
            for (let k = j + 1; k < boxes.length; k++) {
              expect(overlap(boxes[j], boxes[k]), `${where}: buttons ${j + 1} and ${k + 1} apart`).toBe(false);
            }
          }
          if (isVote) {
            expect(b.widths[0], `${where}: the navigation buttons' border width`).toBe(reference.widths[0]);
            expect(b.colours[0], `${where}: its border in the vote's colour, not the navigation's grey`).not.toBe(reference.colours[0]);
            const pressed = (await button.getAttribute('aria-pressed')) === 'true';
            if (state === 'focused') {
              // the two-tone focus ring: the page's colour, then its text colour, 4 px out
              expect(b.shadow, `${where}: the focus ring in the text colour`).toContain(`${b.text} 0px 0px 0px 4px`);
              expect(b.shadow, `${where}: inside it a ring of the page's colour`).toMatch(/0px 0px 0px 2px/);
            }
            if (state === 'pointed at' || pressed) {
              expect(b.background, `${where}: filled with the vote's colour`).toBe(b.colours[0]);
              expect(b.glyph, `${where}: the glyph apart from the fill`).not.toBe(b.colours[0]);
            } else {
              expect(b.glyph, `${where}: the glyph in the vote's colour`).toBe(b.colours[0]);
            }
          }
          await page.mouse.move(1, 1);
          await page.evaluate(() => document.activeElement && document.activeElement.blur());
        }
      }
    }
  });

  // A tile's ✓ and ✗ as the large view's: the border in the vote's colour, Bootstrap's width (the
  // maintainer's notes on 26.11.11), its radius, filled with the vote's colour pointed at (the
  // notes of 2026-11-16), both modes
  test('a tile\'s ✓ and ✗ are filled with the vote\'s colour pointed at', { tag: '@J-16' }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const select = page.getByRole('button', { name: 'Selected' }).first();
    await expect(select).toBeVisible();
    const tile = select.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " ug-tile ")][1]');
    const reject = tile.getByRole('button', { name: 'Rejected' });
    const style = (locator) => locator.evaluate((el) => {
      const c = getComputedStyle(el);
      return {
        border: c.borderTopColor, width: c.borderTopWidth, background: c.backgroundColor,
        radius: c.borderTopLeftRadius, glyph: getComputedStyle(el.querySelector('i')).color,
      };
    });
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      for (const button of [select, reject]) {
        await tile.hover(TILE_CORNER);
        await page.waitForTimeout(200);
        const pressed = (await button.getAttribute('aria-pressed')) === 'true';
        const rest = await style(button);
        expect(rest.width, `${mode}: Bootstrap's border width`).toBe(await bootstrapBorder(page));
        expect(rest.radius, `${mode}: the large view's radius`).toBe('2px');
        if (!pressed) expect(rest.background, `${mode}: not filled at rest`).not.toBe(rest.border);
        await button.hover();
        await page.waitForTimeout(250);
        const hover = await style(button);
        expect(hover.background, `${mode}: filled with the vote's colour pointed at`).toBe(hover.border);
        expect(hover.glyph, `${mode}: the glyph apart from the fill`).not.toBe(hover.border);
      }
      const [s, r] = [await style(select), await style(reject)];
      expect(s.border, `${mode}: ✓ and ✗ in their own colours`).not.toBe(r.border);
    }
  });

  // The large view undoes a vote as a tile does (the maintainer's notes of 2026-11-16): a file
  // selected on its tile shows its ✓ pressed in the large view; a click on it posts rate 0 and the
  // view moves on to the next file, as after any vote (the maintainer's decision); back at the file
  // it is unrated, neither button pressed, and so is its tile
  test('in the binary round\'s large view, its pressed ✓ undoes the vote', { tag: ['@J-16', '@J-20'] }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const tileSelect = () => page.getByRole('button', { name: 'Selected' }).first();
    await expect(tileSelect()).toBeVisible();
    if ((await tileSelect().getAttribute('aria-pressed')) === 'true') {
      // selected already: unselect it on its tile first, so that the selection below is this test's
      const undone = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
      await tileSelect().click();
      expect((await undone).status()).toBe(200);
    }
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await tileSelect().click();
    const voted = await response;
    expect(voted.status()).toBe(200);
    const pageId = new URL(voted.url()).pathname.match(/\/pageid\/(\d+)\/select\/1$/)[1];
    const url = largePath(fixture.roundBinaryId, juror.id, pageId);
    await page.goto(url);
    expect(await binaryVote(page), 'the tile\'s vote, pressed').toBe(1);
    const posted = await voteAndMove(page, () => selectButton(page).click());
    expect(posted.pathname).toMatch(new RegExp(`/pageid/${pageId}/select/0$`));
    expect(new URL(page.url()).pathname, 'on to the next file').not.toBe(url);
    expect(new URL(page.url()).pathname).toMatch(/\/large\/round\//);
    await page.goto(url);
    expect(await binaryVote(page), 'back at the file: unrated').toBe(0);
    await expect(selectButton(page)).toHaveAttribute('aria-pressed', 'false');
    await expect(rejectButton(page)).toHaveAttribute('aria-pressed', 'false');
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    await expect(tileSelect()).toHaveAttribute('aria-pressed', 'false');
  });

  // The large view's ✓ and ✗ always both, ✓ first, as the tiles' (the maintainer's notes of
  // 2026-11-16: one or two buttons by the file's vote was confusing): for an unrated, a selected
  // and a rejected file, the file's vote pressed
  test('the large view has both ✓ and ✗ for an unrated, a selected and a rejected file', { tag: '@J-20' }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    const url = largePath(fixture.roundBinaryId, juror.id, juror.pageId);
    const seen = [];
    for (const rate of [0, 1, -1]) {
      await page.goto(url);
      const now = await binaryVote(page);
      if (now !== rate) {
        // the button that gives `rate`: the pressed one undoes, the other gives its vote
        const button = rate === 0 ? (now === 1 ? selectButton(page) : rejectButton(page))
          : rate === 1 ? selectButton(page) : rejectButton(page);
        await voteAndLoad(page, () => button.click());
        await page.goto(url);
      }
      const order = await page.locator('.large-nav a[data-vote-url]').evaluateAll((els) => els.map((el) =>
        `${el.querySelector('.bi-check-lg') ? '✓' : '✗'}${el.getAttribute('aria-pressed') === 'true' ? '*' : ''}`));
      seen.push(order.join(' '));
    }
    expect(seen).toEqual(['✓ ✗', '✓* ✗', '✓ ✗*']);
  });

  // A tile's zoom above its ✓ and ✗, centred over them, the ✓ and ✗ side by side at one height,
  // their glyphs level (the maintainer's note of 2026-11-16: the ✓ sat 8 px above the ✗;
  // unitegallery.js placed them so), each button the size of the large view's vote buttons (the
  // maintainer's notes and decision on 26.11.11: one button size everywhere, 24 × 24 px or more,
  // the zoom above), pointed at and not, in both colour modes; the ✓ to the left of the ✗
  test('a tile\'s ✓ and ✗ buttons are level under its zoom, the large view\'s size', { tag: '@J-16' }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(largePath(fixture.roundBinaryId, juror.id, juror.pageId));
    const large = await page.locator('.large-nav a[data-vote-url]').first().boundingBox();
    expect(large.width, 'the large view\'s vote button at least 24 px wide').toBeGreaterThanOrEqual(24);
    expect(large.height, 'the large view\'s vote button at least 24 px high').toBeGreaterThanOrEqual(24);
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const select = page.getByRole('button', { name: 'Selected' }).first();
    await expect(select).toBeVisible();
    const tile = select.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " ug-tile ")][1]');
    const reject = tile.getByRole('button', { name: 'Rejected' });
    const zoom = tile.locator('.ug-icon-zoom');
    const boxes = async () => ({
      zoom: await zoom.boundingBox(), select: await select.boundingBox(), reject: await reject.boundingBox(),
      check: await select.locator('i.bi-check-lg').boundingBox(), cross: await reject.locator('i.bi-x-lg').boundingBox(),
    });
    const centreY = (b) => b.y + b.height / 2;
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      for (const state of ['at rest', 'pointed at']) {
        if (state === 'pointed at') await tile.hover(TILE_CORNER); else await page.mouse.move(1, 1);
        await page.waitForTimeout(300);
        const b = await boxes();
        const where = `${mode} mode, ${state}`;
        for (const name of ['zoom', 'select', 'reject']) {
          expect(Math.abs(b[name].width - large.width), `${where}: the ${name} button as wide as the large view's`).toBeLessThanOrEqual(1);
          expect(Math.abs(b[name].height - large.height), `${where}: the ${name} button as high as the large view's`).toBeLessThanOrEqual(1);
        }
        expect(Math.abs(b.select.y - b.reject.y), `${where}: the ✓'s and ✗'s tops`).toBeLessThanOrEqual(1);
        expect(Math.abs(centreY(b.check) - centreY(b.cross)), `${where}: the glyphs' centres`).toBeLessThanOrEqual(1);
        expect(Math.abs(b.check.height - b.cross.height), `${where}: the glyphs' heights`).toBeLessThanOrEqual(1);
        expect(b.select.x + b.select.width, `${where}: the ✓ left of the ✗`).toBeLessThan(b.reject.x);
        expect(b.zoom.y + b.zoom.height, `${where}: the zoom above the ✓ and ✗`).toBeLessThan(b.select.y);
        const centreX = (box) => box.x + box.width / 2;
        expect(Math.abs(centreX(b.zoom) - (centreX(b.select) + centreX(b.reject)) / 2), `${where}: the zoom centred over them`)
          .toBeLessThanOrEqual(1);
      }
    }
  });

  // Each ✓ and ✗ glyph at the centre of its own button, both axes (the maintainer's report on
  // 26.11.11: the marks sat off centre, the ✗ most; the level test above compares the two buttons
  // with each other only), and the tile's zoom's: on a tile and in the large view, at rest and
  // pointed at, in both colour modes; the glyph as drawn (glyphOffset), within 1 px
  test('each ✓, ✗ and zoom glyph is centred in its button', { tag: ['@J-16', '@J-20'] }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    const centred = async (button, where) => {
      const { dx, dy } = await glyphOffset(page, button);
      expect(Math.abs(dx), `${where}: the glyph's centre across (${dx.toFixed(2)} px off)`).toBeLessThanOrEqual(1);
      expect(Math.abs(dy), `${where}: the glyph's centre down (${dy.toFixed(2)} px off)`).toBeLessThanOrEqual(1);
    };
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const select = page.getByRole('button', { name: 'Selected' }).first();
    await expect(select).toBeVisible();
    const tile = select.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " ug-tile ")][1]');
    const reject = tile.getByRole('button', { name: 'Rejected' });
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      for (const [name, button] of [['✓', select], ['✗', reject], ['zoom', tile.locator('.ug-icon-zoom')]]) {
        await tile.hover(TILE_CORNER);
        await page.waitForTimeout(300);
        await centred(button, `${mode} mode, the tile's ${name} at rest`);
        await button.hover();
        await page.waitForTimeout(300);
        await centred(button, `${mode} mode, the tile's ${name} pointed at`);
      }
    }
    await page.goto(largePath(fixture.roundBinaryId, juror.id, juror.pageId));
    const votes = page.locator('.large-nav a[data-vote-url]');
    await expect(votes).toHaveCount(2);
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      for (const [i, name] of [[0, '✓'], [1, '✗']]) {
        await page.mouse.move(1, 1);
        await page.waitForTimeout(300);
        await centred(votes.nth(i), `${mode} mode, the large view's ${name} at rest`);
        await votes.nth(i).hover();
        await page.waitForTimeout(300);
        await centred(votes.nth(i), `${mode} mode, the large view's ${name} pointed at`);
      }
    }
  });

  // A tile's buttons read on a light photo (the maintainer's notes on 26.11.11: the zoom, a white
  // sprite, was hard to see on a light, uniform one): on a white photo, under the tile's darkening
  // while it is pointed at, each of the zoom, ✓ and ✗ has its outer ring, its border or its
  // backing 3:1 or more against the photo beside it (edgeContrast: the pixels as drawn), and its
  // glyph centred, in both colour modes
  test('a tile\'s zoom, ✓ and ✗ stand out on a white photo', { tag: '@J-16' }, async ({ page, fixture }) => {
    const { juror } = fixture.accounts;
    const WHITE = png(640, 480, [255, 255, 255]);
    await page.route((u) => u.pathname.startsWith('/wikipedia/commons/thumb/') || u.hostname === 'upload.wikimedia.org',
      (route) => route.fulfill({ status: 200, contentType: 'image/png', body: WHITE }));
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const select = page.getByRole('button', { name: 'Selected' }).first();
    await expect(select).toBeVisible();
    const tile = select.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " ug-tile ")][1]');
    await expect.poll(() => tile.locator('img.ug-thumb-image').evaluate((img) => img.complete && img.naturalWidth),
      { message: 'the white photo loaded' }).toBe(640);
    const buttons = [['zoom', tile.locator('.ug-icon-zoom')], ['✓', select], ['✗', tile.getByRole('button', { name: 'Rejected' })]];
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      await tile.hover(TILE_CORNER);
      await page.waitForTimeout(400);
      for (const [name, button] of buttons) {
        const where = `${mode} mode, the tile's ${name}`;
        const c = await edgeContrast(page, button);
        expect(c.photo.every((v) => v > 100), `${where}: beside it the light photo (${c.photo})`).toBe(true);
        expect(Math.max(c.ring, c.border, c.backing),
          `${where}: its ring (${c.ring.toFixed(2)}:1), border (${c.border.toFixed(2)}:1) or backing (${c.backing.toFixed(2)}:1) against the photo`)
          .toBeGreaterThanOrEqual(3);
        const { dx, dy } = await glyphOffset(page, button);
        expect(Math.abs(dx), `${where}: the glyph's centre across (${dx.toFixed(2)} px off)`).toBeLessThanOrEqual(1);
        expect(Math.abs(dy), `${where}: the glyph's centre down (${dy.toFixed(2)} px off)`).toBeLessThanOrEqual(1);
      }
    }
  });

  test('in the binary round\'s gallery by a tile\'s ✓ button', { tag: ['@J-16', '@J-36', '@S-02'] }, async ({ page, context, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundBinaryId, juror.id));
    const select = page.getByRole('button', { name: 'Selected' }).first();
    await expect(select).toBeVisible();
    const wasPressed = (await select.getAttribute('aria-pressed')) === 'true';
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await select.click();
    const voted = await response;
    expect(voted.status()).toBe(200);
    expect(voted.request().headers()['csrf-token'], 'the vote sends the CSRF token').toBeTruthy();
    await expect(select).toHaveAttribute('aria-pressed', String(!wasPressed));
    // the tile shows the vote as a ✓ chip in its lower-left corner (where the organisers' tally
    // is), none unrated, and has no coloured frame (the maintainer's decisions of 2026-11-15):
    // one chip (the fixture has no comments), named once for screen readers
    const tile = select.locator('xpath=ancestor::*[contains(concat(" ", @class, " "), " ug-tile ")][1]');
    const mark = tile.locator('.wlx-chip-vote');
    await expect(mark).toHaveCount(wasPressed ? 0 : 1);
    if (!wasPressed) {
      await expect(mark.locator('i.bi-check-lg')).toBeVisible();
      await expect(tile.locator('.wlx-tile-chips > .wlx-chip')).toHaveCount(1);
      await expect(tile.locator('.wlx-chip-time')).toHaveCount(0); // in file order: no time
      await expect(mark.locator('.visually-hidden')).toHaveText('Selected');
      const [t, m] = [await tile.boundingBox(), await mark.boundingBox()];
      expect(m.x - t.x, 'the chip at the left').toBeLessThan(12);
      expect(t.y + t.height - (m.y + m.height), 'the chip at the foot').toBeLessThan(12);
      // the chips under the ✓ and ✗ buttons: the point at the ✗'s centre is the ✗'s
      await tile.hover();
      const reject = tile.locator('.ug-icon-reject');
      await expect(reject).toBeVisible();
      const r = await reject.boundingBox();
      expect(await page.evaluate(([x, y]) => !!document.elementFromPoint(x, y)?.closest('.ug-icon-reject'),
        [r.x + r.width / 2, r.y + r.height / 2]), 'the ✗ button not covered').toBe(true);
      expect(await tile.locator('.wlx-tile-chips').evaluate((el) => Number(getComputedStyle(el).zIndex)))
        .toBeLessThan(await reject.evaluate((el) => Number(getComputedStyle(el).zIndex)));
    }
    expect(await tile.evaluate((el) => getComputedStyle(el, '::before').borderTopWidth)).toBe('0px');
    // the stored vote, in the file's large view
    const [, pageId, rate] = new URL(voted.url()).pathname.match(/\/pageid\/(\d+)\/select\/(-?\d+)$/);
    expect(Number(rate)).toBe(wasPressed ? 0 : 1);
    const large = await context.newPage();
    await large.goto(largePath(fixture.roundBinaryId, juror.id, pageId));
    expect(await binaryVote(large)).toBe(Number(rate));
    await large.close();
    if (!wasPressed) {
      // newest votes first: the file first, its mark and time in one chip ("✓ 15 Nov, 14:32"),
      // the time muted, one name for both ("Selected, 15 Nov, 14:32"); no second chip for the time
      const latest = await context.newPage();
      await latest.goto(`${galleryPath(fixture.roundBinaryId, juror.id)}?sort=latest-vote`);
      const first = latest.locator('.ug-tile').first();
      const chip = first.locator('.wlx-chip-vote');
      await expect(chip.locator('i.bi-check-lg')).toBeVisible();
      await expect(chip.locator('.wlx-chip-time time')).toBeVisible();
      await expect(first.locator('.wlx-chip-time')).toHaveCount(1);
      await expect(first.locator('.wlx-tile-chips > .wlx-chip')).toHaveCount(1);
      await expect(chip.locator('.visually-hidden')).toHaveText(/^Selected, .*\d\d:\d\d$/);
      const [time, mark] = await Promise.all([chip.locator('.wlx-chip-time'), chip.locator('i')]
        .map((l) => l.evaluate((el) => getComputedStyle(el).color)));
      expect(time, 'the time muted, not in the mark\'s colour').not.toBe(mark);
      await latest.close();
    }
  });

  test('in the rated round\'s gallery by a tile\'s star', { tag: '@J-17' }, async ({ page, context, fixture }) => {
    const { juror } = fixture.accounts;
    await page.goto(galleryPath(fixture.roundRatingId, juror.id));
    // the first tile's stars: raty's <i data-alt="n">, as in the large view (tile-votes.js adds
    // them to unitegallery's tiles, outside #gallery); its rate first, then another one
    const stars = page.locator('[data-alt="1"]').first().locator('..');
    await expect(stars).toBeVisible();
    const current = await stars.evaluate((el) => Number(window.jQuery(el).raty('score')) || 0);
    const expected = current === 5 ? 6 : 5;
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await stars.locator(`[data-alt="${expected}"]`).click();
    const voted = await response;
    expect(voted.status()).toBe(200);
    const [, pageId, rate] = new URL(voted.url()).pathname.match(/\/pageid\/(\d+)\/select\/(\d+)$/);
    expect(Number(rate)).toBe(expected);
    const large = await context.newPage();
    await large.goto(largePath(fixture.roundRatingId, juror.id, pageId));
    expect(await ratedVote(large)).toBe(expected);
    await large.close();
  });
});

// The large view's comments link (navigationButtons commentsLink): after the vote's summary, the
// tiles' comment chip ("💬 1") as a link to the comments section under the file; a click moves
// there. The tile's comments chip links the same place from the gallery (tile-votes.js addComments).
// The fixture has no comments: the contest's admin writes one on a file none of the tests' jurors
// has (lib/server.js commentFile), so their views still have none (navbar.spec.js); a rerun on the
// same server finds it there.
test.describe('the large view\'s comments link', () => {
  test('scrolls to the comments section, with the count it shows', { tag: '@J-25' }, async ({ contextAs, fixture, browserName }) => {
    const context = await contextAs('admin');
    const page = await context.newPage();
    const other = { id: fixture.commentFile.juror, pageId: fixture.commentFile.pageId };
    expect(other.pageId, 'server.json has a file for the comment (restart the fixture)').toBeGreaterThan(0);
    const url = largePath(fixture.roundBinaryId, other.id, other.pageId);
    await page.goto(url);
    const link = page.locator('a.wlx-chip-comments');
    if (await link.count() === 0) {
      await page.locator('#addComment textarea').fill('A comment for the comments link (browser test)');
      await Promise.all([page.waitForNavigation(), page.locator('#saySomething').click()]);
      await page.goto(url);
    }
    const count = await page.locator('.large-comments .msg').count();
    expect(count).toBeGreaterThan(0);
    await expect(link).toHaveAttribute('href', '#comments');
    await expect(link.locator('span[aria-hidden=true]')).toHaveText(String(count));
    await expect(link).toHaveAccessibleName(`${count} comment${count === 1 ? '' : 's'}: open the comments`);
    // the heading below the view's height first, then in view after the click: the view made
    // shorter than the heading's place (the file loaded, which pushes the comments down; the
    // file's height follows the view's in some engines, so measured again after each change)
    await page.waitForFunction(() => { const img = document.getElementById('large_image'); return !img || img.complete; });
    const heading = page.locator('h2#comments');
    let height = 400;
    for (let i = 0; i < 4; i++) {
      await page.setViewportSize({ width: 1366, height });
      await page.evaluate(() => window.scrollTo(0, 0));
      const top = await heading.evaluate((h) => h.getBoundingClientRect().top);
      if (top >= height) break;
      height = Math.max(120, Math.floor(top * 0.7));
    }
    await expect(heading).not.toBeInViewport();
    await link.click();
    await expect(page).toHaveURL(/#comments$/);
    await expect(heading).toBeInViewport();

    // the previous file's large view, its Next files strip (large/thumbsBar): the file's comment
    // chip there is the same link, to its large view at the comments (it was a plain badge on the
    // thumbnail's link, which opened the file at its top)
    const up = await page.locator('[data-nav=up]').getAttribute('href');
    expect(up, 'the comment file has a file before it in its list').not.toBe('#');
    await page.goto(up);
    const toggle = page.locator('button[aria-controls=large-next]');
    if (await toggle.getAttribute('aria-expanded') !== 'true') await toggle.click();
    const stripChip = page.locator(`#large-next .thumb_small a.wlx-chip-comments[href*="/pageid/${other.pageId}?"]`);
    await expect(stripChip).toBeVisible();
    await expect(stripChip).toHaveAccessibleName(/^\d+ comments?: open the comments$/);
    await stripChip.click();
    await page.waitForURL((u) => u.pathname.endsWith(`/pageid/${other.pageId}`) && u.hash === '#comments');
    await expect(heading).toBeInViewport();

    // the gallery page with the file (the large view's Gallery button): its tile's comments chip,
    // the one chip that is a link, opens the file's large view at the comments
    await page.goto(await page.locator('[data-nav=gallery]').getAttribute('href'));
    const chip = page.locator(`.ug-tile a.wlx-chip-comments[href*="/pageid/${other.pageId}"]`);
    await expect(chip).toBeVisible();
    // its name says its shown number (another engine sharing the server may have commented since)
    await expect(chip).toHaveAccessibleName(/^\d+ comments?: open the comments$/);
    const named = (await chip.getAttribute('title')).match(/^(\d+) /)[1];
    await expect(chip.locator('span[aria-hidden=true]')).toHaveText(named);
    const box = await chip.boundingBox();
    expect(box.height, 'a 24 px target').toBeGreaterThanOrEqual(24);
    expect(box.width, 'a 24 px target').toBeGreaterThanOrEqual(24);
    await chip.click();
    await page.waitForURL(new RegExp(`/pageid/${other.pageId}.*#comments$`));
    await expect(heading).toBeInViewport();

    // the comments list (commentsReport commentsLink): the file's row, after its vote marks, has
    // the same chip with the row's own comments, a link to its large view at the comments
    await page.goto(`/comments/round/${fixture.roundBinaryId}/user/${other.id}/page/1`);
    const row = page.locator(`article.wlx-comments-row[id="${other.pageId}"]`);
    const rowChip = row.locator('.wlx-comments-result > a.wlx-chip-comments');
    const shown = await row.locator('.msg').count();
    await expect(rowChip.locator('span[aria-hidden=true]')).toHaveText(String(shown));
    await expect(rowChip).toHaveAccessibleName(`${shown} comment${shown === 1 ? '' : 's'}: open the comments`);
    const target = await rowChip.boundingBox();
    expect(target.height, 'a 24 px target').toBeGreaterThanOrEqual(24);
    if (browserName === 'chromium') {
      const results = await new AxeBuilder({ page }).include(`article.wlx-comments-row[id="${other.pageId}"]`)
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map((v) => v.id), 'the row with its chip: no accessibility findings').toEqual([]);
    }
    await rowChip.click();
    await page.waitForURL(new RegExp(`/large/round/${fixture.roundBinaryId}/user/${other.id}/pageid/${other.pageId}.*#comments$`));
    await expect(page.locator('h2#comments')).toBeInViewport();

    // the by-rate gallery of all the jurors, only the files with comments, by approvals (the
    // maintainer's report of 2026-11-16: its chip was a plain badge on the thumbnail's link, which
    // opened the large view at its top): the same link chip, after the file's tally, in its chips
    // under the thumbnail with its rank (galleryByRate rankChips; the maintainer's note of
    // 2026-11-16), to its large view in this list at the comments
    await page.setViewportSize({ width: 1366, height: 400 });
    await page.goto(`/byrate/round/${fixture.roundBinaryId}/user/0/page/1?comments=1&sort=approvals`);
    const byRateBox = page.locator(`li.gallerybox[id="${other.pageId}"]`);
    const byRateChip = byRateBox.locator('.rank-line > .wlx-tile-foot > .wlx-tile-chips > a.wlx-chip-comments');
    await expect(byRateChip).toBeVisible();
    await expect(byRateChip).toHaveAccessibleName(/^\d+ comments?: open the comments$/);
    await expect(byRateBox.locator('.wlx-tile-chips > :first-child')).toHaveClass(/wlx-chip-tally/);
    const byRateTarget = await byRateChip.boundingBox();
    expect(byRateTarget.height, 'a 24 px target').toBeGreaterThanOrEqual(24);
    expect(byRateTarget.width, 'a 24 px target').toBeGreaterThanOrEqual(24);
    await byRateChip.click();
    await page.waitForURL((url) => url.pathname === `/large/round/${fixture.roundBinaryId}/user/0/pageid/${other.pageId}`
      && url.hash === '#comments');
    expect(page.url()).toContain('comments=1');
    expect(page.url()).toContain('sort=approvals');
    await expect(page.locator('h2#comments')).toBeInViewport();
    // the thumbnail itself still opens the large view at its top
    await page.goBack();
    await Promise.all([
      page.waitForURL((url) => url.pathname.endsWith(`/pageid/${other.pageId}`) && url.hash === ''),
      byRateBox.locator('.thumb > a img').click()
    ]);

    // one juror's by-rate list by vote time: the vote chips as the tiles' (rankChips), their
    // words read with the time formatted as shown (vote-time.js), never the raw UTC value, and
    // their title the same with the year
    await page.goto(`/byrate/round/${fixture.roundBinaryId}/user/${other.id}/page/1?sort=latest-vote`);
    const voteChip = page.locator('li.gallerybox .wlx-tile-chips > .wlx-chip-vote').first();
    // the words screen readers read (the span has no role, so its accessible name would fall
    // back to its title)
    const words = voteChip.locator('.visually-hidden');
    await expect(words).toHaveText(/^(Selected|Rejected), [A-Z][a-z]{2} \d{1,2}, \d\d:\d\d$/);
    await expect(words).not.toContainText('Z');
    await expect(voteChip).toHaveAttribute('title', /^(Selected|Rejected), [A-Z][a-z]{2} \d{1,2}, \d{4}, \d\d:\d\d$/);
  });

  // Opened at its comments (the chips' links) while the file is still on its way: the file's box
  // has its loaded height before it arrives (Global.largeImageBox), so the comments are in view at
  // once and stay where they are when it does. A file of other proportions than the stored ones
  // takes another height once loaded (large-view.js removes the box): the page scrolls back to the
  // comments (keepCommentsInView), unless the user has scrolled since, and not on a reload, where
  // the place is the browser's. The upload host's answer is held until the test serves it.
  test('keeps the comments in view while the file loads', { tag: '@J-25' }, async ({ contextAs, fixture }) => {
    const context = await contextAs('admin');
    const page = await context.newPage();
    const other = { id: fixture.commentFile.juror, pageId: fixture.commentFile.pageId };
    expect(other.pageId, 'server.json has a file for the comment (restart the fixture)').toBeGreaterThan(0);
    const url = largePath(fixture.roundBinaryId, other.id, other.pageId);
    // lower than the file, which so has the comments below it; narrow, so that a landscape file's
    // box is narrower than the column and a portrait one's as tall (another file's height then differs)
    await page.setViewportSize({ width: 700, height: 500 });
    const PLACEHOLDER = png(64, 48, [120, 140, 160]);
    let stored;
    let serve;
    let held;
    let requests = [];
    // the large image, served when the test says, and the next file's, which large-next-image.js
    // loads once the page is up: every upload-host request is kept, the large image's is the one in
    // its srcset (file() below)
    await page.route((u) => u.hostname === 'upload.wikimedia.org', async (route) => {
      requests.push(route.request().url());
      const body = await held;
      await route.fulfill({ status: 200, contentType: 'image/png', body }).catch(() => {});
    });
    // a fresh load each time: from the view to the view at #comments would only move to the comments
    const open = async (path) => {
      if (serve) serve(PLACEHOLDER);
      await page.goto('about:blank');
      held = new Promise((resolve) => { serve = resolve; });
      requests = [];
      await page.goto(path, { waitUntil: 'domcontentloaded' });
    };
    // the file the page asked for, `tall` times as tall as the stored proportions make it: as wide as
    // its srcset says (the browser shows it the srcset's width over the sizes' wide)
    const file = async (tall) => {
      // the request of the large image: the one whose URL is in its srcset
      const own = () => image.evaluate((img, urls) => {
        const set = img.srcset.split(/,\s+/).map((c) => c.trim().split(/\s+/));
        const hit = urls.find((u) => set.some(([c]) => new URL(c, location.href).href === u));
        return hit ? { url: hit, width: parseInt(set.find(([c]) => new URL(c, location.href).href === hit)[1], 10) } : null;
      }, requests);
      await expect.poll(own, { message: 'the page asked for the file (a request in the large image srcset)' }).toBeTruthy();
      const { width } = await own();
      return png(width, Math.round(tall * width * stored.height / stored.width), [120, 140, 160]);
    };
    const image = page.locator('#large_image');
    const heading = page.locator('h2#comments');
    const box = () => image.evaluate((img) => { const r = img.getBoundingClientRect(); return { width: r.width, height: r.height }; });
    const loaded = () => page.waitForFunction(() => document.getElementById('large_image').complete);
    // how far the page is from where a link to the comments scrolls it: the heading at the top, or
    // as near as the page's end lets it
    const offComments = () => heading.evaluate((h) => {
      const root = document.scrollingElement;
      const target = Math.min(h.getBoundingClientRect().top + root.scrollTop, root.scrollHeight - root.clientHeight);
      return Math.abs(root.scrollTop - target);
    });

    // the stored proportions, in the box's width and height
    await open(url);
    stored = { width: Number(await image.getAttribute('width')), height: Number(await image.getAttribute('height')) };
    expect(stored.width, "the fixture's file has a stored size: its box has a width").toBeGreaterThan(0);
    expect(stored.height).toBeGreaterThan(0);

    // a file of the stored proportions: its box as tall before it arrives as the file after, so the
    // comments below don't move (where the engine scrolls to #comments before the file arrives is
    // its own: WebKit at times only once the page has loaded), and the page at them once loaded
    await open(`${url}#comments`);
    expect(await image.evaluate((img) => img.complete), 'the file is held').toBe(false);
    const before = await box();
    expect(before.height, 'the box has its height before the file arrives').toBeGreaterThan(100);
    serve(await file(1));
    await loaded();
    await expect(image).not.toHaveAttribute('width');
    await expect(image).not.toHaveAttribute('height');
    // as tall as its box (which may be wider while the file is on its way: object-fit, main.css)
    expect(Math.abs((await box()).height - before.height), 'the loaded file as tall as its box').toBeLessThanOrEqual(1);
    await page.waitForLoadState('load');
    await expect(heading).toBeInViewport();
    // polled: a smooth scroll may still be on its way at the page's load
    await expect.poll(offComments, { message: 'the page at the comments' }).toBeLessThanOrEqual(1.5);

    // a file of other proportions than the stored ones (twice as tall, or, for a box as tall as the
    // column already, half as tall): another height once loaded, the page back at the comments
    await open(`${url}#comments`);
    const reserved = await box();
    const columnHeight = await page.locator('#mainContent').evaluate((c) => c.clientHeight);
    const taller = reserved.height < columnHeight * 0.75;
    serve(await file(taller ? 2 : 0.5));
    await loaded();
    expect(Math.abs((await box()).height - reserved.height), 'the file of another height than its box').toBeGreaterThan(50);
    // once the page has loaded, the comments in view (a few px from the top: at the page's load it
    // scrolls only while their heading is out of view)
    await page.waitForLoadState('load');
    await expect(heading).toBeInViewport();

    // from here the page's own scrolls to the comments are counted (its scrollIntoView of them): where
    // the engine puts the page is its own (on a reload Chromium restores the user's place, WebKit goes
    // to #comments)
    await page.addInitScript(() => {
      const scrollIntoView = Element.prototype.scrollIntoView;
      window.wlxCommentsScrolls = 0;
      Element.prototype.scrollIntoView = function (...args) {
        if (this.id === 'comments') window.wlxCommentsScrolls += 1;
        return scrollIntoView.apply(this, args);
      };
    });
    const commentsScrolls = () => page.evaluate(() => window.wlxCommentsScrolls);

    // the user scrolls (a wheel) before the file (of other proportions) arrives: the page doesn't
    // scroll back to the comments
    await open(`${url}#comments`);
    await page.mouse.move(400, 250);
    await page.mouse.wheel(0, -300);
    const otherFile = await file(taller ? 2 : 0.5);
    serve(otherFile);
    await loaded();
    await page.waitForLoadState('load');
    expect(await commentsScrolls(), 'no scroll back to the comments after the user scrolled').toBe(0);

    // a reload (Back too: the view is no-store): no scroll to the comments, the place is the browser's
    held = Promise.resolve(otherFile);
    await page.reload({ waitUntil: 'load' });
    await loaded();
    expect(await commentsScrolls(), 'no scroll to the comments on a reload').toBe(0);

    // a control: opened at #comments with no user action, the page does scroll to them
    await open(`${url}#comments`);
    serve(otherFile);
    await loaded();
    await page.waitForLoadState('load');
    expect(await commentsScrolls(), 'the page scrolls to the comments when the file loads').toBeGreaterThan(0);
  });
});

// The by-rate gallery's chips with the ranking under the thumbnail (the maintainer's note of
// 2026-11-16; galleryByRate rankChips): all the jurors' tally on the rank's line, after it, never
// on the thumbnail, the line no wider than the thumbnail; a juror's own rated list by vote time
// with the vote's time after the stars, and a new vote's time there at once (tile-votes.js
// listVoteTimeAfter)
test.describe('the by-rate chips', () => {
  test('are on the rank\'s line under the thumbnail', { tag: ['@O-02', '@J-17'] }, async ({ contextAs, fixture }) => {
    const admin = await (await contextAs('admin')).newPage();
    await admin.goto(`/byrate/round/${fixture.roundBinaryId}/user/0/page/1`);
    const box = admin.locator('li.gallerybox').first();
    const line = box.locator('.rank-line');
    const tally = line.locator('.wlx-tile-foot > .wlx-tile-chips > .wlx-chip-tally');
    await expect(tally).toBeVisible();
    await expect(box.locator('.thumb .wlx-chip')).toHaveCount(0);
    const [thumb, rank, chip, row] = await Promise.all([box.locator('.thumb'), line.locator('b'), tally, line]
      .map((l) => l.boundingBox()));
    const centreY = (b) => b.y + b.height / 2;
    expect(chip.y, 'under the thumbnail').toBeGreaterThanOrEqual(thumb.y + thumb.height);
    expect(Math.abs(centreY(chip) - centreY(rank)), 'on the rank\'s line').toBeLessThanOrEqual(6);
    expect(chip.x, 'after the rank').toBeGreaterThan(rank.x + rank.width);
    expect(row.width, 'no wider than the thumbnail').toBeLessThanOrEqual(thumb.width + 1);

    const juror = await (await contextAs('juror')).newPage();
    await juror.goto(`/byrate/round/${fixture.roundRatingId}/user/${fixture.accounts.juror.id}/page/1?sort=latest-vote`);
    const first = juror.locator('li.gallerybox').first();
    const stars = first.locator('.rank-line .wlx-tile-stars');
    await expect(stars).toBeVisible();
    const current = await stars.evaluate((el) => Number(window.jQuery(el).raty('score')) || 0);
    const response = juror.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await stars.locator(`[data-alt="${current === 4 ? 5 : 4}"]`).click();
    expect((await response).status()).toBe(200);
    const vote = first.locator('.rank-line > .wlx-tile-foot > .wlx-tile-chips > .wlx-chip-vote');
    await expect(vote.locator('time.wlx-vote-time').first()).toBeVisible();
    await expect(first.locator('.thumb .wlx-chip')).toHaveCount(0);
    const [t, v] = [await first.locator('.thumb').boundingBox(), await vote.boundingBox()];
    expect(v.y, 'under the thumbnail').toBeGreaterThanOrEqual(t.y + t.height);
  });
});
