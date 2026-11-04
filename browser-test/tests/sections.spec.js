// The large view's side sections (large-sections.js) are drawn in the viewer's stored state from
// the first frame that shows them, not first in their default state (moved from
// scripts/ui_checks.py, sections_first_frame). Before the page's scripts: the stored states (the
// file's details closed, the next files open: the opposite of the defaults) and a probe of the
// sections in the first animation frame that has them, which runs before that frame is painted.
import { test, expect } from '../lib/fixtures.js';
import { largePath } from '../lib/actions.js';

function probe() {
  try {
    localStorage.setItem('wlx.large.section.large-info', 'closed');
    localStorage.setItem('wlx.large.section.large-next', 'open');
  } catch (e) {
    // no storage: the check below fails, as it should
  }
  (function frame() {
    requestAnimationFrame(() => {
      const info = document.getElementById('large-info');
      const next = document.getElementById('large-next');
      if (!info) {
        if (document.readyState !== 'complete') frame();
        return;
      }
      window.wlxFirstFrame = {
        // Bootstrap's collapse: shown is the "show" class, which is its state
        infoOpen: info.classList.contains('show'),
        nextOpen: next ? next.classList.contains('show') : null,
      };
    });
  }());
}

const LOADS = 3;

for (const [name, role, path] of [
  ['a juror\'s large view', 'juror', (f) => largePath(f.roundBinaryId, f.accounts.juror.id, f.accounts.juror.pageId)],
  ['a contest file', 'admin', (f) => `/contest/${f.contestId}/files/${f.accounts.juror.pageId}`],
]) {
  test.describe(() => {
    test.use({ role });

    test(`the side sections of ${name} are drawn as stored from the first frame`, async ({ page, fixture }) => {
      await page.addInitScript(probe);
      for (let i = 0; i < LOADS; i++) {
        await page.goto(path(fixture), { waitUntil: 'load' });
        await expect.poll(() => page.evaluate(() => window.wlxFirstFrame ?? null), {
          message: `load ${i + 1}: the sections in the first frame`,
        }).toEqual(expect.objectContaining({ infoOpen: false }));
        const first = await page.evaluate(() => window.wlxFirstFrame);
        expect([true, null], `load ${i + 1}: the next files open, if shown`).toContain(first.nextOpen);
      }
      // and as the page stays
      await expect(page.locator('#large-info')).toBeHidden();
    });
  });
}
