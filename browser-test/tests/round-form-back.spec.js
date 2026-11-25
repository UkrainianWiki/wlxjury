// Back after saving a new round (UI-path plan §5 Q6, the maintainer's decision of 2026-11-25): the
// stale new-round form is never shown again. The form's page puts its URL with the form's one-time
// token in its history entry (round-form-token.js) and is sent `Cache-Control: no-store`; Back asks
// the server for that URL, which opens the saved round's Edit page with "Round N is saved. You can
// change its settings here." A browser that restores the page from its back/forward cache reloads
// it (`pageshow` with `persisted`), with the same result. So no second round is created and nothing
// is refused (the 11 refused saves of 2026-11-25 began with Back and Save).
//
// In the routeless browser (lib/fixtures.js routelessAs): Playwright's routes turn the HTTP cache
// off and its Chromium runs without the back/forward cache, so a routed page would pass without
// either mechanism (testing practices §10.3).
//
// It writes to the shared fixture: one round per engine and button, named after it, in review and
// hidden from every juror (as review.spec.js's), its filter one monument so its fill is small.
import { test, expect } from '../lib/fixtures.js';

const MONUMENT = '73-250-0001';

/** The new-round form filled with `name` and one monument. */
async function newRound(page, contestId, name) {
  await page.goto(`/admin/rounds/edit?contestId=${contestId}`);
  await page.getByLabel('Round name (optional)').fill(name);
  const toggle = page.getByRole('button', { name: 'Media filtering' });
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click();
  await page.locator('#monumentIds').fill(MONUMENT);
}

for (const [button, landing] of [['Review images first', /^\/admin\/rounds$/], ['Choose jurors next', /^\/admin\/rounds\/\d+\/jurors$/]]) {
  test(`Back after "${button}" opens the saved round's Edit page, not the stale form`, { tag: ['@A-15', '@A-21'] },
    async ({ routelessAs, fixture, browserName }) => {
      test.setTimeout(120_000);
      const context = await routelessAs('admin');
      const page = await context.newPage();
      const name = `Back ${button.split(' ')[0]} ${browserName} ${Date.now()}`;
      await newRound(page, fixture.contestId, name);
      // the form's history entry already carries its token
      await expect.poll(() => new URL(page.url()).searchParams.get('submitToken')).toMatch(/^[0-9a-f-]{36}$/);

      // saved: the landing page says so, as served (its job status script may reload it)
      const [landed] = await Promise.all([
        page.waitForResponse((r) => r.request().method() === 'GET' && landing.test(new URL(r.url()).pathname)),
        page.getByRole('button', { name: button }).click(),
      ]);
      expect(await landed.text()).toMatch(/id="flash-success"[^>]*>\s*Round \d+ \([^)]*\) is saved\. Its matching files are being found\./);
      await page.waitForURL((url) => landing.test(url.pathname));

      // Back: the saved round's Edit page with its line (in the page as served: the job status script
      // reloads it, without the line, when the round's matching ends), never the filled new-round form
      const isEdit = (url) => url.pathname === '/admin/rounds/edit' && /^\d+$/.test(url.searchParams.get('id') ?? '');
      const [edit] = await Promise.all([
        page.waitForResponse((r) => r.request().method() === 'GET' && isEdit(new URL(r.url()))),
        page.goBack(),
      ]);
      expect(await edit.text()).toMatch(/id="flash-success"[^>]*>\s*Round \d+ is saved\. You can change its settings here\.\s*</);
      await expect(page).toHaveURL(isEdit);
      await expect(page.getByRole('heading', { level: 1, name: 'Edit round' })).toBeVisible();
      await expect(page.getByLabel('Round name (optional)')).toHaveValue(name);
      await expect(page.locator('input[name="submitToken"]')).toHaveCount(0);
      await context.close();
    });
}
