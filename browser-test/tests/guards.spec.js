// The guards are not vacuous (testing §4.2): the fixture sends the production CSP, a violation
// of it and a request to another host are reported, and Commons images are stubbed.
import { test, expect } from '../lib/fixtures.js';

test.describe('the guards', () => {
  test('the fixture sends the production content security policy', async ({ page }) => {
    const response = await page.goto('/auth');
    const csp = response.headers()['content-security-policy'];
    expect(csp, 'start the fixture with node fixture-server.mjs (-Dui.csp=production)').toBeTruthy();
    expect(csp).toMatch(/script-src [^;]*'self'/);
    expect(csp).not.toMatch(/script-src [^;]*'unsafe-inline'/);
  });

  test('a CSP violation is reported', async ({ page, diagnostics }) => {
    await page.goto('/auth');
    await page.evaluate(() => {
      const s = document.createElement('script');
      s.textContent = 'window.wlxInjected = true;';
      document.head.append(s);
    });
    await expect.poll(() => diagnostics.problems.filter((p) => p.startsWith('CSP violation: ')).length).toBe(1);
    expect(await page.evaluate(() => window.wlxInjected === true)).toBe(false);
    diagnostics.take('CSP violation');
    // Chromium also logs the violation on the console
    diagnostics.take('console error');
  });

  // images: the CSP's img-src allows https:, so only the guard stops them (WebKit fires no error
  // event on a refused image, so the test waits for the guard instead)
  for (const host of ['example.org', 'upload.wikimedia.org.example.org', 'commons.wikimedia.org']) {
    test(`an image from ${host} is refused and reported`, async ({ page, diagnostics }) => {
      // Chromium logs the refused load on the console, whenever it arrives
      diagnostics.allow(/^console error: Failed to load resource: net::ERR_BLOCKED_BY_CLIENT/);
      await page.goto('/auth');
      await page.evaluate((src) => {
        const img = document.createElement('img');
        img.id = 'other-host';
        img.src = src;
        document.body.append(img);
      }, `https://${host}/wikipedia/commons/a/ab/Example.jpg`);
      await expect.poll(() => diagnostics.otherHosts).toContain(host);
      expect(await page.evaluate(() => document.getElementById('other-host').naturalWidth)).toBe(0);
      expect(diagnostics.take('other host')).toEqual([expect.stringContaining(`https://${host} refused`)]);
    });
  }

  test('a request to Commons\' API is refused and reported', async ({ page, diagnostics }) => {
    diagnostics.allow(/^console error: .*(ERR_BLOCKED_BY_CLIENT|api\.php)/);
    await page.goto('/auth');
    // the CSP's connect-src allows commons.wikimedia.org (the category suggestions)
    const result = await page.evaluate(() => fetch('https://commons.wikimedia.org/w/api.php?action=query&format=json')
      .then(() => 'loaded', () => 'refused'));
    expect(result).toBe('refused');
    expect(diagnostics.take('other host')).toEqual([expect.stringContaining('https://commons.wikimedia.org refused (fetch)')]);
  });

  test('images from Commons\' upload host and the image proxy get the placeholder', async ({ page }) => {
    await page.goto('/auth');
    const sizes = await page.evaluate((srcs) => Promise.all(srcs.map((src) => new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve([img.naturalWidth, img.naturalHeight]);
      img.onerror = () => resolve(null);
      img.src = src;
    }))), ['/wikipedia/commons/thumb/a/ab/Example.jpg/320px-Example.jpg',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Example.jpg/320px-Example.jpg']);
    expect(sizes).toEqual([[640, 480], [640, 480]]);
  });

  test('the routeless browser refuses other hosts too', async ({ routelessAs, diagnostics }) => {
    const page = await (await routelessAs(null)).newPage();
    await page.goto('/auth');
    await page.evaluate(() => {
      const img = document.createElement('img');
      img.src = 'https://example.org/x.png';
      document.body.append(img);
    });
    await expect.poll(() => diagnostics.otherHosts).toContain('example.org');
    expect(diagnostics.take('other host')).toEqual([expect.stringContaining('https://example.org refused')]);
    diagnostics.take('request failed');
    diagnostics.take('console error');
  });
});
