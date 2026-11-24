// The large view's next file's photo, loaded by script (large-next-image.js): after a vote the next
// page's photo comes from the browser's HTTP cache, not the network. Any route turns that cache
// off, so the upload host is a local https server (lib/image-host.js) that Chromium reaches
// under that name and that sends Wikimedia's Cache-Control; its log of paths asked for shows what
// the browser fetched. The last file's having none is views.LargeViewNextImageSpec's (the fixture's
// list can't be walked to its end by key: End stops at the files shown). Chromium only (the host mapping is its flag).
import { test, expect } from '../lib/fixtures.js';
import { largePath } from '../lib/actions.js';
import { launchChromiumOnImageHost, startImageHost } from '../lib/image-host.js';
import { authFile } from '../lib/server.js';

test.skip(({ browserName }) => browserName !== 'chromium', 'the image host is mapped by a Chromium flag');

const distinct = (paths) => [...new Set(paths)];

test.describe('the large view loads the next photo by script', () => {
  let host;
  let browser;

  test.beforeEach(async ({ playwright }) => {
    host = await startImageHost();
    browser = await launchChromiumOnImageHost(playwright, host);
  });

  test.afterEach(async () => {
    await browser.close();
    await host.close();
  });

  /** A juror's page in a fresh context (its own HTTP cache), with Save-Data on if asked. */
  async function jurorPage({ baseURL, browserName }, saveData = false) {
    const context = await browser.newContext({ baseURL, storageState: authFile(browserName, 'juror'), locale: 'en-US' });
    if (saveData) {
      await context.addInitScript(() => {
        Object.defineProperty(navigator, 'connection', { value: { saveData: true }, configurable: true });
      });
    }
    return context.newPage();
  }

  const open = async (page, fixture) => {
    const { juror } = fixture.accounts;
    await page.goto(largePath(fixture.roundBinaryId, juror.id, juror.pageId));
    await expect(page.locator('#large_image')).toBeVisible();
    await page.waitForFunction(() => document.getElementById('large_image').complete);
  };

  test('a vote goes on to a photo already in the cache', { tag: '@J-21' }, async ({ fixture, baseURL, browserName }) => {
    const page = await jurorPage({ baseURL, browserName });
    await open(page, fixture);
    const next = await page.locator('#wlx-next-image').evaluate((el) => ({ src: el.dataset.src, srcset: el.dataset.srcset, sizes: el.dataset.sizes }));
    const nextPath = decodeURI(new URL(next.src).pathname);
    // the script asks the host for it once the page is up
    await expect.poll(() => host.seen.includes(nextPath), { message: 'the next file was prefetched' }).toBe(true);
    const hitsBefore = host.seen.filter((p) => p === nextPath).length;
    expect(hitsBefore).toBe(1);
    const currentPath = decodeURI(new URL(await page.locator('#large_image').evaluate((img) => img.currentSrc)).pathname);
    expect(currentPath, 'the current photo is not the next one').not.toBe(nextPath);

    // vote: the page goes on to the next file
    const from = page.url();
    // Space selects unless selected; Delete rejects
    const space = await page.locator('#large-keys[data-vote-key-space]').count();
    await page.keyboard.press(space ? 'Space' : 'Delete');
    await page.waitForURL((u) => u.href !== from);
    await page.waitForFunction(() => document.getElementById('large_image').complete);
    const shown = await page.locator('#large_image').evaluate((img) => {
      const entry = performance.getEntriesByType('resource').find((e) => e.name === img.currentSrc);
      return { path: new URL(img.currentSrc).pathname, transferSize: entry && entry.transferSize, from: entry && entry.deliveryType };
    });
    expect(decodeURI(shown.path), 'the page shows the file that was prefetched').toBe(nextPath);
    expect(host.seen.filter((p) => p === nextPath), 'the next page asked the host for its photo again').toHaveLength(1);
    expect(shown.transferSize, 'no bytes came over the network for it').toBe(0);
    // the file it went on to prefetches its own next: three photos in all
    await expect.poll(() => distinct(host.seen).length).toBe(3);
  });

  test('Save-Data prefetches nothing', { tag: '@J-21' }, async ({ fixture, baseURL, browserName }) => {
    const page = await jurorPage({ baseURL, browserName }, true);
    await open(page, fixture);
    expect(await page.locator('#wlx-next-image').count(), 'the page still names the next file').toBe(1);
    await page.waitForTimeout(1000);
    expect(distinct(host.seen), 'only the photo shown').toHaveLength(1);
  });
});
