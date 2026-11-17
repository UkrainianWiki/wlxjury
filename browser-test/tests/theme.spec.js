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

/** A colour drawn over another (rgb(a) strings): the opaque result, as rgb(). */
function over(fg, bg) {
  const [r, g, b, a = 1] = fg.match(/[\d.]+/g).map(Number);
  const [br, bgG, bb] = bg.match(/[\d.]+/g).map(Number);
  return `rgb(${r * a + br * (1 - a)}, ${g * a + bgG * (1 - a)}, ${b * a + bb * (1 - a)})`;
}

/** The computed value of a `property` set to `value` (e.g. a var()) on a probe in `parent`. */
async function resolved(page, property, value, parent = 'body') {
  return page.evaluate(([prop, val, sel]) => {
    const probe = document.createElement('span');
    probe.style.setProperty(prop, val);
    document.querySelector(sel).append(probe);
    const out = getComputedStyle(probe).getPropertyValue(prop);
    probe.remove();
    return out;
  }, [property, value, parent]);
}

// The navbar's Votes toggle on the selected or the rejected (?filter=): the real button, which is
// never .active, in its vote colour at rest, pointed at and with its menu open (the hover grey
// under it then, never the selected blue), at 4.5:1 on what is under it, in both modes (the
// colours come from theme.css)
test.describe('the Votes toggle\'s contrast', { tag: '@J-30' }, () => {
  test.use({ role: 'juror' }); // signed out, the gallery is the sign-in page, which has no tabs bar
  for (const mode of ['light', 'dark']) {
    test(`the Votes toggle keeps its vote colour at rest, pointed at and open, at 4.5:1, in ${mode} mode`, async ({ context, page, fixture, baseURL }) => {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      for (const kind of ['selected', 'rejected']) {
        await page.goto(`${galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id)}?filter=${kind}`);
        const toggle = page.locator(`#votes-menu.vote-${kind} > button.nav-link`);
        await expect(toggle).toBeVisible();
        await expect(toggle).not.toHaveClass(/\bactive\b/);
        const tint = await resolved(page, 'color', `var(--wlx-${kind})`, 'nav.navbar');
        const hoverBg = await resolved(page, 'background-color', 'var(--wlx-hover-bg)', 'nav.navbar');
        const read = () => toggle.evaluate((el) => {
          const s = getComputedStyle(el);
          return { color: s.color, bg: s.backgroundColor, bar: getComputedStyle(el.closest('nav.navbar')).backgroundColor };
        });
        // Bootstrap's nav links fade their colours (.15 s): each state read once it has settled
        const settled = async (bg) => {
          await expect.poll(async () => (await read()).bg).toBe(bg);
          return read();
        };
        await page.mouse.move(0, 0);
        const states = { rest: await settled('rgba(0, 0, 0, 0)') };
        await toggle.hover();
        states.pointed = await settled(hoverBg);
        await toggle.click();
        await expect(toggle).toHaveClass(/\bshow\b/);
        await page.mouse.move(0, 0);
        states.open = await settled(hoverBg);
        for (const [state, { color, bg, bar }] of Object.entries(states)) {
          expect(color, `${mode}: the ${kind} toggle's text ${state}`).toBe(tint);
          const ratio = contrast(color, over(bg, bar));
          expect(ratio, `${mode}: the ${kind} toggle's text ${color} ${state}`).toBeGreaterThanOrEqual(4.5);
        }
        await page.keyboard.press('Escape');
      }
    });
  }
});

// One "selected" highlight (Codex progressive-subtle fill, progressive text and border) and one
// hover colour (neutral grey), distinct from each other (the 2026-11-17 Codex restyle): the
// navbar's active item and the pager's range box are the selected one, a pointed-at navbar item
// and pager link the grey; selected text reads at 4.5:1 on its own fill, and the selected border
// is 3:1 against the page.
test.describe('the selected and hover colours', { tag: '@J-30' }, () => {
  test.use({ role: 'admin' });
  for (const mode of ['light', 'dark']) {
    test(`the selected highlight and the hover grey differ and read in ${mode} mode`, async ({ context, page, fixture, baseURL }) => {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      await page.goto(`/admin/rounds?contestId=${fixture.contestId}`);
      const style = (loc, props) => loc.evaluate((el, ps) => {
        const s = getComputedStyle(el);
        return Object.fromEntries(ps.map((p) => [p, s[p]]));
      }, props);
      const pageBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      const navBg = await page.evaluate(() => getComputedStyle(document.querySelector('nav.navbar')).backgroundColor);
      const active = page.locator('nav.navbar .navbar-nav .nav-link.active').first();
      const selected = await style(active, ['backgroundColor', 'color']);
      const idle = page.locator('nav.navbar .navbar-nav > .nav-item > .nav-link:not(.active)').first();
      await idle.hover();
      // read once Bootstrap's fade (.15 s) has settled on the hover grey
      const hoverBg = await resolved(page, 'background-color', 'var(--wlx-hover-bg)', 'nav.navbar');
      await expect.poll(async () => (await style(idle, ['backgroundColor'])).backgroundColor).toBe(hoverBg);
      const hovered = await style(idle, ['backgroundColor', 'color', 'textDecorationLine']);
      expect(selected.backgroundColor, 'the active item has a fill').not.toBe('rgba(0, 0, 0, 0)');
      expect(hovered.backgroundColor, `${mode}: hover is not the selected fill`).not.toBe(selected.backgroundColor);
      expect(hovered.backgroundColor, 'the pointed-at item has a fill').not.toBe('rgba(0, 0, 0, 0)');
      expect(hovered.textDecorationLine, 'chrome is not underlined on hover').toBe('none');
      expect(contrast(selected.color, selected.backgroundColor), `${mode}: the active item's text on its fill`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(hovered.color, hovered.backgroundColor), `${mode}: the pointed-at item's text on its fill`).toBeGreaterThanOrEqual(4.5);
      // the selected text differs from the hover's (blue, not neutral): selected is told by hue
      expect(selected.color).not.toBe(hovered.color);
      // the pager's range box: progressive border 3:1 against the page, its fill the selected one
      const range = page.locator('.page-link.pager-range').first();
      if (await range.count()) { // the rounds page has no pager: the gallery's is checked in the gallery test below
        const box = await style(range, ['backgroundColor', 'color', 'borderTopColor']);
        expect(box.backgroundColor, 'the range box is the selected fill').toBe(selected.backgroundColor);
        expect(contrast(box.borderTopColor, pageBg), `${mode}: the range box's border on the page`).toBeGreaterThanOrEqual(3);
        expect(contrast(box.color, box.backgroundColor), `${mode}: its text on its fill`).toBeGreaterThanOrEqual(4.5);
      }
      expect(contrast(selected.color, navBg), `${mode}: the active item's text on the bar`).toBeGreaterThanOrEqual(3);
      // the bar on the page's background (white, #101418), apart from the page by its border;
      // both fills differ from it
      const base = await resolved(page, 'background-color', 'var(--background-color-base)');
      expect(navBg, `${mode}: the bar on the page's background`).toBe(base);
      const border = await page.evaluate(() => {
        const s = getComputedStyle(document.querySelector('nav.navbar'));
        return { width: parseFloat(s.borderBottomWidth), color: s.borderBottomColor };
      });
      expect(border.width, 'the bar\'s bottom border').toBeGreaterThanOrEqual(1);
      expect(border.color, `${mode}: its border is not the bar's colour`).not.toBe(navBg);
      expect(selected.backgroundColor, `${mode}: the selected fill shows on the bar`).not.toBe(navBg);
      expect(hovered.backgroundColor, `${mode}: the hover grey shows on the bar`).not.toBe(navBg);
      // a menu open: its toggle the hover grey in the neutral text, not the selected blue; the
      // menu on the page's background, a pointed-at item the hover grey
      const toggle = page.locator('nav.navbar .navbar-nav .nav-link.dropdown-toggle:not(.active):visible').first();
      await toggle.click();
      await expect(toggle).toHaveClass(/\bshow\b/);
      await page.mouse.move(0, 0);
      await expect.poll(async () => (await style(toggle, ['backgroundColor'])).backgroundColor,
        `${mode}: an open menu's toggle is the hover grey`).toBe(hovered.backgroundColor);
      const open = await style(toggle, ['backgroundColor', 'color']);
      expect(open.color, `${mode}: an open menu's toggle is not the selected blue`).not.toBe(selected.color);
      const menu = toggle.locator('xpath=following-sibling::*[contains(@class,"dropdown-menu")]');
      expect((await style(menu, ['backgroundColor'])).backgroundColor, `${mode}: the menu on the page's background`).toBe(base);
      const item = menu.locator('.dropdown-item:not(.active):not(.disabled)').first();
      if (await item.count()) {
        await item.hover();
        await expect.poll(async () => (await style(item, ['backgroundColor'])).backgroundColor, `${mode}: a pointed-at menu item`)
          .toBe(hovered.backgroundColor);
      }
      await page.keyboard.press('Escape');
    });
  }
});

// Links: the link colour, no underline, underlined on hover and keyboard focus (everywhere but
// interface chrome); text at 4.5:1 on the page in both modes, controls' borders 3:1.
test.describe('the links and controls', { tag: '@J-30' }, () => {
  test.use({ role: 'juror' });
  for (const mode of ['light', 'dark']) {
    test(`a text link is not underlined until hovered, and controls keep their contrast in ${mode} mode`, async ({ context, page, fixture, baseURL }) => {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
      await page.evaluate(() => {
        const a = document.createElement('a'); a.id = 'probe-link'; a.href = '#'; a.textContent = 'probe link';
        const p = document.createElement('div'); p.append(a); document.body.append(p);
        const sentence = document.createElement('p'); sentence.innerHTML = 'See <a id="probe-inline" href="#">the rules</a> first'; document.body.append(sentence);
        // a count of 0 (snippets/textLink): an anchor without href, plain text
        const plain = document.createElement('p'); plain.innerHTML = '<a id="probe-plain" class="text-body-secondary" aria-disabled="true" tabindex="-1">Comments: none</a>'; document.body.append(plain);
        const f = document.createElement('input'); f.id = 'probe-field'; f.className = 'form-control'; f.placeholder = 'placeholder';
        document.body.append(f);
        const controls = document.createElement('div'); controls.id = 'probe-controls';
        controls.innerHTML = '<button class="btn btn-primary">Save</button><button class="btn btn-outline-secondary">Back</button>'
          + '<select class="form-select"><option>One</option></select>'
          + '<button class="btn btn-outline-secondary btn-lg">Large</button><input class="form-control form-control-lg">'
          + '<button class="btn btn-outline-secondary btn-sm">Small</button><input class="form-control form-control-sm">';
        document.body.append(controls);
      });
      const probe = page.locator('#probe-link');
      const rest = await probe.evaluate((el) => ({ deco: getComputedStyle(el).textDecorationLine, color: getComputedStyle(el).color }));
      expect(rest.deco, 'a link standing alone: no underline at rest').toBe('none');
      expect(await page.locator('#probe-inline').evaluate((el) => getComputedStyle(el).textDecorationLine), 'a link in a sentence: underlined (WCAG 1.4.1)').toBe('underline');
      const plain = page.locator('#probe-plain');
      expect(await plain.evaluate((el) => getComputedStyle(el).textDecorationLine), 'an anchor without href in a sentence: plain text').toBe('none');
      await plain.hover();
      expect(await plain.evaluate((el) => getComputedStyle(el).textDecorationLine), 'an anchor without href, pointed at: plain text').toBe('none');
      await probe.hover();
      expect(await probe.evaluate((el) => getComputedStyle(el).textDecorationLine)).toBe('underline');
      const pageBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      expect(contrast(rest.color, pageBg), `${mode}: link on the page`).toBeGreaterThanOrEqual(4.5);
      const field = await page.locator('#probe-field').evaluate((el) => {
        const s = getComputedStyle(el); const ph = getComputedStyle(el, '::placeholder');
        return { border: s.borderTopColor, bg: s.backgroundColor, placeholder: ph.color, height: el.getBoundingClientRect().height, radius: s.borderTopLeftRadius };
      });
      expect(contrast(field.border, pageBg), `${mode}: control border on the page`).toBeGreaterThanOrEqual(3);
      expect(contrast(field.placeholder, field.bg), `${mode}: placeholder on the field`).toBeGreaterThanOrEqual(4.5);
      expect(field.height, 'a 32 px control').toBe(32);
      // Codex's sizes exactly: 32 px, large 44, small 28 (Bootstrap's would be 38, 48, 31)
      const heights = await page.locator('#probe-controls > *').evaluateAll((els) => els.map((el) => [el.className, el.getBoundingClientRect().height]));
      for (const [cls, height] of heights) {
        const want = /-lg\b/.test(cls) ? 44 : /-sm\b/.test(cls) ? 28 : 32;
        expect(height, `${cls}: ${want} px`).toBe(want);
      }
      expect(field.radius, 'Codex 2 px corners').toBe('2px');
    });
  }
});

test.describe('the pager range box', { tag: '@J-30' }, () => {
  test.use({ role: 'juror' });
  for (const mode of ['light', 'dark']) {
    test(`is the selected highlight, its border 3:1 on the page, in ${mode} mode`, async ({ context, page, fixture, baseURL }) => {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
      const pageBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      const box = await page.locator('.page-link.pager-range').first().evaluate((el) => {
        const s = getComputedStyle(el);
        return { bg: s.backgroundColor, color: s.color, border: s.borderTopColor };
      });
      const other = await page.locator('.pagination .page-item:not(.disabled) > a.page-link').first().evaluate((el) => getComputedStyle(el).backgroundColor);
      expect(box.bg, 'the range box differs from a plain page link').not.toBe(other);
      expect(contrast(box.border, pageBg), `${mode}: border on the page`).toBeGreaterThanOrEqual(3);
      expect(contrast(box.color, box.bg), `${mode}: text on its fill`).toBeGreaterThanOrEqual(4.5);
    });
  }
});

// Tom Select (the round form's multiple selects): its Bootstrap 5 theme loads after main.css, so
// codex-theme.css's rules carry :root to win: 32 px, 2 px corners, the list on the page's
// background, the pointed-at option the hover grey (visible on the list), a chosen one the
// selected highlight; text at 4.5:1 on each
test.describe('Tom Select', { tag: '@J-30' }, () => {
  test.use({ role: 'admin' });
  for (const mode of ['light', 'dark']) {
    test(`a multiple select is a Codex control and menu in ${mode} mode`, async ({ context, page, fixture, baseURL }) => {
      await context.addCookies([{ name: 'theme', value: mode, url: baseURL }]);
      // the new round form's media filtering section, opened: its previous rounds
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      await page.locator('a[href="#collapseImageFiltering"]').click();
      const wrapper = page.locator('#collapseImageFiltering .ts-wrapper.wlx-multi:not(.disabled)').first();
      await expect(wrapper).toBeVisible();
      const box = await wrapper.evaluate((el) => {
        const s = getComputedStyle(el);
        return { height: el.getBoundingClientRect().height, radius: s.borderTopLeftRadius };
      });
      expect(box.height, 'a 32 px control').toBe(32);
      expect(box.radius, 'Codex 2 px corners').toBe('2px');
      const base = await resolved(page, 'background-color', 'var(--background-color-base)');
      const hoverBg = await resolved(page, 'background-color', 'var(--wlx-hover-bg)');
      const currentBg = await resolved(page, 'background-color', 'var(--wlx-current-bg)');
      await wrapper.locator('.ts-control').click();
      const dropdown = wrapper.locator('.ts-dropdown');
      await expect(dropdown).toBeVisible();
      const list = await dropdown.evaluate((el) => {
        const s = getComputedStyle(el);
        return { bg: s.backgroundColor, radius: s.borderTopLeftRadius };
      });
      expect(list.bg, `${mode}: the list on the page's background`).toBe(base);
      expect(list.radius, 'the list\'s 2 px corners').toBe('2px');
      const options = dropdown.locator('.option[data-selectable]');
      await expect(options.nth(1), 'two options or more').toBeVisible();
      // choose the first, point at the second
      await options.nth(0).click();
      await expect(options.nth(0)).toHaveClass(/\bselected\b/);
      await options.nth(1).hover();
      await expect(options.nth(1)).toHaveClass(/\bactive\b/);
      const look = (loc) => loc.evaluate((el) => ({ bg: getComputedStyle(el).backgroundColor, color: getComputedStyle(el).color }));
      const [chosen, pointed] = [await look(options.nth(0)), await look(options.nth(1))];
      expect(pointed.bg, `${mode}: the pointed-at option is the hover grey`).toBe(hoverBg);
      expect(pointed.bg, `${mode}: ... which shows on the list`).not.toBe(list.bg);
      expect(chosen.bg, `${mode}: the chosen option is the selected highlight`).toBe(currentBg);
      expect(contrast(pointed.color, pointed.bg), `${mode}: the pointed-at option's text`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(chosen.color, chosen.bg), `${mode}: the chosen option's text`).toBeGreaterThanOrEqual(4.5);
      await page.keyboard.press('Escape');
    });
  }
});
