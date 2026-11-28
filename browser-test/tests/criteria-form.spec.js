// The round form's criteria start small (the maintainer's decisions of 2026-11-19; round-form.js
// initCriteriaSlots): the server renders all ten selects for a page without script, the script
// shows only the chosen ones, each with a remove button, and "Add criteria" / "Add another
// criterion". Checked on a new round's form of the fixture's contest, which has no criteria round
// (nothing prefilled), as its admin; nothing is saved. With script off, all ten are shown and the
// add button's row stays hidden. A round of one criterion's gallery stars, below.
import { test, expect } from '../lib/fixtures.js';
import { signIn } from '../lib/actions.js';

test.describe(() => {
  test.use({ role: 'admin' });

  const formPath = (f) => `/admin/rounds/edit?contestId=${f.contestId}`;

  /** The new round's form with a rated rating chosen and the Criteria section opened. */
  async function openCriteria(page, fixture) {
    await page.goto(formPath(fixture));
    await page.locator('#rates').selectOption('10');
    const heading = page.locator('#headingCriteria a.wlx-card-toggle');
    if ((await heading.getAttribute('aria-expanded')) !== 'true') await heading.click();
    await expect(page.locator('#collapseCriteria')).toHaveClass(/\bshow\b/);
  }

  test('opening the criteria catalogue keeps the chosen filters in the draft', { tag: '@A-01' }, async ({ page, fixture }) => {
    await openCriteria(page, fixture);
    const filtering = page.locator('#headingImageFiltering a');
    if ((await filtering.getAttribute('aria-expanded')) !== 'true') await filtering.click();
    const media = page.locator('#mediaType');
    await media.selectOption('image');

    const [catalogue] = await Promise.all([
      page.context().waitForEvent('page'),
      page.getByRole('link', { name: /^Criteria catalogue/ }).click(),
    ]);
    await expect(catalogue).toHaveURL((url) => url.pathname === '/admin/criteria');
    await catalogue.close();
    await expect(page).toHaveURL((url) => url.pathname === '/admin/rounds/edit');
    await expect(media).toHaveValue('image');
    await expect(page.locator('#rates')).toHaveValue('10');
  });

  test('start with "Add criteria" only; add, choose and remove criteria, the focus following', { tag: '@A-01' }, async ({ page, fixture }) => {
    await openCriteria(page, fixture);
    const selects = page.locator('#round-criteria-selects select');
    await expect(selects).toHaveCount(10);
    await expect(page.locator('#round-criteria-selects select:visible')).toHaveCount(0);
    const add = page.getByRole('button', { name: 'Add criteria' });
    await expect(add).toBeVisible();

    await add.click();
    await expect(page.locator('#criteria_0')).toBeVisible();
    await expect(page.locator('#criteria_0')).toBeFocused();
    const another = page.getByRole('button', { name: 'Add another criterion' });
    await expect(another).toBeVisible();
    await page.locator('#criteria_0').selectOption({ index: 1 });
    const first = await page.locator('#criteria_0').inputValue();
    // a criteria round has no worst mark
    await expect(page.locator('#round-worst-mark')).toBeHidden();

    await another.click();
    await expect(page.locator('#criteria_1')).toBeFocused();
    await page.locator('#criteria_1').selectOption({ index: 2 });
    const second = await page.locator('#criteria_1').inputValue();
    await expect(page.locator('#round-criteria-selects select:visible')).toHaveCount(2);

    // the first removed: the second moves up into its place, which gets the focus
    await page.getByRole('button', { name: 'Remove criterion 1', exact: true }).click();
    await expect(page.locator('#round-criteria-selects select:visible')).toHaveCount(1);
    await expect(page.locator('#criteria_0')).toHaveValue(second);
    await expect(page.locator('#criteria_0')).toBeFocused();
    expect(second).not.toBe(first);

    // the last removed: back to "Add criteria", focused, and the worst mark back
    await page.getByRole('button', { name: 'Remove criterion 1', exact: true }).click();
    await expect(page.locator('#round-criteria-selects select:visible')).toHaveCount(0);
    await expect(add).toBeFocused();
    await expect(page.locator('#round-worst-mark')).toBeVisible();
  });

  test('add up to ten criteria by keyboard: Tab past each remove button to the add button, Enter', { tag: '@A-01' }, async ({ page, fixture }) => {
    await openCriteria(page, fixture);
    await page.getByRole('button', { name: 'Add criteria' }).focus();
    await page.keyboard.press('Enter');
    for (let i = 1; i < 10; i++) {
      await expect(page.locator(`#criteria_${i - 1}`)).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(page.getByRole('button', { name: `Remove criterion ${i}`, exact: true })).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(page.getByRole('button', { name: 'Add another criterion' })).toBeFocused();
      await page.keyboard.press('Enter');
    }
    await expect(page.locator('#criteria_9')).toBeFocused();
    await expect(page.locator('#round-criteria-selects select:visible')).toHaveCount(10);
    await expect(page.locator('#criteria-add-row')).toBeHidden();
  });

  test.describe('without script', () => {
    test.use({ javaScriptEnabled: false });

    test('show all ten selects and no add button', { tag: '@A-01' }, async ({ page, fixture }) => {
      await page.goto(formPath(fixture));
      // without script the collapsed section can't open: its selects are in the page, not shown
      const selects = page.locator('#round-criteria-selects select');
      await expect(selects).toHaveCount(10);
      await expect(page.locator('#round-criteria-selects [hidden]')).toHaveCount(0);
      await expect(page.locator('#criteria-add-row')).toHaveAttribute('hidden', '');
      await expect(page.locator('.wlx-criterion-remove')).toHaveCount(0);
    });
  });
});

// A round of one criterion is rated as a rated round (the maintainer's decisions of 2026-11-20):
// its juror's gallery names the criterion once, above the files, and has stars under each file,
// which rate the criterion; the large view shows the stored rate on the criterion's stars. This
// engine's juror of the fixture's round of its own (UiShotsServer oneCriterion): a vote of a
// rate other than the file's current one, so a rerun on the same server changes it too.
test.describe('a round of one criterion', () => {
  test('the gallery names the criterion once, its stars rate it, the large view shows it', { tag: '@J-23' }, async ({ contextAs, fixture }) => {
    test.skip(!fixture.oneCriterion, 'no round of one criterion in the guides\' screenshot mode');
    const { roundId, juror } = fixture.oneCriterion;
    const context = await contextAs(null);
    const page = await context.newPage();
    await signIn(page, juror);
    await page.goto(`/byrate/round/${roundId}/user/${juror.id}/page/1`);
    await expect(page.locator('.wlx-single-criterion')).toHaveText('Rate: Scientific value');
    await expect(page.getByText('Scientific value')).toHaveCount(1);
    const first = page.locator('li.gallerybox').first();
    const stars = first.locator('.rank-line .wlx-tile-stars');
    await expect(stars).toBeVisible();
    await expect(page.locator('li.gallerybox .wlx-tile-stars')).toHaveCount(3);
    const pageId = await first.getAttribute('id');
    const current = await stars.evaluate((el) => Number(window.jQuery(el).raty('score')) || 0);
    const rate = current === 7 ? 6 : 7;
    const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/rate/round/'));
    await stars.locator(`[data-alt="${rate}"]`).click();
    const answer = await response;
    expect(answer.status()).toBe(200);
    expect(answer.url()).toContain('?criteria=1');
    await expect(first.locator('.wlx-rate-text')).toHaveText(String(rate));

    // stored: the vote's rate in the gallery after a reload, the criterion's in the large view
    await page.reload();
    await expect(page.locator(`li.gallerybox[id="${pageId}"] .wlx-rate-text`)).toHaveText(String(rate));
    await page.goto(`/large/round/${roundId}/user/${juror.id}/pageid/${pageId}`);
    await expect(page.locator('.wlx-criterion-name')).toHaveText('Scientific value');
    await expect(page.locator('#rate-number1')).toHaveText(String(rate));
    await context.close(); // also closed by the fixture
  });
});
