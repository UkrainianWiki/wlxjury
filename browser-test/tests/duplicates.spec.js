// The duplicates report (the account join plan's J-1): the admin opens it from the users page and
// sees a group of accounts sharing a wiki account written three ways (UiShotsServer: its first
// account a member of the contest without a role, its email in full; the others in no contest,
// masked), keeps one account (a screening portion) apart from the others, then the remaining
// pair, each a form POST with the CSRF token; the group is gone for good. Each run uses the
// engine's first group still listed (four per engine), so a rerun against the same server takes
// the next.
import { test, expect } from '../lib/fixtures.js';
import { one } from '../lib/actions.js';

// the page's message after a Keep separate
const kept = (page) => page.getByRole('status').filter({ hasText: 'Kept separate: these accounts are no longer listed together.' });
const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

test.describe('an admin', () => {
  test.use({ role: 'admin' });

  test('lists accounts sharing a wiki account, masked outside the contest, and keeps them separate', { tag: '@A-24' },
    async ({ page, fixture }) => {
      test.skip(fixture.duplicateGroups.length === 0, 'the fixture has no duplicates (the guides\' screenshot mode)');
      await page.goto(`/admin/users?contestId=${fixture.contestId}`);
      await page.getByRole('link', { name: 'Possible duplicates' }).click();
      await expect(page).toHaveURL((url) => url.pathname === '/admin/duplicates');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Possible duplicate accounts');

      // the engine's first group still listed
      const groupOf = (wiki) => page.getByRole('region', { name: new RegExp(`^Same wiki account: ${escape(wiki)} ·`) });
      let wiki;
      for (const name of fixture.duplicateGroups) {
        if (await groupOf(name).count() === 1) { wiki = name; break; }
      }
      expect(wiki, 'every group of this engine is kept apart already: restart the fixture').toBeTruthy();
      const group = await one(groupOf(wiki));
      const [n, k] = wiki.replace('Dup Target ', '').split('-');
      const name = (x) => `Duplicate ${n}-${k} ${x}`;
      const rows = group.getByRole('row').filter({ hasText: `Duplicate ${n}-${k} ` });
      await expect(rows).toHaveCount(3);

      // the member's address in full and its edit page; the others masked, never their contests
      const member = await one(rows.filter({ hasText: name('A') }));
      await expect(member).toContainText(`dup-${n}-${k}-a@ui-shots.test`);
      await expect(member.getByRole('link', { name: name('A') })).toBeVisible();
      const outside = await one(rows.filter({ hasText: name('C') }));
      await expect(outside).toContainText('d***@ui-shots.test');
      await expect(outside).not.toContainText(`dup-${n}-${k}-c@`);
      await expect(outside.getByRole('link')).toHaveCount(0);
      await expect(group).toContainText(`dup_target ${n}-${k}`);

      // a screening portion: one account kept apart from the others; the other two stay listed
      await outside.getByRole('button', { name: 'Keep separate from the others' }).click();
      await expect(kept(page)).toBeVisible();
      const pair = await one(groupOf(wiki));
      await expect(pair.getByRole('row').filter({ hasText: `Duplicate ${n}-${k} ` })).toHaveCount(2);
      await expect(pair).not.toContainText(name('C'));

      // then the pair: the group is gone, also after a reload
      await pair.getByRole('button', { name: 'Keep separate', exact: true }).click();
      await expect(groupOf(wiki)).toHaveCount(0);
      await expect(kept(page)).toBeVisible();
      await page.reload();
      await expect(groupOf(wiki)).toHaveCount(0);
      await expect(page.getByText(name('B'))).toHaveCount(0);
    });
});
