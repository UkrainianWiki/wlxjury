// The round form's category filter (docs/user/round-files.md "Choosing the round's files"): two
// boxes always shown in the Media filtering section, "Only files in category" with its hint and
// "Leave out files in category"; "More category options" (a <details>, closed) says what the second
// box does, and its label follows the choice. Nothing is saved: the form's POST is answered here,
// so no round is created and Commons is never asked for a category's files. Commons' suggestions
// are answered here too (none).
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../lib/fixtures.js';

/** Commons' suggestions: none (a page's route goes before the guard's). */
async function noSuggestions(page) {
  await page.route('https://commons.wikimedia.org/w/api.php**', (route) => route.fulfill({
    status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
    body: JSON.stringify({ batchcomplete: true, query: { prefixsearch: [] } }),
  }));
}

/** The form's POSTs to `url`, answered here, as URLSearchParams. */
async function posts(page, url) {
  const sent = [];
  await page.route(url, (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    sent.push(new URLSearchParams(route.request().postData() || ''));
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>sent</title>' });
  });
  return sent;
}

/** Types `text` into a category box (its Tom Select control) and leaves it. */
async function typeCategory(page, id, text) {
  await page.locator(`#${id}-ts-control`).focus();
  await page.keyboard.type(text);
  await page.keyboard.press('Tab');
  await expect(page.locator(`#${id}`)).toHaveValue(text);
}

test.describe('the new-round form', () => {
  test.use({ role: 'admin' });

  test('shows the two category boxes, the hint, and the choice under More category options', { tag: '@A-01' },
    async ({ page, fixture, browserName }, testInfo) => {
      await noSuggestions(page);
      const sent = await posts(page, '**/admin/rounds/save**');
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      // no category yet: the section starts closed
      const toggle = page.locator('a[href="#collapseImageFiltering"]');
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');
      await toggle.click();

      const only = page.getByRole('combobox', { name: 'Only files in category' });
      const second = page.getByRole('combobox', { name: 'Leave out files in category' });
      await expect(only).toBeVisible();
      await expect(second).toBeVisible();
      await expect(page.locator('#categoryClause')).toHaveCount(0);
      // the hint describes the first box, also its suggestions control
      const hint = page.locator('#source_help');
      await expect(hint).toContainText('not in its subcategories');
      await expect(page.locator('#source')).toHaveAttribute('aria-describedby', /\bsource_help\b/);
      await expect(only).toHaveAttribute('aria-describedby', /\bsource_help\b/);

      // More category options: closed, its default choice the second box's meaning
      const more = page.locator('#category-more');
      await expect(more).not.toHaveAttribute('open', /.*/);
      const both = page.getByRole('radio', { name: 'Files must be in both categories' });
      await expect(both).toBeHidden();
      await page.getByText('More category options').click();
      await expect(page.getByRole('radio', { name: 'Leave its files out' })).toBeChecked();
      await both.check();
      await expect(page.getByRole('combobox', { name: '…and also in category' })).toBeVisible();
      await page.getByRole('radio', { name: 'Files may be in either category' }).check();
      await expect(page.getByRole('combobox', { name: '…or in category' })).toBeVisible();
      await page.getByRole('radio', { name: 'Leave its files out' }).check();
      await expect(second).toBeVisible();

      if (browserName === 'chromium') {
        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
          // the boxes' controls and More; not the inputs Tom Select hides (named by no label once it
          // points the label at its control), nor the section's multi-selects: as before this form
          .include('#category-first .ts-wrapper').include('#category-second .ts-wrapper').include('#category-more')
          .analyze();
        const serious = results.violations.filter((v) => ['serious', 'critical'].includes(v.impact))
          .map((v) => ({ rule: v.id, nodes: v.nodes.map((n) => n.target.join(' ')) }));
        await testInfo.attach('axe the category boxes', { body: JSON.stringify(serious, null, 1), contentType: 'application/json' });
        expect(serious, 'serious or critical accessibility findings').toEqual([]);
      }

      // only the first box filled: sent as the boxes and the default choice
      await typeCategory(page, 'source', 'Category:Lakes of Ukraine');
      await page.locator('#round-form-save-find').click();
      await expect.poll(() => sent.length).toBe(1);
      expect(sent[0].get('source')).toBe('Category:Lakes of Ukraine');
      expect(sent[0].get('excludeCategory')).toBe('');
      expect(sent[0].get('categoryCombine')).toBe('minus');
      expect(sent[0].has('categoryClause')).toBe(false);
    });

  test('opens Media filtering by itself, More too, when a refused form comes back with "either"', { tag: '@A-01' },
    async ({ page, fixture, diagnostics }) => {
      diagnostics.expectStatus(400); // the refused form, shown again
      await noSuggestions(page);
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      await page.locator('a[href="#collapseImageFiltering"]').click();
      await typeCategory(page, 'source', 'Category:Lakes of Ukraine');
      await page.getByText('More category options').click();
      await page.getByRole('radio', { name: 'Files may be in either category' }).check();
      // "either" needs the second category: the server refuses and shows the form again
      await page.locator('#round-form-save-find').click();
      await expect(page.locator('.alert-danger')).toContainText('Enter the second category');
      await expect(page.locator('a[href="#collapseImageFiltering"]')).toHaveAttribute('aria-expanded', 'true');
      await expect(page.locator('#category-more')).toHaveAttribute('open', /.*/);
      await expect(page.getByRole('radio', { name: 'Files may be in either category' })).toBeChecked();
      await expect(page.getByRole('combobox', { name: '…or in category' })).toBeVisible();
      await expect(page.locator('#source')).toHaveValue('Category:Lakes of Ukraine');
    });

  // the card's header is an a[role=button] (snippets/collapsible.scala.html): Space opens and
  // closes it as on a button, and doesn't scroll the page (round-form.js, initCardToggleKeys)
  test('opens and closes Media filtering with Space on its header', { tag: '@A-01' },
    async ({ page, fixture }) => {
      await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
      const toggle = page.locator('a[href="#collapseImageFiltering"]');
      const panel = page.locator('#collapseImageFiltering');
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');
      await toggle.focus();
      const scrollY = await page.evaluate(() => window.scrollY);
      await page.keyboard.press('Space');
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      await expect(panel).toHaveClass(/\bshow\b/);
      await expect(panel).not.toHaveClass(/\bcollapsing\b/);
      expect(await page.evaluate(() => window.scrollY)).toBe(scrollY);
      await expect(toggle).toBeFocused();
      await page.keyboard.press('Space');
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');
      await expect(panel).not.toHaveClass(/\b(show|collapsing)\b/);
      expect(await page.evaluate(() => window.scrollY)).toBe(scrollY);
    });
});
