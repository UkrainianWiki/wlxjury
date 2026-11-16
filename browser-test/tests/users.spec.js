// The users page's "Find an existing account" (accounts plan A-1): the search is a script's POST
// (the text never in a URL), an account outside the admin's contests shows a masked address, and
// "Add to this contest" adds it by id. The engine's own target account (UiShotsServer) has an
// unverified email and a password, so the add invites it (no membership); the test then cancels
// that invitation, so a rerun against the same server finds the account as before.
import { test, expect } from '../lib/fixtures.js';
import { one } from '../lib/actions.js';

const isSearch = (r) => new URL(r.url()).pathname === '/admin/users/search';

test.describe('an admin', () => {
  test.use({ role: 'admin' });

  test('finds an account outside the contest by its name, masked, and adds it to the contest', { tag: '@A-22' },
    async ({ page, fixture }) => {
      const target = fixture.searchTarget;
      expect(target, 'server.json lists searchTargets: restart the fixture').toBeTruthy();
      await page.goto(`/admin/users?contestId=${fixture.contestId}`);
      const box = await one(page.getByRole('region', { name: 'Find an existing account' }));
      const text = box.getByLabel('Name, wiki account or email');

      // a part of an address outside the admin's contests finds nothing; the text goes in the body
      await text.fill(target.login.split('@')[0]);
      const refused = page.waitForResponse(isSearch);
      await box.getByRole('button', { name: 'Search' }).click();
      const response = await refused;
      expect(response.status()).toBe(200);
      expect(response.request().method()).toBe('POST');
      expect(response.url()).not.toContain('search-target');
      expect(response.request().postData()).toContain('search-target');
      await expect(box.getByRole('status')).toHaveText('No account found.');

      // its name finds it, the address masked, never in full
      await text.fill(target.name);
      const found = page.waitForResponse(isSearch);
      await box.getByRole('button', { name: 'Search' }).click();
      expect((await found).status()).toBe(200);
      const row = await one(box.getByRole('row').filter({ hasText: target.name }));
      await expect(row.getByRole('cell', { name: 's***@ui-shots.test', exact: true })).toBeVisible();
      await expect(row).not.toContainText(target.login);
      // no link to its edit page: it is not a member of this contest
      await expect(row.getByRole('link')).toHaveCount(0);

      // added as a juror: an account that hasn't proven its email gets an invitation
      await expect(row.getByLabel('Role')).toHaveValue('jury');
      await expect(row.getByLabel('Send an invitation email')).not.toBeChecked();
      // never a mail nobody chose: its owner sees a notice in the tool, mailed only if ticked
      await expect(row.getByLabel('Tell its owner by email')).not.toBeChecked();
      await row.getByRole('button', { name: 'Add to this contest' }).click();
      await expect(page).toHaveURL((url) => url.pathname === '/admin/users');
      await expect(page.getByText('Invited (role given once they accept): 1.')).toBeVisible();
      await expect(page.getByText('Its owner will see a notice in the tool when they sign in.')).toBeVisible();

      // the invitation waits, unsent, in the invitations list: cancelled here, so a rerun starts over
      const invitation = await one(page.getByRole('row').filter({ hasText: target.login })
        .filter({ has: page.getByRole('button', { name: 'Cancel', exact: true }) }));
      await expect(invitation).toContainText('Juror');
      await invitation.getByRole('button', { name: 'Cancel' }).click();
      await expect(page.getByText('The invitation has been cancelled.')).toBeVisible();
    });
});

// The Edit user form's "wiki account taken" panel (accounts plan A-2): a save giving the engine's
// member (no role, no wiki account) the wiki account of its account in no contest is refused, and
// the panel shows that account, masked; "Keep them separate" puts the wiki account back in the
// form without a request, and "Add that account to this contest instead" adds it by id (an
// invitation, cancelled again so a rerun starts over). The edited account is never saved.
test.describe('an admin editing a member', () => {
  test.use({ role: 'admin' });

  test('sees the account that has the wiki account, keeps them separate, then adds that account instead',
    { tag: '@A-23' }, async ({ page, fixture, diagnostics }) => {
      // the refused save is answered 400 with the form and the panel
      diagnostics.expectStatus(400);
      const pair = fixture.wikiTaken;
      expect(pair, 'server.json lists wikiTaken: restart the fixture').toBeTruthy();
      const { edited, target } = pair;
      await page.goto(`/users/edit/${edited.id}?contestId=${fixture.contestId}`);
      const wiki = page.getByLabel('Wiki account', { exact: true });
      await expect(wiki).toHaveValue('');

      const refusedSave = async () => {
        await wiki.fill(target.wiki);
        await page.getByRole('button', { name: 'Save', exact: true }).click();
        await expect(page.getByText('Another account already has this wiki account.')).toBeVisible();
        return one(page.getByRole('region', { name: `Other accounts with the wiki account ${target.wiki}` }));
      };

      let panel = await refusedSave();
      const item = await one(panel.getByRole('listitem').filter({ hasText: target.name }));
      // outside the admin's contests: masked, none of its contests, nothing counted
      await expect(item).toContainText('t***@ui-shots.test');
      await expect(item).not.toContainText(target.login);
      await expect(item).toContainText('No votes, comments or rounds in your contests.');
      await expect(item.getByRole('link')).toHaveCount(0);

      // Keep them separate: the wiki account as stored, no request, the panel gone
      let requests = 0;
      const count = () => { requests += 1; };
      page.on('request', count);
      await panel.getByRole('link', { name: 'Keep them separate' }).click();
      await expect(panel).toBeHidden();
      await expect(wiki).toHaveValue('');
      await expect(wiki).toBeFocused();
      page.off('request', count);
      expect(requests).toBe(0);

      // again, then add that account instead, as a juror, both mails unticked
      panel = await refusedSave();
      const again = await one(panel.getByRole('listitem').filter({ hasText: target.name }));
      await expect(again.getByLabel('Send an invitation email')).not.toBeChecked();
      await expect(again.getByLabel('Tell its owner by email')).not.toBeChecked();
      await again.getByLabel('Role').selectOption({ label: 'Juror' });
      await again.getByRole('button', { name: 'Add that account to this contest instead' }).click();
      await expect(page).toHaveURL((url) => url.pathname === '/admin/users');
      await expect(page.getByText('Invited (role given once they accept): 1.')).toBeVisible();
      await expect(page.getByText('Its owner will see a notice in the tool when they sign in.')).toBeVisible();

      // the invitation waits, unsent: cancelled here, so a rerun starts over
      const invitation = await one(page.getByRole('row').filter({ hasText: target.login })
        .filter({ has: page.getByRole('button', { name: 'Cancel', exact: true }) }));
      await expect(invitation).toContainText('Juror');
      await invitation.getByRole('button', { name: 'Cancel' }).click();
      await expect(page.getByText('The invitation has been cancelled.')).toBeVisible();
    });
});
