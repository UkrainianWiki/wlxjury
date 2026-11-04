// The organizers' pages: a round's results for the organizer role, and the round form for the
// contest's admin, opened, changed and left with Cancel (Montage's edit-then-cancel flows), so no
// round is saved and no distribution job starts.
import { test, expect } from '../lib/fixtures.js';

test.describe('an organizer', () => {
  test.use({ role: 'organizer' });

  test('opens a round\'s statistics, with a row per juror', async ({ page, fixture }) => {
    await page.goto(`/roundstat/${fixture.roundBinaryId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Statistics' })).toBeAttached();
    await expect(page.getByRole('columnheader', { name: 'Selected' })).toBeVisible();
    await expect(page.getByRole('cell', { name: fixture.accounts.juror.login, exact: true })).toBeVisible();
    // the rounds' links: the current one marked
    await expect(page.getByRole('link', { name: /Binary Round/ })).toHaveAttribute('aria-current', 'page');
  });

  test('opens the rated round\'s files by rate, of all the jurors', async ({ page, fixture }) => {
    await page.goto(`/byrate/round/${fixture.roundRatingId}/user/0/page/1`);
    await expect(page.getByRole('heading', { level: 1, name: 'By rate' })).toBeAttached();
    // a file per row, each with its rate, read-only: no vote buttons
    await expect(page.getByRole('link', { name: /\.jpe?g/i }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /^Select image/ })).toHaveCount(0);
  });
});

test.describe('an admin', () => {
  test.use({ role: 'admin' });

  test('opens a round\'s form from the rounds list, with its jurors\' table', async ({ page, fixture }) => {
    await page.goto(`/admin/rounds?contestId=${fixture.contestId}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Campaign');
    await page.getByRole('row', { name: /Binary Round/ }).getByRole('link', { name: '1', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit round' })).toBeVisible();
    await expect(page.getByLabel('Round name (optional)')).toHaveValue('Binary Round');

    // the jurors' panel loads its table when opened (round-form.js)
    const table = page.waitForResponse((r) => new URL(r.url()).pathname === `/roundstat/${fixture.roundBinaryId}/table`);
    await page.getByRole('button', { name: 'Jurors', exact: true }).click();
    expect((await table).status()).toBe(200);
    await expect(page.getByRole('cell', { name: fixture.accounts.juror.login, exact: true })).toBeVisible();
  });

  test('changes a round\'s name, cancels, and the round keeps its name', async ({ page, fixture }) => {
    const form = `/admin/rounds/edit?id=${fixture.roundBinaryId}&contestId=${fixture.contestId}`;
    await page.goto(form);
    const name = page.getByLabel('Round name (optional)');
    await expect(name).toHaveValue('Binary Round');
    await name.fill('Renamed and cancelled');
    const posts = [];
    page.on('request', (r) => { if (r.method() === 'POST') posts.push(r.url()); });
    await page.getByRole('link', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/\/admin\/rounds/);
    expect(posts, 'Cancel saves nothing').toEqual([]);
    await page.goto(form);
    await expect(page.getByLabel('Round name (optional)')).toHaveValue('Binary Round');
  });

  test('opens the new round form, whose fields follow the choices', async ({ page, fixture }) => {
    await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Create round' })).toBeVisible();
    await expect(page.getByLabel('Round name (optional)')).toHaveValue('');
    await expect(page.getByLabel('Rating type')).toBeVisible();
  });
});

test.describe('root', () => {
  test.use({ role: 'root' });

  test('opens the campaigns page', async ({ page }) => {
    await page.goto('/contests');
    await expect(page.getByRole('heading', { level: 1, name: 'Campaigns' })).toBeAttached();
    await expect(page.getByRole('cell', { name: /Wiki Loves Monuments/ }).first()).toBeVisible();
  });
});
