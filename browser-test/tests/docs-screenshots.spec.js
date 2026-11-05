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
import { ROOT } from '../lib/server.js';

const OUT_DIR = path.join(ROOT, 'docs', 'user', 'images');
const VIEWPORT = { width: 1280, height: 800 };

test.describe('the guides\' screenshots', () => {
  test.skip(!process.env.DOCS_SCREENSHOTS, 'opt-in: set DOCS_SCREENSHOTS=1');
  test.skip(({ browserName }) => browserName !== 'chromium', 'Chromium only');
  // 2x: a 1280-wide page is a 2560-pixel PNG, shown in the guides at its CSS size (width="1280")
  test.use({ viewport: VIEWPORT, locale: 'en-US', colorScheme: 'dark', deviceScaleFactor: 2 });

  test.beforeAll(() => fs.mkdirSync(OUT_DIR, { recursive: true }));

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
  // `height`: only the top of the viewport (the rest of such a page is empty)
  const top = (page, file, height, width = VIEWPORT.width) =>
    page.screenshot({ path: file, clip: { x: 0, y: 0, width, height } });

  async function shot(contextAs, role, target, name, { full = false, prepare, crop, height, width } = {}) {
    const context = await contextAs(role);
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await sized(context);
    await page.goto(target);
    await expect(page.locator('h1').first()).toBeAttached();
    if (prepare) await prepare(page);
    await page.waitForLoadState('networkidle');
    await anonymize(page);
    const file = path.join(OUT_DIR, `${name}.png`);
    // `crop` (a CSS selector): the relevant part of the page only, else the viewport or the page
    if (crop) {
      // with a margin, so that nothing at the edge is cut
      const box = await page.locator(crop).first().boundingBox();
      const x = Math.max(0, box.x - 12);
      await page.screenshot({ path: file, fullPage: true,
        clip: { x, y: Math.max(0, box.y - 12), width: Math.min(VIEWPORT.width - x, box.width + 24), height: box.height + 24 } });
    } else if (height) await top(page, file, height, width);
    else await page.screenshot({ path: file, fullPage: full });
    await context.close();
  }

  test('sign-in and juror pages', async ({ contextAs, fixture }) => {
    const { juror } = fixture.accounts;
    await shot(contextAs, null, '/auth', 'juror-sign-in');
    await shot(contextAs, 'juror', galleryPath(fixture.roundBinaryId, juror.id), 'juror-gallery-binary', { height: 400 });
    await shot(contextAs, 'juror', galleryPath(fixture.roundRatingId, juror.id), 'juror-gallery-rated', { height: 400 });
    await shot(contextAs, 'juror', galleryPath(fixture.roundBinaryId, juror.id), 'juror-gallery-top-bar', { height: 56 });
    await shot(contextAs, 'juror', largePath(fixture.roundBinaryId, juror.id, juror.pageId), 'juror-large-view-binary', { height: 560 });
    await shot(contextAs, 'juror', largePath(fixture.roundRatingId, juror.id, juror.ratedPageId), 'juror-large-view-rated', { height: 560 });
    await shot(contextAs, 'juror', `/users/edit/${juror.id}`, 'juror-profile', { full: true });
  });

  test('organizer pages', async ({ contextAs, fixture }) => {
    await shot(contextAs, 'organizer', `/roundstat/${fixture.roundBinaryId}`, 'organizer-round-statistics');
    await shot(contextAs, 'organizer', `/byrate/round/${fixture.roundRatingId}/user/0/page/1`, 'organizer-results-by-rate', { height: 600 });
    await shot(contextAs, 'organizer', `/filelist/round/${fixture.roundRatingId}/user/0/page/1`, 'organizer-results-file-list');
    await shot(contextAs, 'organizer', `/admin/rounds/${fixture.roundBinaryId}/conflicts`, 'organizer-conflicts', { height: 680 });
  });

  test('admin pages', async ({ contextAs, fixture }) => {
    const contest = fixture.contestId;
    await shot(contextAs, 'admin', `/admin/rounds?contestId=${contest}`, 'organizer-rounds-list', { height: 460 });
    await shot(contextAs, 'admin', `/admin/rounds/edit?contestId=${contest}`, 'organizer-new-round-form', {
      crop: '#round-form', // the form only (about 800 CSS pixels wide)
      // the form's collapsed sections, opened
      prepare: async (page) => {
        for (const name of ['Media filtering', 'Add files']) {
          await page.getByRole('button', { name }).or(page.getByRole('link', { name })).first().click();
        }
      },
    });
    await shot(contextAs, 'admin', `/contest/${contest}/images`, 'organizer-contest-images', { full: true });
    await shot(contextAs, 'admin', `/users/edit/${fixture.accounts.juror.id}?contestId=${contest}`, 'admin-user-edit', { full: true });

    // a pending invitation: invite one address (once; a second run finds it)
    const context = await contextAs('admin');
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await sized(context);
    await page.goto(`/admin/users?contestId=${contest}`);
    if (await page.getByRole('cell', { name: 'invitee@example.org' }).count() === 0) {
      // a form can't wrap a table row, so the browser leaves the inputs outside it: by id
      await page.locator('#fullname').fill('Sample Invitee');
      await page.locator('#email').fill('invitee@example.org');
      await page.locator('#roles').selectOption({ label: 'Jury' });
      await page.locator('#add-user-button').click();
      await page.waitForLoadState('networkidle');
    }
    // the top of the table (the fixture has 30 members), and the add row with the invitations
    await top(page, path.join(OUT_DIR, 'admin-users.png'), 520);
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
    await top(page, path.join(OUT_DIR, 'admin-share-link.png'), 440);
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
    const warning = page.locator('#averageRule_warning');
    test.skip(await page.locator('#averageRule_warning_row').count() === 0, 'the rated round has no vote: no warning');
    await expect(warning).toBeVisible();
    await page.waitForLoadState('networkidle');
    const from = await select.boundingBox();
    const to = await warning.boundingBox();
    await page.screenshot({ path: path.join(OUT_DIR, 'organizer-score-rule-warning.png'), fullPage: true,
      clip: { x: 0, y: from.y - 80, width: VIEWPORT.width, height: to.y + to.height - from.y + 120 } });
    await context.close(); // not saved
  });

  // The rated round with the worst mark on, for the large view's ✗ controls: the option is
  // turned on through the round form and off again at the end, so other specs see the round as
  // the fixture made it.
  test('the worst mark in the large view', async ({ contextAs, fixture }) => {
    const form = `/admin/rounds/edit?id=${fixture.roundRatingId}&contestId=${fixture.contestId}`;
    const admin = await contextAs('admin');
    const page = await sized(admin);
    await page.goto(form);
    const box = page.getByLabel('Worst mark (✗)');
    test.skip(await box.count() === 0 || !(await box.isVisible()), 'the rated round takes no worst mark (criteria?)');
    const was = await box.isChecked();
    const setOption = async (on) => {
      await page.goto(form);
      await page.getByLabel('Worst mark (✗)').setChecked(on);
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page).toHaveURL(/\/admin\/rounds/);
    };
    try {
      if (!was) await setOption(true);
      const { juror } = fixture.accounts;
      const context = await contextAs('juror');
      await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
      const view = await sized(context);
      await view.goto(largePath(fixture.roundRatingId, juror.id, juror.ratedPageId));
      await expect(view.getByRole('button', { name: /Worst mark/ }).first()).toBeVisible();
      await view.waitForLoadState('networkidle');
      await top(view, path.join(OUT_DIR, 'juror-large-view-worst-mark.png'), 560);
      await context.close();
    } finally {
      if (!was) await setOption(false);
    }
    await admin.close();
  });

  test('root pages', async ({ contextAs }) => {
    await shot(contextAs, 'root', '/contests', 'admin-campaigns');
  });
});
