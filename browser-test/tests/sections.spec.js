// The large view's side sections (large-sections.js) in the viewer's stored state (moved from
// scripts/ui_checks.py, sections_first_frame). Before the page's scripts: the stored states (the
// file's details closed, the next files open: the opposite of the defaults) and a probe of the
// sections in every animation frame until the page has loaded (a frame's callbacks run before
// it is painted), and at DOMContentLoaded.
//
// Enforced: the sections are restored by DOMContentLoaded, before the page's own scripts run.
// Not enforced (triage U3, UI practices §9): that no frame shows them in their default states.
// The probe sees a frame with the defaults in about one load of eight in Chromium and Firefox,
// under parallel load, on the large view and the contest file: a frame before the column's
// restore script ran (the parser yielding between the sections and the script), or a frame the
// browser ran but didn't paint (Chromium holds the old page's paint while navigating).
import { test, expect } from '../lib/fixtures.js';
import { largePath } from '../lib/actions.js';

function probe() {
  try {
    localStorage.setItem('wlx.large.section.large-info', 'closed');
    localStorage.setItem('wlx.large.section.large-next', 'open');
  } catch (e) {
    // no storage: the checks below fail, as they should
  }
  const state = () => {
    const info = document.getElementById('large-info');
    const next = document.getElementById('large-next');
    // Bootstrap's collapse: shown is its "show" class, which is its state
    return info && { infoOpen: info.classList.contains('show'), nextOpen: next ? next.classList.contains('show') : null };
  };
  window.wlxFrames = [];
  document.addEventListener('DOMContentLoaded', () => { window.wlxAtReady = state(); });
  (function frame() {
    requestAnimationFrame(() => {
      const s = state();
      if (s) window.wlxFrames.push(s);
      if (document.readyState !== 'complete') frame();
    });
  }());
}

const STORED = { infoOpen: false, nextOpen: true };
const LOADS = 3;

for (const [name, role, path] of [
  ['a juror\'s large view', 'juror', (f) => largePath(f.roundBinaryId, f.accounts.juror.id, f.accounts.juror.pageId)],
  ['a contest file', 'admin', (f) => `/contest/${f.contestId}/files/${f.accounts.juror.pageId}`],
]) {
  test.describe(() => {
    test.use({ role });

    test(`the side sections of ${name} are restored as stored before its scripts run`, async ({ page, fixture }) => {
      await page.addInitScript(probe);
      for (let i = 0; i < LOADS; i++) {
        await page.goto(path(fixture), { waitUntil: 'load' });
        expect(await page.evaluate(() => window.wlxAtReady), `load ${i + 1}: at DOMContentLoaded`).toEqual(STORED);
      }
      await expect(page.locator('#large-info')).toBeHidden();
      await expect(page.locator('#large-next')).toBeVisible();
    });

    test(`the side sections of ${name} are drawn as stored from the first frame`, async ({ page, fixture }) => {
      test.fixme(true, 'U3 (UI practices §9): a frame with the default states in about one load of eight');
      await page.addInitScript(probe);
      for (let i = 0; i < LOADS; i++) {
        await page.goto(path(fixture), { waitUntil: 'load' });
        const frames = await page.evaluate(() => window.wlxFrames);
        expect(frames.length, `load ${i + 1}: frames with the sections`).toBeGreaterThan(0);
        expect(frames.filter((f) => f.infoOpen !== STORED.infoOpen || f.nextOpen === false),
          `load ${i + 1}: frames in the default states`).toEqual([]);
      }
    });
  });
}
