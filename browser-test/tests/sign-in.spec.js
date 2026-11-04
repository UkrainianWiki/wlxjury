// Signing in through the form and out through the user menu's POST, with its CSRF token; a
// sign-out without the token is refused. Each in a fresh session of the engine's own juror, so
// the saved sessions of the other tests stay signed in.
import { test, expect } from '../lib/fixtures.js';
import { allowFirefoxLeavingGallery, galleryPath, signIn } from '../lib/actions.js';

test.describe('signing in and out', () => {
  test('a juror signs in, then signs out from the user menu', async ({ page, fixture, diagnostics, browserName }) => {
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

  test('a sign-out without the CSRF token is refused and the session stays', async ({ page, fixture, diagnostics }) => {
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

  test('a wrong password keeps the login page, with its error', async ({ page, fixture, diagnostics }) => {
    diagnostics.expectStatus(400); // the login page again, as a 400
    await page.goto('/auth');
    await page.getByLabel('Your login').fill(fixture.freshJuror.login);
    await page.getByLabel('Your password').fill('not the password');
    await page.getByRole('button', { name: 'Sign In' }).click();
    await expect(page).toHaveURL(/\/auth/);
    await expect(page.getByRole('alert')).toBeVisible();
  });
});
