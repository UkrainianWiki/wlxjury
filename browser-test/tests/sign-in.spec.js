// Signing in through the form and out through the user menu's POST, with its CSRF token; a
// sign-out without the token is refused. Each in a fresh session of the engine's own juror, so
// the saved sessions of the other tests stay signed in.
import { test, expect } from '../lib/fixtures.js';
import { allowFirefoxLeavingGallery, galleryPath, signIn } from '../lib/actions.js';

test.describe('signing in and out', () => {
  // the logo is the one h1; its name is "WLX" and the translated suffix, spoken as one name
  for (const [locale, name] of [['en-US', 'WLX Jury'], ['uk-UA', 'WLX Журі']]) {
    test(`the sign-in page's h1 is the wordmark, named "${name}" (${locale})`, { tag: '@J-03' }, async ({ contextAs }) => {
      const context = await contextAs(null, { locale });
      const page = await context.newPage();
      await page.goto('/auth');
      await expect(page.getByRole('heading', { level: 1, name, exact: true })).toBeVisible();
      await expect(page.locator('nav .wlx-wordmark')).toHaveCount(0);
    });
  }

  test('a juror signs in, then signs out from the user menu', { tag: ['@J-02', '@J-06', '@J-07', '@S-02'] }, async ({ page, fixture, diagnostics, browserName }) => {
    allowFirefoxLeavingGallery(diagnostics, browserName); // U22: the sign-out leaves a gallery
    const juror = fixture.freshJuror;
    await signIn(page, juror);
    const gallery = galleryPath(fixture.roundBinaryId, juror.id);
    await page.goto(gallery);
    await expect(page.getByRole('heading', { level: 1 })).toBeAttached();

    await page.getByRole('button', { name: `Juror ${juror.login.match(/\d+/)[0]}` }).click();
    const logout = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/logout');
    await page.getByRole('button', { name: 'Logout' }).click();
    const form = new URLSearchParams((await logout).postData() ?? '');
    expect(form.get('csrfToken'), 'the sign-out form sends its CSRF token').toBeTruthy();
    // its answer, the login page, before going on (a navigation now would cancel it)
    await expect(page).toHaveURL(/\/auth(\?|$)/);
    await expect(page.getByLabel('Your login')).toBeVisible();

    // signed out: the gallery now asks to sign in, coming back to it afterwards
    await page.goto(gallery);
    await expect(page).toHaveURL(/\/auth\?returnTo=/);
    expect(new URL(page.url()).searchParams.get('returnTo')).toBe(gallery);
    await expect(page.getByLabel('Your login')).toBeVisible();
  });

  test('a sign-out without the CSRF token is refused and the session stays', { tag: ['@S-02', '@J-34'] }, async ({ page, fixture, diagnostics }) => {
    const juror = fixture.freshJuror;
    await signIn(page, juror);
    // the page's own cookies, without the form's token
    const refused = await page.request.post('/logout', { form: {}, maxRedirects: 0 });
    expect(refused.status()).toBe(403);
    expect(await refused.text()).toContain('This page has expired or you were signed out');

    const gallery = galleryPath(fixture.roundBinaryId, juror.id);
    const response = await page.goto(gallery);
    expect(response.status()).toBe(200);
    await expect(page).toHaveURL(new RegExp(`${gallery}$`));
  });

  test('a wrong password keeps the login page, with its error', { tag: '@J-03' }, async ({ page, fixture, diagnostics }) => {
    diagnostics.expectStatus(400); // the login page again, as a 400
    await page.goto('/auth');
    await page.getByLabel('Your login').fill(fixture.freshJuror.login);
    await page.getByLabel('Your password').fill('not the password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/auth/);
    await expect(page.getByRole('alert')).toBeVisible();
  });

  // the sign-in answers with a redirect to the start page, which opens by a GET with its CSRF
  // token: reloading it posts nothing again, and its own Sign out works (refs ba1126f9, 92ca61d7)
  test('a juror of a stopped round sees it closed, reloads, and signs out', { tag: ['@J-02', '@J-06', '@S-02'] }, async ({ page, fixture }) => {
    const juror = fixture.closedRoundJuror;
    test.skip(!juror, "no closed-round juror in the guides' screenshot mode");
    await signIn(page, juror);
    expect(new URL(page.url()).pathname).toBe('/');
    const heading = page.getByRole('heading', { name: 'Your rounds are closed now' });
    await expect(heading).toBeVisible();
    const closed = page.locator('#no-round-closed-rounds li');
    await expect(closed).toHaveCount(1);
    await expect(closed).toContainText('Closed round: Round stopped');

    const reloaded = await page.reload();
    expect(reloaded.status()).toBe(200);
    expect(reloaded.request().method()).toBe('GET');
    await expect(heading).toBeVisible();

    const logout = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/logout');
    await page.locator('form.no-round-logout button').click();
    expect((await logout).status()).toBe(303);
    await expect(page).toHaveURL(/\/auth(\?|$)/);
    await expect(page.getByLabel('Your login')).toBeVisible();
  });

  // a stale sign-in tab on a shared computer, submitted while another account is signed in here:
  // no sign-in and no contest switch, but a page naming the signed-in account, with Continue and
  // Logout (the refused POST's 303)
  test('a stale sign-in tab, submitted while signed in, says who is signed in', { tag: ['@J-02', '@J-06', '@S-02'] }, async ({ page, fixture }) => {
    const juror = fixture.closedRoundJuror;
    test.skip(!juror, "no closed-round juror in the guides' screenshot mode");
    const stale = await page.context().newPage();
    await stale.goto('/auth');
    await signIn(page, juror);
    await expect(page.getByRole('heading', { name: 'Your rounds are closed now' })).toBeVisible();

    await stale.getByLabel('Your login').fill('someone-else@example.org');
    await stale.getByLabel('Your password').fill('their password');
    await stale.getByLabel('Your password').press('Enter');
    await expect(stale).toHaveURL(/\/auth\/signed-in$/);
    await expect(stale.getByRole('heading', { name: 'Already signed in' })).toBeVisible();
    await expect(stale.locator('#signed-in-already')).toContainText(`You are already signed in as ${juror.name}.`);
    await expect(stale.locator('#signed-in-already')).toContainText('To sign in with another account, sign out first.');

    // Continue: the start page, as the signed-in account
    await stale.getByRole('link', { name: 'Continue' }).click();
    await expect(stale).toHaveURL(/^[^?#]*:\/\/[^/]+\/$/);
    await expect(stale.getByRole('heading', { name: 'Your rounds are closed now' })).toBeVisible();

    // Logout, from the same page opened again
    await stale.goto('/auth/signed-in');
    const logout = stale.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/logout');
    await stale.locator('form.signed-in-logout button').click();
    expect((await logout).status()).toBe(303);
    await expect(stale).toHaveURL(/\/auth(\?|$)/);
    await expect(stale.getByLabel('Your login')).toBeVisible();
  });
});
