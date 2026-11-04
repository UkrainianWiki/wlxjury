// A juror sees only their own votes, and no organizer page; an organizer (the role) no admin
// page: each refusal is the 403 page with its reference (secure-coding §9, negative cases).
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, largePath } from '../lib/actions.js';

/** The access-denied page: its heading, and the reference the user can quote. */
async function expectAccessDenied(page, response) {
  expect(response.status()).toBe(403);
  await expect(page.getByRole('heading', { level: 1, name: 'Access denied' })).toBeVisible();
  await expect(page.getByText(/^Reference: \S+, /)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign out and sign in with another account' })).toBeVisible();
}

test.describe('a juror', () => {
  test.use({ role: 'juror' });

  test('can\'t see another juror\'s votes, in the gallery or the large view', async ({ page, fixture, diagnostics }) => {
    diagnostics.expectStatus(403);
    const other = fixture.otherJuror;
    for (const path of [
      galleryPath(fixture.roundBinaryId, other.id),
      galleryPath(fixture.roundRatingId, other.id),
      largePath(fixture.roundBinaryId, other.id, other.pageId),
      // all the jurors' votes together
      `/byrate/round/${fixture.roundRatingId}/user/0/page/1`,
    ]) {
      await test.step(path, async () => {
        const response = await page.goto(path);
        await expectAccessDenied(page, response);
        // none of the other juror's votes on the page
        await expect(page.getByRole('button', { name: 'Selected' })).toHaveCount(0);
        await expect(page.locator('#gallery')).toHaveCount(0);
      });
    }
  });

  test('can\'t open an organizer page', async ({ page, fixture, diagnostics }) => {
    diagnostics.expectStatus(403);
    for (const path of [
      `/admin/rounds?contestId=${fixture.contestId}`,
      `/admin/users?contestId=${fixture.contestId}`,
      `/admin/rounds/edit?id=${fixture.roundBinaryId}&contestId=${fixture.contestId}`,
    ]) {
      await test.step(path, async () => {
        await expectAccessDenied(page, await page.goto(path));
        await expect(page.getByText('This page is for: Admin, Root.')).toBeVisible();
      });
    }
  });
});

test.describe('an organizer', () => {
  test.use({ role: 'organizer' });

  test('can\'t open the round form, an admin\'s page', async ({ page, fixture, diagnostics }) => {
    diagnostics.expectStatus(403);
    await expectAccessDenied(page, await page.goto(`/admin/rounds/edit?id=${fixture.roundBinaryId}&contestId=${fixture.contestId}`));
  });
});
