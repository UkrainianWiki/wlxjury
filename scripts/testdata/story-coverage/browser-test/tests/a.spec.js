// The self-test's fixture of scripts/story-coverage.py: J-01 known, J-02 retired, J-99 unknown,
// a tag that is no story ('@slow'), and a fixme test (not counted as covering).
import { test, expect } from '../lib/fixtures.js';

test.describe('a juror', { tag: '@J-01' }, () => {
  test('signs in', { tag: ['@J-02', '@J-99', '@slow'] }, async ({ page }) => {
    await page.goto(`/auth?n=${[1, 2].map((x) => `${x}`).join('')}`);
  });

  test('is drawn from the first frame', async () => {
    test.fixme(true, 'a known flake');
  });
});
