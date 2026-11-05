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
// The fixture's images are placeholders from a stub, never Commons, and its accounts are
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
  async function shot(contextAs, role, target, name, { full = false, prepare, crop } = {}) {
    const context = await contextAs(role);
    await context.addCookies([{ name: 'theme', value: 'dark', url: baseUrl }]);
    const page = await context.newPage();
    await page.goto(target);
    await expect(page.locator('h1').first()).toBeAttached();
    if (prepare) await prepare(page);
    await page.waitForLoadState('networkidle');
    const file = path.join(OUT_DIR, `${name}.png`);
    // `crop` (a CSS selector): the relevant part of the page only, else the viewport or the page
    if (crop) await page.locator(crop).first().screenshot({ path: file });
    else await page.screenshot({ path: file, fullPage: full });
    await context.close();
  }

  test('sign-in and juror pages', async ({ contextAs, fixture }) => {
    const { juror } = fixture.accounts;
    await shot(contextAs, null, '/auth', 'juror-sign-in');
    await shot(contextAs, 'juror', galleryPath(fixture.roundBinaryId, juror.id), 'juror-gallery-binary');
    await shot(contextAs, 'juror', galleryPath(fixture.roundRatingId, juror.id), 'juror-gallery-rated');
    await shot(contextAs, 'juror', largePath(fixture.roundBinaryId, juror.id, juror.pageId), 'juror-large-view-binary');
    await shot(contextAs, 'juror', largePath(fixture.roundRatingId, juror.id, juror.ratedPageId), 'juror-large-view-rated');
    await shot(contextAs, 'juror', `/users/edit/${juror.id}`, 'juror-profile', { full: true });
  });

  test('organizer pages', async ({ contextAs, fixture }) => {
    await shot(contextAs, 'organizer', `/roundstat/${fixture.roundBinaryId}`, 'organizer-round-statistics');
    await shot(contextAs, 'organizer', `/byrate/round/${fixture.roundRatingId}/user/0/page/1`, 'organizer-results-by-rate');
    await shot(contextAs, 'organizer', `/filelist/round/${fixture.roundRatingId}/user/0/page/1`, 'organizer-results-file-list');
    await shot(contextAs, 'organizer', `/admin/rounds/${fixture.roundBinaryId}/conflicts`, 'organizer-conflicts', { full: true });
  });

  test('admin pages', async ({ contextAs, fixture }) => {
    const contest = fixture.contestId;
    await shot(contextAs, 'admin', `/admin/rounds?contestId=${contest}`, 'organizer-rounds-list');
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
    const page = await context.newPage();
    await page.goto(`/admin/users?contestId=${contest}`);
    if (await page.getByRole('cell', { name: 'invitee@example.org' }).count() === 0) {
      const form = page.locator('#add-user');
      await form.getByPlaceholder('Full name').fill('Sample Invitee');
      await form.getByPlaceholder('Email').fill('invitee@example.org');
      await form.getByRole('combobox').selectOption({ label: 'Jury' });
      await page.locator('#add-user-button').click();
      await page.waitForLoadState('networkidle');
    }
    await page.screenshot({ path: path.join(OUT_DIR, 'admin-users.png'), fullPage: true });
    await context.close();
  });

  test('root pages', async ({ contextAs }) => {
    await shot(contextAs, 'root', '/contests', 'admin-campaigns');
  });
});
