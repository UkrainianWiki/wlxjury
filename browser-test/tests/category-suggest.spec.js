// The category fields' saved value (category-suggest.js, a Tom Select): it is an item, which a
// click could neither select nor edit (only Backspace made it text again). On focus it becomes
// the text typed, wholly selected: typing replaces it, and leaving unchanged keeps it. On the
// contest files page's import source (UiShotsServer's Nepal case, its source saved) and on the
// round form's first category, a value chosen there. Commons' suggestions are answered here (none).
import { test, expect } from '../lib/fixtures.js';

/** Commons' suggestions: none (a page's route goes before the guard's). */
async function noSuggestions(page) {
  await page.route('https://commons.wikimedia.org/w/api.php**', (route) => route.fulfill({
    status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
    body: JSON.stringify({ batchcomplete: true, query: { prefixsearch: [] } }),
  }));
}

/** The focused element's id, text and selection. */
const focused = (page) => page.evaluate(() => {
  const el = document.activeElement;
  return { id: el.id, value: el.value, start: el.selectionStart, end: el.selectionEnd };
});

/** Clicking the field (its `wrapper`) with `saved` selects all of it; typing replaces it; focus
  * and blur without typing keep the new value in `input`. */
async function editsOnClick(page, wrapper, input, saved, replacement) {
  const control = wrapper.locator('input[id$="-ts-control"]');
  const id = await control.getAttribute('id');
  await wrapper.locator('.ts-control').click();
  await expect(control).toBeFocused();
  expect(await focused(page), 'the whole saved value selected, as text').toEqual({ id, value: saved, start: 0, end: saved.length });

  await page.keyboard.type(replacement);
  await page.keyboard.press('Tab'); // at once, within the list's 250 ms pause in typing
  // past that pause: the field stays left (its waiting refresh would focus it again)
  await page.waitForTimeout(500);
  await expect(control).not.toBeFocused();
  await expect(input).toHaveValue(replacement);
  await expect(wrapper.locator('.item')).toHaveText(replacement);

  // focus and leave without typing: the value stays
  await control.focus();
  expect(await focused(page)).toEqual({ id, value: replacement, start: 0, end: replacement.length });
  await page.keyboard.press('Tab');
  await expect(input).toHaveValue(replacement);
  await expect(wrapper.locator('.item')).toHaveText(replacement);
}

test('the import source saved for a contest is selected on a click and edited by typing', { tag: '@A-09' },
  async ({ contextAs, fixture }) => {
    test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
    const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    await noSuggestions(page);
    const checks = [];
    // the check's requests (/importimages/source-identity?source=…), not the script's own
    page.on('request', (request) => { if (request.url().includes('/importimages/source-identity')) checks.push(request.url()); });
    await page.goto(`/contest/${fixture.healthContestId}/images`);
    const saved = 'Category:Images from Wiki Loves Monuments 2026 in Nepal';
    const input = page.locator('#source');
    await expect(input).toHaveValue(saved);
    const wrapper = page.locator('#import-images .ts-wrapper');

    // a focus and a blur, unchanged: the value and the warning stay, the server isn't asked
    await wrapper.locator('.ts-control').click();
    await page.keyboard.press('Tab');
    await expect(input).toHaveValue(saved);
    await expect(wrapper.locator('.item')).toHaveText(saved);
    await page.waitForTimeout(500); // past source-identity.js's 300 ms pause
    expect(checks, 'no identity check for an unchanged source').toEqual([]);
    await expect(page.locator('#source-identity-warning')).toBeVisible();

    await editsOnClick(page, wrapper, input, saved, 'Category:Images from Wiki Loves Earth 2020 in Armenia');
  });

test('on a phone, a tap on the saved import source selects all of it', { tag: '@A-09' },
  async ({ contextAs, fixture, browserName }) => {
    test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
    test.skip(browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
    const context = await contextAs('root', { viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
    const page = await context.newPage();
    await noSuggestions(page);
    await page.goto(`/contest/${fixture.healthContestId}/images`);
    const saved = 'Category:Images from Wiki Loves Monuments 2026 in Nepal';
    // the field's height, the value on one line either way (main.css): what is below it stays put
    const height = () => page.locator('#import-images .ts-wrapper').evaluate((el) => el.getBoundingClientRect().height);
    const left = await height();
    await page.locator('#import-images .ts-control').tap();
    await expect(page.locator('#source-ts-control')).toBeFocused();
    expect(await focused(page)).toEqual({ id: 'source-ts-control', value: saved, start: 0, end: saved.length });
    expect(await height(), 'the field as tall focused as left').toBe(left);
    // away to the next field (a tap on plain text leaves the field focused in WebKit, as on iOS): the value stays
    await page.locator('#list').tap();
    await expect(page.locator('#list')).toBeFocused();
    await expect(page.locator('#source')).toHaveValue(saved);
    expect(await height()).toBe(left);
  });

/** The form's POSTs to `url` (a glob), answered with a blank page (a 204 hangs Chromium's click
  * and is a failed request in WebKit): their fields, as sent. */
async function posts(page, url) {
  const sent = [];
  await page.route(url, (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    sent.push(new URLSearchParams(route.request().postData() || ''));
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>sent</title>' });
  });
  return sent;
}

test('on a phone, the import source is sent as kept or typed while its field is focused', { tag: '@A-09' },
  async ({ contextAs, fixture, browserName }) => {
    test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
    test.skip(browserName === 'firefox', 'Firefox has no mobile emulation (isMobile)');
    const context = await contextAs('root', { viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
    const page = await context.newPage();
    await noSuggestions(page);
    const sent = await posts(page, `**/contest/${fixture.healthContestId}/importimages`);
    await page.goto(`/contest/${fixture.healthContestId}/images`);
    const saved = 'Category:Images from Wiki Loves Monuments 2026 in Nepal';
    const control = page.locator('#source-ts-control');
    // the field (its own input, a 1-character one beside the item, is out of view until then)
    await page.locator('#import-images .ts-control').tap();
    await expect(control).toBeFocused();
    // a tap on plain text: WebKit (as iOS) leaves the field focused, the value in its box
    await page.locator('#import-images h2').tap();
    if (browserName === 'webkit') await expect(control).toBeFocused();
    await expect(page.locator('#source')).toHaveValue(saved);
    await page.locator('#import-btn').tap();
    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0].get('source')).toBe(saved);
    expect(sent[0].get('action')).toBe('import.files');

    // a new source typed, then the same taps: the new one is sent, never the old
    const typed = 'Category:Images from Wiki Loves Monuments 2026 in Armenia';
    await page.goto(`/contest/${fixture.healthContestId}/images`);
    await page.locator('#import-images .ts-control').tap();
    await expect(control).toBeFocused();
    await page.keyboard.type(typed);
    await page.locator('#import-images h2').tap();
    if (browserName === 'webkit') await expect(control).toBeFocused();
    await page.locator('#import-btn').tap();
    await expect.poll(() => sent.length).toBe(2);
    expect(sent[1].get('source')).toBe(typed);
  });

test('Enter in the import source keeps it; a second Enter sends it', { tag: '@A-09' },
  async ({ contextAs, fixture }) => {
    test.skip(!fixture.healthContestId, 'the fixture has no health contest (the guides\' screenshot mode)');
    const context = await contextAs('root', { viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    await noSuggestions(page);
    const sent = await posts(page, `**/contest/${fixture.healthContestId}/importimages`);
    await page.goto(`/contest/${fixture.healthContestId}/images`);
    const saved = 'Category:Images from Wiki Loves Monuments 2026 in Nepal';
    const control = page.locator('#source-ts-control');
    await page.locator('#import-images .ts-control').click();
    await expect(control).toBeFocused();
    // Tom Select's own focus handling done (the wrapper's 'focus' class): a click focuses the box
    // at once but handles the focus after a timer, which opens the list with the value as the row
    // Enter chooses. On a busy runner Chromium may handle Playwright's key before that timer
    // (input goes before timers): the first Enter then sent the form, or the timer's handling came
    // after it and left the list open, so that the second Enter only chose the value again.
    await expect(page.locator('#import-images .ts-wrapper')).toHaveClass(/(^|\s)focus(\s|$)/);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    expect(sent, 'the first Enter keeps the value, sends nothing').toEqual([]);
    await expect(page.locator('#source')).toHaveValue(saved);
    await expect(page.locator('#import-images .ts-wrapper .item')).toHaveText(saved);
    await page.keyboard.press('Enter');
    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0].get('source')).toBe(saved);
  });

test('the round form sent from its focused category field sends the text typed', { tag: '@A-01' },
  async ({ contextAs, fixture }) => {
    const context = await contextAs('admin');
    const page = await context.newPage();
    await noSuggestions(page);
    const sent = await posts(page, '**/admin/rounds/save**');
    await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
    await page.locator('a[href="#collapseImageFiltering"]').click();
    const control = page.locator('#source-ts-control');
    await control.focus();
    await page.keyboard.type('Category:Lakes of Ukraine');
    await page.keyboard.press('Tab');
    await expect(page.locator('#source')).toHaveValue('Category:Lakes of Ukraine');
    await control.focus(); // the category becomes the text typed, wholly selected
    await expect(control).toHaveValue('Category:Lakes of Ukraine');
    // the suggestions asked for the text typed (after the pause in typing) and answered before the
    // form is sent: a request the submit cut off would be a page error (WebKit)
    const answered = page.waitForResponse((r) => r.url().startsWith('https://commons.wikimedia.org/w/api.php') &&
      new URL(r.url()).searchParams.get('pssearch') === 'Rivers of Ukraine');
    await page.keyboard.type('Category:Rivers of Ukraine');
    await answered;
    // sent from the field itself, still focused (no blur makes the text an item first)
    await control.evaluate((el) => el.form.requestSubmit(document.getElementById('round-form-jurors-next')));
    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0].get('source')).toBe('Category:Rivers of Ukraine');
    // the boxes and the choice under More category options (its default), no operation select
    expect(sent[0].get('categoryCombine')).toBe('minus');
    expect(sent[0].has('categoryClause')).toBe(false);
    expect(sent[0].get('next')).toBe('jurors');
  });

test('the round form\'s category is selected on a click and edited by typing', { tag: '@A-01' },
  async ({ contextAs, fixture }) => {
    const context = await contextAs('admin');
    const page = await context.newPage();
    await noSuggestions(page);
    await page.goto(`/admin/rounds/edit?contestId=${fixture.contestId}`);
    await page.locator('a[href="#collapseImageFiltering"]').click();
    const wrapper = page.locator('#category-first .ts-wrapper');
    const input = page.locator('#source');
    // a category chosen (typed, then the field left) is an item, as one the form comes with
    await page.locator('#source-ts-control').focus();
    await page.keyboard.type('Category:Lakes of Ukraine');
    await page.keyboard.press('Tab');
    await expect(input).toHaveValue('Category:Lakes of Ukraine');

    await editsOnClick(page, wrapper, input, 'Category:Lakes of Ukraine', 'Category:Rivers of Ukraine');
  });
