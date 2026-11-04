// The colour modes (UI §3.1): the login page's switch sets the cookie without a request; the
// navbar's switch is a CSRF POST that stores the mode; and `auto` follows the browser's scheme
// from the first frame, light and dark, with no frame in the other mode (the "Firefox theme
// flash" check of the roadmap's "Every release", now in all three engines).
import { test, expect } from '../lib/fixtures.js';
import { galleryPath, signIn } from '../lib/actions.js';

/** Before the page's scripts: the mode (data-bs-theme) of every frame until the page has loaded. */
function framesProbe() {
  window.wlxFrames = [];
  (function frame() {
    requestAnimationFrame(() => {
      const root = document.documentElement;
      if (root && document.body) window.wlxFrames.push(root.getAttribute('data-bs-theme'));
      if (document.readyState !== 'complete' || window.wlxFrames.length < 2) frame();
    });
  }());
}

test.describe('the colour mode', () => {
  test('the login page\'s switch sets the mode at once, without a request', async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'theme', value: 'light', url: baseURL }]);
    await page.goto('/auth');
    await expect(page.locator('html')).toHaveAttribute('data-bs-theme', 'light');
    const group = page.getByRole('group', { name: /Theme/ });
    const requests = [];
    page.on('request', (r) => requests.push(r.url()));
    await group.getByRole('button', { name: 'Dark' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-bs-theme', 'dark');
    await expect(group.getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true');
    await expect(group.getByRole('button', { name: 'Light' })).toHaveAttribute('aria-pressed', 'false');
    expect(requests, 'no request').toEqual([]);
    const theme = (await context.cookies()).find((c) => c.name === 'theme');
    expect(theme?.value).toBe('dark');
    // and the next page is rendered dark
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-bs-theme', 'dark');
  });

  test('the navbar\'s switch posts the mode, which the next pages show', async ({ page, fixture }) => {
    const juror = fixture.freshJuror;
    await signIn(page, juror);
    const gallery = galleryPath(fixture.roundBinaryId, juror.id);
    await page.goto(gallery);
    const current = await page.locator('html').getAttribute('data-theme-pref');
    const [next, label] = current === 'dark' ? ['light', 'Light'] : ['dark', 'Dark'];

    await page.getByRole('button', { name: /^Theme: / }).click();
    const posted = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/theme');
    await page.getByRole('group', { name: 'Theme' }).getByRole('button', { name: label }).click();
    const form = new URLSearchParams((await posted).postData() ?? '');
    expect(form.get('theme')).toBe(next);
    expect(form.get('csrfToken'), 'the switch sends its CSRF token').toBeTruthy();

    // back on the same page, in the new mode, the switch saying so
    await expect(page).toHaveURL(new RegExp(`${gallery}$`));
    await expect(page.locator('html')).toHaveAttribute('data-bs-theme', next);
    await expect(page.getByRole('button', { name: `Theme: ${label}` })).toBeAttached();
    await page.goto('/auth');
    await expect(page.locator('html')).toHaveAttribute('data-bs-theme', next);

    // back to light for the next run
    await page.goto(gallery);
    if (next !== 'light') {
      await page.getByRole('button', { name: /^Theme: / }).click();
      await page.getByRole('group', { name: 'Theme' }).getByRole('button', { name: 'Light' }).click();
      await expect(page.locator('html')).toHaveAttribute('data-bs-theme', 'light');
    }
  });

  for (const scheme of ['light', 'dark']) {
    test(`auto shows the ${scheme} scheme from the first frame, never the other`, async ({ contextAs, baseURL, fixture }) => {
      for (const [role, path] of [
        [null, '/auth'],
        ['juror', galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id)],
      ]) {
        const context = await contextAs(role, { colorScheme: scheme });
        await context.addCookies([{ name: 'theme', value: 'auto', url: baseURL }]);
        await context.addInitScript(framesProbe);
        const page = await context.newPage();
        await page.goto(path, { waitUntil: 'load' });
        await expect.poll(() => page.evaluate(() => window.wlxFrames.length), { message: `frames of ${path}` })
          .toBeGreaterThanOrEqual(2);
        const frames = await page.evaluate(() => window.wlxFrames);
        expect(new Set(frames), `${path}: the mode of each frame until loaded`).toEqual(new Set([scheme]));
        await expect(page.locator('html')).toHaveAttribute('data-theme-pref', 'auto');
        // the page's background is the scheme's (the variables applied, not only the attribute)
        const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        const [r, g, b] = background.match(/\d+/g).map(Number);
        const light = (r + g + b) / 3 > 127;
        expect(light ? 'light' : 'dark', `${path}: body background ${background}`).toBe(scheme);
      }
    });
  }
});
