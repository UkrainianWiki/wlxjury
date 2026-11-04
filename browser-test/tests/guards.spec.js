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

  test('a request to another host is refused and reported', async ({ page, diagnostics }) => {
    await page.goto('/auth');
    // an image: the CSP's img-src allows https:, so only the guard stops it
    const result = await page.evaluate(() => new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve('loaded');
      img.onerror = () => resolve('refused');
      img.src = 'https://example.org/x.png';
    }));
    expect(result).toBe('refused');
    expect(diagnostics.take('other host')).toEqual([expect.stringContaining('https://example.org refused')]);
    // Chromium logs the refused load on the console
    diagnostics.take('console error');
  });

  test('images from Commons get the placeholder', async ({ page }) => {
    await page.goto('/auth');
    const size = await page.evaluate(() => new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve([img.naturalWidth, img.naturalHeight]);
      img.onerror = () => resolve(null);
      img.src = '/wikipedia/commons/thumb/a/ab/Example.jpg/320px-Example.jpg';
    }));
    expect(size).toEqual([640, 480]);
  });
});
