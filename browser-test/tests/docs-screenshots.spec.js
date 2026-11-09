// The user guides' screenshots (docs/user/*.md, images in docs/user/images/). Opt-in: skipped
// unless DOCS_SCREENSHOTS=1, and only in Chromium, so CI and normal runs are unaffected.
//
//   cd browser-test && DOCS_SCREENSHOTS=1 npx playwright test docs-screenshots --project=chromium
//
// Fixed settings: the dark theme (the tool's default, set by the theme cookie so that a fixture
// account's stored choice can't change it), English, a 1280x800 viewport, the UiShotsServer
// fixture's data.
// Every image is a Playwright `page.screenshot` or `locator.screenshot`: the page only, with no
// browser or operating-system window around it. Never capture the screen or a headed window.
//
// PLATFORM: fonts and text rendering depend on the operating system. The images in
// docs/user/images/ must all come from one platform and be regenerated together; the platform
// that produced the current set is recorded in docs/user/images/README.md (update it with them).
//
// The fixture's images are the CC0 photos of browser-test/docs-photos.py (UI_IMAGES=docs, see
// docs/user/images/README.md), never fetched by the run itself, and its accounts are
// generated: no real name, address or secret appears. The users page shot first invites one
// address at example.org (a pending invitation); a second run reuses it.
//
// Stories walked: none (the shots open pages, they test nothing; the page tour tests them).
import fs from 'node:fs';
import path from 'node:path';
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath } from '../lib/actions.js';
import { docsScreenshotsOn, readServer, ROOT } from '../lib/server.js';

const OUT_DIR = path.join(ROOT, 'docs', 'user', 'images');
const VIEWPORT = { width: 1280, height: 800 };

test.describe('the guides\' screenshots', () => {
  test.skip(!docsScreenshotsOn(), 'opt-in: set DOCS_SCREENSHOTS=1');
  test.skip(({ browserName }) => browserName !== 'chromium', 'Chromium only');
  // 2x: a 1280-wide page is a 2560-pixel PNG, shown in the guides at its CSS size (width="1280")
  test.use({ viewport: VIEWPORT, locale: 'en-US', colorScheme: 'dark', deviceScaleFactor: 2 });

  test.beforeAll(() => {
    // the photos, never the grey stub: a server started without docs mode fails the run
    expect(readServer().images, 'the fixture serves the docs photos: start it with DOCS_SCREENSHOTS=1 ' +
      '(node fixture-server.mjs stop, then start)').toBe('docs');
    fs.mkdirSync(OUT_DIR, { recursive: true });
  });

  // the app's theme cookie (controllers.Theme.CookieName), on every context before its first page
  let baseUrl;
  test.beforeEach(({ fixture }) => { baseUrl = fixture.baseUrl; });

  /** Opens `target` as `role` (null: signed out), waits for the page, and saves `name`.png. */
  // `contextAs` contexts use the project's viewport, not test.use's: set it on each page
  const sized = async (context) => {
    const page = await context.newPage();
    await page.setViewportSize(VIEWPORT);
    return page;
  };
  // The fixture's files are real Commons files, with their real authors' user names and file
  // names: the guides show neither. File names and an "Author" column become numbered examples.
  const anonymize = (page) => page.evaluate(() => {
    let n = 0;
    for (const a of document.querySelectorAll('a')) {
      if (/^File:/.test(a.textContent.trim())) a.textContent = `File:Example photo ${++n}.jpg`;
    }
    for (const table of document.querySelectorAll('table')) {
      const headers = [...table.querySelectorAll('thead th')].map((th) => th.textContent.trim());
      const column = headers.indexOf('Author');
      if (column < 0) continue;
      table.querySelectorAll('tbody tr').forEach((row, i) => {
        const cell = row.children[column];
        if (cell) cell.textContent = `Author ${i + 1}`;
      });
    }
  });
  // The large views show the controls column and the top of the photo (the guide explains the
  // column); the comment box has its own shot
  const LARGE_TOP = { height: 330, width: 820 };
  // `height`: only the top of the viewport (the rest of such a page is empty)
  const top = (page, file, height, width = VIEWPORT.width) =>
    page.screenshot({ path: file, fullPage: true, clip: { x: 0, y: 0, width, height } });

  /** The page height that ends below the `n`th row of the gallery's tiles (with a margin), so
    * that no crop cuts through a photo. */
  const belowRows = (page, n) => page.evaluate((rows) => {
    // the gallery's tiles (.ug-tile) or the results' (.gallerybox), in rows: tiles in a row are
    // centred on each other, so a tile starts a new row when it begins below the row's bottom
    const boxes = [...document.querySelectorAll('.ug-tile, .gallerybox')]
      .map((tile) => tile.getBoundingClientRect())
      .filter((r) => r.height > 0)
      .map((r) => ({ top: r.top + window.scrollY, bottom: r.bottom + window.scrollY }))
      .sort((a, b) => a.top - b.top);
    const rowList = [];
    for (const box of boxes) {
      const last = rowList[rowList.length - 1];
      if (last && box.top < last.bottom - 4) last.bottom = Math.max(last.bottom, box.bottom);
      else rowList.push({ top: box.top, bottom: box.bottom });
    }
    const row = rowList[Math.min(rows, rowList.length) - 1];
    const next = rowList[rows]; // the margin never reaches the next row's photos
    return Math.floor(next ? Math.min(row.bottom + 12, next.top - 2) : row.bottom + 12);
  }, n);

  /** The page height that ends below the `n`th body row of the page's first table, so that no
    * crop cuts through a row. */
  const belowTableRow = (page, n) => page.evaluate((count) => {
    const rows = [...document.querySelectorAll('table tbody tr')].filter((r) => r.getBoundingClientRect().height > 0);
    const row = rows[Math.min(count, rows.length) - 1].getBoundingClientRect();
    return Math.ceil(row.bottom + window.scrollY) + 2;
  }, n);

  async function shot(contextAs, role, target, name, { full = false, prepare, crop, height, width, rows, tableRows } = {}) {
    const context = await contextAs(role);
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await sized(context);
    await page.goto(target);
    await expect(page.locator('h1').first()).toBeAttached();
    if (prepare) await prepare(page);
    await page.waitForLoadState('networkidle');
    await anonymize(page);
    const file = path.join(OUT_DIR, `${name}.png`);
    // `crop` (CSS selectors): the relevant part of the page only, the boxes of the selectors'
    // first elements together, else the viewport or the page
    if (crop) {
      const boxes = [];
      for (const selector of [crop].flat()) {
        const box = await page.locator(selector).first().boundingBox();
        expect(box, `${selector} is shown`).not.toBeNull();
        boxes.push(box);
      }
      // the boxes are in the viewport; a full-page clip is in the page (a click may have scrolled)
      const scrollY = await page.evaluate(() => window.scrollY);
      const left = Math.min(...boxes.map((b) => b.x));
      const topEdge = Math.min(...boxes.map((b) => b.y)) + scrollY;
      const right = Math.max(...boxes.map((b) => b.x + b.width));
      const bottom = Math.max(...boxes.map((b) => b.y + b.height)) + scrollY;
      // with a margin, so that nothing at the edge is cut, but never up into the navbar: its
      // bottom border would show as a grey line along the top of the crop
      // (the large view has no navbar)
      const navBox = await page.locator('nav.navbar').count() ? await page.locator('nav.navbar').first().boundingBox() : null;
      const navBottom = navBox ? Math.ceil(navBox.y + navBox.height + scrollY) + 1 : 0;
      const x = Math.max(0, left - 12);
      const y = Math.max(navBottom, topEdge - 12);
      await page.screenshot({ path: file, fullPage: true,
        clip: { x, y, width: Math.min(VIEWPORT.width - x, right - x + 12), height: bottom - y + 12 } });
    } else if (rows) await top(page, file, await belowRows(page, rows), width);
    else if (tableRows) await top(page, file, await belowTableRow(page, tableRows), width);
    else if (height) await top(page, file, height, width);
    else {
      // a whole page ends with a margin, so that its last button isn't cut at the edge
      if (full) await page.addStyleTag({ content: 'body { padding-bottom: 24px; }' });
      await page.screenshot({ path: file, fullPage: full });
    }
    await context.close();
  }

  test('sign-in and juror pages', async ({ contextAs, fixture }) => {
    const { juror } = fixture.accounts;
    await shot(contextAs, null, '/auth', 'juror-sign-in');
    await shot(contextAs, 'juror', galleryPath(fixture.roundBinaryId, juror.id), 'juror-gallery-binary', { rows: 1 });
    await shot(contextAs, 'juror', galleryPath(fixture.roundRatingId, juror.id), 'juror-gallery-rated', { rows: 1 });
    await shot(contextAs, 'juror', galleryPath(fixture.roundBinaryId, juror.id), 'juror-gallery-top-bar', { height: 56 });
    await shot(contextAs, 'juror', largePath(fixture.roundBinaryId, juror.id, juror.pageId), 'juror-large-view-binary', LARGE_TOP);
    // the comment box, under the photo
    await shot(contextAs, 'juror', largePath(fixture.roundBinaryId, juror.id, juror.pageId), 'juror-large-view-comments', { crop: '.large-comments' });
    await shot(contextAs, 'juror', largePath(fixture.roundRatingId, juror.id, juror.ratedPageId), 'juror-large-view-rated', {
      ...LARGE_TOP,
      // a plain star rate in a round without the worst mark, as the guide describes. The
      // worst-mark test undoes its vote, so this holds on a rerun too; a safety net if that
      // undo failed: start the docs-mode server afresh
      prepare: async (page) => {
        await expect(page.locator('.bi-x-lg'), 'no worst-mark icon on the rated shot: restart the docs-mode server').toHaveCount(0);
      },
    });
    await shot(contextAs, 'juror', `/users/edit/${juror.id}`, 'juror-profile', { crop: '#edit-user' });
  });

  test('organizer pages', async ({ contextAs, fixture }) => {
    await shot(contextAs, 'organizer', `/roundstat/${fixture.roundBinaryId}`, 'organizer-round-statistics', { tableRows: 12 });
    await shot(contextAs, 'organizer', `/byrate/round/${fixture.roundRatingId}/user/0/page/1`, 'organizer-results-by-rate', { rows: 1 });
    await shot(contextAs, 'organizer', `/filelist/round/${fixture.roundRatingId}/user/0/page/1`, 'organizer-results-file-list', { tableRows: 6 });
    await shot(contextAs, 'organizer', `/admin/rounds/${fixture.roundBinaryId}/conflicts`, 'organizer-conflicts', { height: 680 });
    // the organizer's way to that page: the link under the statistics table (the last rows and it)
    const context = await contextAs('organizer');
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const stat = await sized(context);
    await stat.goto(`/roundstat/${fixture.roundBinaryId}`);
    await expect(stat.locator('#round-conflicts-link a')).toBeVisible();
    await stat.waitForLoadState('networkidle');
    const lastRow = await stat.locator('table tbody tr').last().boundingBox();
    const link = await stat.locator('#round-conflicts-link').boundingBox();
    await stat.screenshot({ path: path.join(OUT_DIR, 'organizer-round-statistics-link.png'), fullPage: true,
      clip: { x: 0, y: lastRow.y - 2, width: VIEWPORT.width, height: link.y + link.height - lastRow.y + 22 } });
    await context.close();
  });

  test('admin pages', async ({ contextAs, fixture }) => {
    const contest = fixture.contestId;
    await shot(contextAs, 'admin', `/admin/rounds?contestId=${contest}`, 'organizer-rounds-list', { height: 460 });
    await shot(contextAs, 'admin', `/admin/rounds/edit?contestId=${contest}`, 'organizer-new-round-form', {
      // the form only (about 800 CSS pixels wide), down to the end of its Save and Cancel buttons
      crop: ['#round-form', '#round-form .form-actions'],
      // the form's collapsed sections, opened, and their opening animation done before the form
      // is measured (Bootstrap's .collapsing becomes .collapse.show)
      prepare: async (page) => {
        for (const name of ['Media filtering', 'Add files']) {
          const toggle = page.getByRole('button', { name }).or(page.getByRole('link', { name })).first();
          await toggle.click();
          await expect(page.locator(`#${await toggle.getAttribute('aria-controls')}.collapse.show`)).toHaveCount(1);
        }
        await expect(page.locator('#round-form .collapsing')).toHaveCount(0);
        // the buttons end the page: room under them, so that the crop's margin is not cut off
        await page.addStyleTag({ content: 'body { padding-bottom: 48px; }' });
      },
    });
    await shot(contextAs, 'admin', `/contest/${contest}/images`, 'organizer-contest-images', { full: true });
    await shot(contextAs, 'admin', `/users/edit/${fixture.accounts.juror.id}?contestId=${contest}`, 'admin-user-edit', { crop: '#edit-user' });

    // a pending invitation: invite one address (once; a second run finds it)
    const context = await contextAs('admin');
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await sized(context);
    await page.goto(`/admin/users?contestId=${contest}`);
    if (await page.getByRole('cell', { name: 'invitee@example.org' }).count() === 0) {
      // a form can't wrap a table row, so the browser leaves the inputs outside it: by id
      await page.locator('#fullname').fill('Sample Invitee');
      await page.locator('#email').fill('invitee@example.org');
      await page.locator('#roles').selectOption({ label: 'Juror' });
      await page.locator('#add-user-button').click();
      await page.waitForLoadState('networkidle');
    }
    // the top of the table (the fixture has 30 members), and the add row with the invitations
    await top(page, path.join(OUT_DIR, 'admin-users.png'), await belowTableRow(page, 4));
    const heading = await page.getByRole('heading', { name: 'Invitations', exact: true }).boundingBox();
    const table = await page.locator('table.pending-invitations').boundingBox();
    await page.screenshot({ path: path.join(OUT_DIR, 'admin-invitations.png'), fullPage: true,
      clip: { x: 0, y: heading.y - 10, width: VIEWPORT.width, height: table.y + table.height - heading.y + 20 } });

    // the link to share for that invitation, shown once. Creating it revokes the invitation's
    // earlier unused links (a later use of the mailed one is not part of any test).
    await page.locator('table.pending-invitations tr', { hasText: 'invitee@example.org' })
      .locator('button.share-link').click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('A link to set the password');
    await page.waitForLoadState('networkidle');
    // the page that link opens (shot below, signed out: nothing is accepted by looking at it)
    const invitationPath = new URL(await page.locator('input[value*="/invitation/"]').inputValue()).pathname;
    // the link's host is the fixture's configured one: show a neutral host instead
    await page.locator('input[value*="/invitation/"]').evaluateAll((inputs) => inputs.forEach((input) => {
      input.value = input.value.replace(/^https?:\/\/[^/]+/, 'https://jury.example.org');
    }));
    // the centred section only, from its heading down to the end of the warning (the button
    // after it isn't shown, so nothing is cut)
    const section = await page.locator('#share-link').boundingBox();
    const warning = await page.locator('#share-link .alert').last().boundingBox();
    expect(section && warning, 'the section and its warning are shown').toBeTruthy();
    // as the other crops: full-page coordinates, never up into the navbar, inside the viewport
    const scrollY = await page.evaluate(() => window.scrollY);
    const navBox = await page.locator('nav.navbar').count() ? await page.locator('nav.navbar').first().boundingBox() : null;
    const navBottom = navBox ? Math.ceil(navBox.y + navBox.height + scrollY) + 1 : 0;
    const shareX = Math.max(0, section.x - 12);
    const shareY = Math.max(navBottom, section.y + scrollY - 12);
    await page.screenshot({ path: path.join(OUT_DIR, 'admin-share-link.png'), fullPage: true,
      clip: { x: shareX, y: shareY, width: Math.min(VIEWPORT.width - shareX, section.width + 24),
        height: Math.ceil(warning.y + warning.height + scrollY) + 16 - shareY } });
    await context.close();
    await shot(contextAs, null, invitationPath, 'juror-set-password', { height: 440 });
  });

  // a juror opening an organizer's page: the 403 page, with its reference
  test('the access-denied page', async ({ contextAs, fixture, diagnostics }) => {
    diagnostics.expectStatus(403);
    const context = await contextAs('juror');
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await sized(context);
    const response = await page.goto(`/roundstat/${fixture.roundBinaryId}`);
    expect(response.status()).toBe(403);
    await expect(page.getByRole('heading', { level: 1, name: 'Access denied' })).toBeVisible();
    await page.waitForLoadState('networkidle');
    await top(page, path.join(OUT_DIR, 'juror-access-denied.png'), 470);
    await context.close();
  });

  // The rated round's form with another score rule picked, which shows the warning (not saved).
  test('the score rule warning', async ({ contextAs, fixture }) => {
    const context = await contextAs('admin');
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await sized(context);
    await page.goto(`/admin/rounds/edit?id=${fixture.roundRatingId}&contestId=${fixture.contestId}`);
    const select = page.locator('#averageRule');
    const current = await select.inputValue();
    const other = await select.locator('option').evaluateAll((options, c) => options.map((o) => o.value).find((v) => v !== c), current);
    await select.selectOption(other);
    // the fixture's rated round has rated votes, so the page has the warning: without it the
    // shot would show no warning, a failure, not a skip
    await expect(page.locator('#averageRule_warning_row'), 'the rated round has rated votes').toHaveCount(1);
    await expect(page.locator('#averageRule_warning')).toBeVisible();
    await page.waitForLoadState('networkidle');
    // from the rating type's row above the option to the worst mark's below its warning
    const fieldRow = (selector) => page.locator(selector).locator('xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " row ")][1]');
    const from = await fieldRow('#rates').boundingBox();
    const to = await page.locator('#round-worst-mark').boundingBox();
    expect(from && to, 'the rating type and the worst mark are shown').toBeTruthy();
    // the boxes are in the viewport, the full-page clip in the page: selecting scrolled to the select
    const scrollY = await page.evaluate(() => window.scrollY);
    const y = Math.max(0, from.y + scrollY - 12);
    await page.screenshot({ path: path.join(OUT_DIR, 'organizer-score-rule-warning.png'), fullPage: true,
      // the centred form's width (the rows span it), with a margin
      clip: { x: Math.max(0, from.x - 12), y, width: from.width + 24, height: to.y + scrollY + to.height + 12 - y } });
    await context.close(); // not saved
  });

  // The rated round with the worst mark on, for the large view's worst-mark controls: the option
  // is turned on through the round form and off again at the end, so other specs see the round
  // as the fixture made it.
  test('the worst mark in the large view', async ({ contextAs, fixture }) => {
    const form = `/admin/rounds/edit?id=${fixture.roundRatingId}&contestId=${fixture.contestId}`;
    const admin = await contextAs('admin');
    const page = await sized(admin);
    await page.goto(form);
    const box = page.getByLabel('Worst mark', { exact: true });
    // the fixture's rated round takes the option (not a criteria round): without it, a failure
    await expect(box, 'the rated round offers the worst mark').toBeVisible();
    const was = await box.isChecked();
    const setOption = async (on) => {
      await page.goto(form);
      await page.getByLabel('Worst mark', { exact: true }).setChecked(on);
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page).toHaveURL(/\/admin\/rounds/);
    };
    const { juror } = fixture.accounts;
    const filePath = largePath(fixture.roundRatingId, juror.id, juror.ratedPageId);
    const context = await contextAs('juror');
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const view = await sized(context);
    let rateBefore = 0; // the file's own rate in stars, to give back
    let voted = false;
    try {
      if (!was) await setOption(true);
      await view.goto(filePath);
      await expect(view.getByRole('button', { name: /Worst mark/ }).first()).toBeVisible();
      // the shot shows a file that has the worst mark, as the guide says: give it one, then
      // open the file again, as the large view moves on after a vote. The vote is undone in
      // the finally below, so that a rerun on the same server still shows plain stars.
      rateBefore = Number(await view.locator('.large-stars').first().getAttribute('data-score')) || 0;
      voted = true;
      await view.getByRole('button', { name: /Worst mark/ }).first().click();
      await view.waitForLoadState('networkidle');
      await view.goto(filePath);
      await expect(view.getByRole('button', { name: /Clear the vote/ }).first()).toBeVisible();
      await view.waitForLoadState('networkidle');
      await top(view, path.join(OUT_DIR, 'juror-large-view-worst-mark.png'), LARGE_TOP.height, LARGE_TOP.width);
    } finally {
      try {
        if (voted) {
          // clear the vote, and give the file its earlier whole-star rate back (a click on that
          // star); a half-star rate (3.5) is not restored, as a click gives whole stars
          await view.goto(filePath);
          const clear = view.getByRole('button', { name: /Clear the vote/ }).first();
          if (await clear.count()) {
            await clear.click();
            await view.waitForLoadState('networkidle');
          }
          if (rateBefore > 0 && Number.isInteger(rateBefore)) {
            await view.goto(filePath);
            await view.locator('.large-stars .wlx-star').nth(rateBefore - 1).click();
            await view.waitForLoadState('networkidle');
          }
        }
      } finally {
        await context.close();
        if (!was) await setOption(false);
      }
    }
    await admin.close();
  });

  test('root pages', async ({ contextAs }) => {
    await shot(contextAs, 'root', '/contests', 'admin-contests');
  });
});
