// The colour modes (UI §3.1): the login page's switch sets the cookie without a request; the
// navbar's switch is a CSRF POST that stores the mode; and `auto` follows the browser's scheme
// from the first frame, light and dark, with no frame in the other mode (the "Firefox theme
// flash" check of the roadmap's "Every release", now in all three engines).
import { test, expect } from '../lib/fixtures.js';
import { allowFirefoxLeavingGallery, galleryPath, signIn } from '../lib/actions.js';

/** Before the page's scripts: from the first animation frame (whose callbacks run before it is
  * painted) until the page has loaded, each frame's mode attribute, the root's computed
  * color-scheme (the canvas's colour before any background) and the page's computed background:
  * the body's once it exists, else the root's. */
function framesProbe() {
  window.wlxFrames = [];
  (function frame() {
    requestAnimationFrame(() => {
      const root = document.documentElement;
      const painted = document.body || root;
      window.wlxFrames.push({
        theme: root.getAttribute('data-bs-theme'),
        colorScheme: getComputedStyle(root).colorScheme,
        background: getComputedStyle(painted).backgroundColor,
        body: !!document.body,
      });
      if (document.readyState !== 'complete' || window.wlxFrames.length < 2) frame();
    });
  }());
}

/** "light" or "dark" for an rgb(a) colour, null for a transparent one (the canvas shows). */
function shade(color) {
  const [r, g, b, a = 1] = color.match(/[\d.]+/g).map(Number);
  if (a === 0) return null;
  return (r + g + b) / 3 > 127 ? 'light' : 'dark';
}

test.describe('the colour mode', { tag: '@J-30' }, () => {
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

  test('the navbar\'s switch posts the mode, which the next pages show', async ({ page, fixture, diagnostics, browserName }) => {
    allowFirefoxLeavingGallery(diagnostics, browserName); // U22: the switch's POST leaves a gallery
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
        // every frame: the scheme's mode, the canvas in the scheme (a light canvas is also the
        // default, "normal"), and any background drawn in the scheme's shade
        const wrong = frames.filter((f) => f.theme !== scheme
          || !(f.colorScheme === scheme || (scheme === 'light' && f.colorScheme === 'normal'))
          || (shade(f.background) !== null && shade(f.background) !== scheme));
        expect(wrong, `${path}: frames not in the ${scheme} mode, of ${frames.length}`).toEqual([]);
        expect(frames.some((f) => f.body && shade(f.background) === scheme), `${path}: a frame with the page`).toBe(true);
        await expect(page.locator('html')).toHaveAttribute('data-theme-pref', 'auto');
      }
    });
  }
});

/** WCAG contrast of two rgb(a) strings, the foreground drawn over the (opaque) background. */
function contrast(fg, bg) {
  const parse = (c) => c.match(/[\d.]+/g).map(Number);
  const [br, bgG, bb] = parse(bg);
  const [r, g, b, a = 1] = parse(fg);
  const lum = (rgb) => {
    const [x, y, z] = rgb.map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * x + 0.7152 * y + 0.0722 * z;
  };
  const over = [r, g, b].map((v, i) => v * a + [br, bgG, bb][i] * (1 - a));
  const [hi, lo] = [lum(over), lum([br, bgG, bb])].sort((p, q) => q - p);
  return (hi + 0.05) / (lo + 0.05);
}

// the navbar's selected and rejected tabs (own votes) in the active tab's background, which is
// the lightest ground their text sits on: 4.5:1 in both modes (the colours come from theme.css)
test.describe('the vote tabs\' contrast', { tag: '@J-30' }, () => {
  for (const mode of ['light', 'dark']) {
    test(`the active selected and rejected tabs read at 4.5:1 in ${mode} mode`, async ({ context, page, fixture, baseURL }) => {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
      for (const kind of ['selected', 'rejected']) {
        const { color, active, bar } = await page.evaluate((k) => {
          const nav = document.querySelector('nav.navbar');
          const ul = nav.querySelector('.navbar-nav');
          const li = document.createElement('li');
          li.className = `nav-item vote-${k}`;
          li.innerHTML = '<a class="nav-link active" href="#">Probe</a>';
          ul.appendChild(li);
          const link = li.querySelector('a');
          const s = getComputedStyle(link);
          const out = { color: s.color, active: s.backgroundColor, bar: getComputedStyle(nav).backgroundColor };
          li.remove();
          return out;
        }, kind);
        // the bar's opaque colour under the active tab's translucent one
        const ratio = contrast(color, (() => {
          const [r, g, b] = bar.match(/[\d.]+/g).map(Number);
          const [ar, ag, ab, aa = 1] = active.match(/[\d.]+/g).map(Number);
          return `rgb(${ar * aa + r * (1 - aa)}, ${ag * aa + g * (1 - aa)}, ${ab * aa + b * (1 - aa)})`;
        })());
        expect(ratio, `${mode}: the ${kind} tab's text ${color} on the active tab`).toBeGreaterThanOrEqual(4.5);
      }
    });
  }
});
