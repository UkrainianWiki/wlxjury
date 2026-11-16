// The navbar's menus by keyboard and the organisers' bar on one line (the navigation proposal v3,
// §2.4 and §2.5). The menus open from real buttons (Space opens them, as Enter does), the arrow
// keys move through their items, Esc closes them and gives the focus back to the button. The
// organisers' bar of one juror's votes, on all files (the widest Votes button), keeps its pager on
// the line of its first menu in Ukrainian (the longest), with a long juror name: compact at 1280
// px, and full from 1800 px (the comments list 2100 px: main.css), with 10 px to spare, also with
// Linux's fonts.
//
// The fixture has no comments, so the Comments menu's way between the gallery, the files with
// comments and the comments list (comments=1, the Votes choice kept) is the Scala specs'
// (NavbarMenusSpec, JurorCommentsReportSpec); here its greyed-out items are skipped by the keys.
import { test, expect } from '../lib/fixtures.js';
import { galleryPath } from '../lib/actions.js';

/** The menu's toggle, focused, opened by Space; its menu shown. */
async function openBySpace(page, menu) {
  const button = menu.locator('> button');
  await button.focus();
  await page.keyboard.press('Space');
  await expect(button).toHaveAttribute('aria-expanded', 'true');
  await expect(menu.locator('.dropdown-menu')).toBeVisible();
  return button;
}

/** ArrowDown until the focused item's text starts with `name` (at most `max` presses). */
async function arrowTo(page, name, max = 6) {
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('ArrowDown');
    const text = await page.evaluate(() => document.activeElement && document.activeElement.textContent.trim());
    if (text && text.startsWith(name)) return;
  }
  throw new Error(`no item "${name}" reached by ArrowDown`);
}

test.describe('a juror\'s navbar menus by keyboard', () => {
  test.use({ role: 'juror' });

  test('Votes opens by Space, the arrows reach Unrated, Enter opens it; Esc gives the focus back', { tag: '@J-12' }, async ({ page, fixture }) => {
    await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
    const votes = page.locator('#votes-menu');
    // no visible word: the current choice and its count, "Votes" its accessible name's start
    await expect(votes.locator('> button')).toHaveAccessibleName(/^Votes: All \d{1,3}(,\d{3})* · \d{1,3}(,\d{3})* unrated$/);

    let button = await openBySpace(page, votes);
    await page.keyboard.press('Escape');
    await expect(votes.locator('.dropdown-menu')).toBeHidden();
    await expect(button).toBeFocused();

    await openBySpace(page, votes);
    await arrowTo(page, 'Unrated');
    await Promise.all([page.waitForURL(/filter=unrated/), page.keyboard.press('Enter')]);
    button = votes.locator('> button');
    await expect(button).toHaveAccessibleName(/^Votes: Unrated \d{1,3}(,\d{3})*$/);
    // in the menu shown (Playwright gives the closed menu's hidden items an empty name), the page's
    // item marked, the items' counts without brackets, read after the name ("Unrated 120")
    await openBySpace(page, votes);
    await expect(votes.getByRole('link', { name: /^Unrated \d{1,3}(,\d{3})*$/ })).toHaveAttribute('aria-current', 'page');
    await expect(votes.getByRole('link', { name: /^All \d{1,3}(,\d{3})*$/ })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('the order and Comments menus open by Space; greyed-out items are skipped', { tag: '@J-12' }, async ({ page, fixture }) => {
    await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
    // the order menu (dropDownSelection, a button now)
    const order = page.locator('.wlx-nav-order li.dropdown').first();
    await openBySpace(page, order);
    await page.keyboard.press('Escape');
    await expect(order.locator('> button')).toBeFocused();

    const comments = page.locator('#comments-menu');
    await openBySpace(page, comments);
    const disabled = comments.locator('.dropdown-item.disabled');
    // the fixture's juror has no files with comments: two items greyed out, not links
    await expect(disabled).toHaveCount(2);
    await expect(disabled.first()).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    expect(await page.evaluate(() => document.activeElement.textContent.trim())).toBe('All files');
    await page.keyboard.press('Escape');
    await expect(comments.locator('> button')).toBeFocused();
  });

  // The Votes button's "All 120 · 30 unrated": the count is the main number, the unrated after it
  // muted as the count in every state of the button and in both colour modes, never brighter
  // (main.css wlx-nav-suffix; the maintainer's note of 2026-11-16: pointed at, the count stayed
  // grey and the unrated turned white with the button's text)
  test('the Votes button\'s unrated is as muted as its count, at rest, pointed at, focused and open', { tag: '@J-12' }, async ({ page, fixture }) => {
    await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
    const menu = page.locator('#votes-menu');
    const button = menu.locator('> button');
    await expect(button.locator('.wlx-votes-unrated')).toBeVisible();
    await expect(button.locator('.wlx-votes-count')).toBeVisible();
    const colors = () => button.evaluate((b) => {
      const color = (selector) => getComputedStyle(b.querySelector(selector)).color;
      return { count: color('.wlx-votes-count'), unrated: color('.wlx-votes-unrated'), choice: color('.wlx-nav-choice') };
    });
    /** The colours once the button's colour transition (Bootstrap's .15s) is over. */
    const settled = async () => { await page.waitForTimeout(300); return colors(); };
    for (const mode of ['dark', 'light']) {
      await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
      await page.mouse.move(5, 700);
      const states = {};
      states.rest = await settled();
      await button.hover();
      states.hover = await settled();
      await page.mouse.move(5, 700);
      await button.focus();
      states.focus = await settled();
      await page.keyboard.press('Space');
      await expect(button).toHaveAttribute('aria-expanded', 'true');
      states.open = await settled();
      await page.keyboard.press('Escape');
      for (const [state, c] of Object.entries(states)) {
        expect(c.unrated, `${mode} mode, ${state}: the unrated as the count`).toBe(c.count);
      }
      // pointed at, the button's own text brightens (or darkens): its choice no longer the count's colour
      expect(states.hover.choice, `${mode} mode: the choice follows the button's hover colour`).not.toBe(states.hover.count);
    }
  });
});

// The widest bar the fixture can't show by itself, put in before measuring: a long juror name
// (the fixture's are "Juror N"; main.css wlx-truncate cuts it), five-digit counts on the Votes
// button (All and its unrated), a count on the Comments button (the fixture has no comments) and
// a long region (wlx-truncate cuts it too); in the comments list the Comments button filtered by a
// long author's name and the gallery's pager (the fixture's list has too few files for one). A
// classic 17 px scrollbar, which the headless
// browser's overlay scrollbars don't take: the window keeps the nominal width (the media queries
// see it, as a real browser's do) and the page 17 px less, by a right margin on the root element.
const LONG_NAME = 'Олександра Костянтинопольська';
const LONG_REGION = 'Дніпропетровська область';
const SCROLLBAR = 17;

/** The comments list's Comments button as the server draws it filtered by the author `name`
  * (navbar.scala.html commentsMenu: " · " and the name, cut short), after its words. */
async function commentsBy(page, name) {
  await page.locator('#comments-menu > button').evaluate((button, n) => {
    const words = button.querySelector('.wlx-nav-label');
    const dot = document.createElement('span');
    dot.className = 'wlx-nav-label';
    dot.textContent = ' · ';
    const shown = document.createElement('span');
    shown.className = 'wlx-truncate wlx-comments-by-name';
    shown.textContent = n;
    words.after(dot, shown);
  }, name);
}

async function widest(page) {
  await page.locator('#juror-menu .wlx-truncate').evaluate((span, name) => { span.textContent = name; }, LONG_NAME);
  await page.locator('#votes-menu .wlx-votes-count').evaluate((span) => { span.textContent = ' 38123'; });
  await page.locator('#votes-menu .wlx-votes-unrated').evaluate((span) => { span.textContent = ' · 12345 без оцінки'; });
  await page.locator('#comments-menu > button').evaluate((button) => {
    let count = button.querySelector('.wlx-comments-count');
    if (!count) {
      count = document.createElement('span');
      count.className = 'wlx-comments-count wlx-count';
      button.appendChild(count);
    }
    count.textContent = ' 31';
  });
  await page.locator('#regionmenu > button b').evaluate((b, region) => { b.textContent = region; }, LONG_REGION);
  if (await page.locator('#comments-by-header').count() > 0) await commentsBy(page, LONG_NAME);
}

// every view of the organisers' results bar: the gallery, the by-rate view (its page name longer),
// the file list and the comments list (filtered by an author: the maintainer's decisions of
// 2026-11-15), for an organizer and an admin (the most pages in the Pages menu; root's no wider),
// compact at 1280 px and full at the width it is full from. With Linux's fonts, wider than Windows', too:
// node linux-fonts.mjs (docs/testing/practices.md §10.6)
// the widths from which the results bar is full (main.css wlx-nav-results), the comments list's wider
const FULL_BAR = 1800;
const FULL = { 'comments list': 2100 };
const VIEWS = [['gallery', 'Галерея', (f) => galleryPath(f.roundBinaryId, f.accounts.juror.id)],
  ['by rate', 'За рейтингом', (f) => `/byrate/round/${f.roundBinaryId}/user/${f.accounts.juror.id}/page/1`],
  ['file list', null, (f) => `/filelist/round/${f.roundBinaryId}/user/${f.accounts.juror.id}/page/1`],
  ['comments list', null, (f) => `/comments/round/${f.roundBinaryId}/user/${f.accounts.juror.id}/page/1`]];
for (const [view, pageName, pathOf] of VIEWS) {
  const full = FULL[view] ?? FULL_BAR;
  for (const [role, width] of [['organizer', 1280], ['organizer', full], ['admin', 1280], ['admin', full]]) {
    test(`the ${role}'s ${view} bar of one juror's votes stays on one line at ${width} px in Ukrainian`, { tag: '@O-03' }, async ({ contextAs, fixture }, testInfo) => {
      const context = await contextAs(role, { viewport: { width, height: 900 }, locale: 'uk-UA' });
      await context.addCookies([{ name: 'PLAY_LANG', value: 'uk', url: fixture.baseUrl }]);
      const page = await context.newPage();
      // a list without a pager of its own (the fixture's comments list): the gallery's, put in its place
      await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
      const pagerHtml = await page.locator('.wlx-nav-pager').evaluate((ul) => ul.outerHTML);
      await page.goto(pathOf(fixture));
      await expect(page.locator('html')).toHaveAttribute('lang', 'uk');
      if (await page.locator('.wlx-nav-pager').count() === 0) {
        await page.locator('.wlx-nav-groups').evaluate((groups, html) => groups.insertAdjacentHTML('beforeend', html), pagerHtml);
      }
      if (view === 'comments list') {
        // the Comments menu's "Comments by" section (no menu of its own: one speech bubble)
        await expect(page.locator('#comments-menu #comments-by-header')).toHaveText('Коментарі від');
      }
      // on all files, the widest Votes button: its unrated read by screen readers only in the
      // compact bar, shown in the full one; the count always. The Pages menu: its icon alone when compact
      await expect(page.locator('#votes-menu > button')).toHaveAccessibleName(/^Голоси: Усі \d{1,3}(\s\d{3})* · \d{1,3}(\s\d{3})* без оцінки$/);
      await expect(page.locator('#pages-menu > button')).toHaveAccessibleName(pageName ? `Перейти до: ${pageName}` : /^Перейти до: /);
      // the juror menu's people icon (decision C of 2026-11-14) and the Votes button's icon, on All
      // the round's marks together (circle, ✓, ✗: the maintainer's decision of 2026-11-15) squeezed
      // into one icon's box (a triangle, main.css wlx-vote-icons), count in the width
      await expect(page.locator('#juror-menu > button > i.bi-people')).toBeVisible();
      await expect(page.locator('#votes-menu > button > .wlx-vote-icons > i')).toHaveCount(3);
      await expect(page.locator('#votes-menu > button > .wlx-vote-icons > i.bi-x-lg')).toBeVisible();
      const icons = await page.locator('#votes-menu > button > .wlx-vote-icons').boundingBox();
      const people = await page.locator('#juror-menu > button > i.bi-people').boundingBox();
      console.log(`[width] ${role} ${view} ${width} px: the Votes button's icon ${icons.width.toFixed(1)} px, the people icon ${people.width.toFixed(1)} px`);
      expect(icons.width).toBeLessThanOrEqual(people.width * 1.25);
      await widest(page);
      await page.evaluate((px) => { document.documentElement.style.marginRight = `${px}px`; }, SCROLLBAR);
      expect(await page.evaluate(() => Math.round(document.querySelector('nav.navbar').getBoundingClientRect().width))).toBe(width - SCROLLBAR);
      const shown = async (selector) => (await page.locator(selector).boundingBox()).width;
      if (width >= full) {
        expect(await shown('#votes-menu .wlx-votes-unrated')).toBeGreaterThan(20);
        expect(await shown('#pages-menu .wlx-nav-label')).toBeGreaterThan(20);
      } else {
        expect(await shown('#votes-menu .wlx-votes-unrated')).toBeLessThanOrEqual(1);
        expect(await shown('#pages-menu .wlx-nav-label')).toBeLessThanOrEqual(1);
      }
      expect(await shown('#votes-menu .wlx-votes-count')).toBeGreaterThan(20);
      expect(await shown('#comments-menu .wlx-comments-count')).toBeGreaterThan(10);
      const first = await page.locator('.wlx-nav-groups > ul > li').first().boundingBox();
      const pager = await page.locator('#pagenav').boundingBox();
      const logged = await page.locator('nav .container-fluid > .navbar-nav').last().boundingBox();
      const middle = (box) => box.y + box.height / 2;
      const oneLine = Math.abs(middle(pager) - middle(first)) < 4;
      // a wrapping bar: by how much it is short, the groups that wrapped (each wraps as a whole)
      // and the gaps before them against the room left after the first line's last group
      const shortOf = () => page.evaluate((loggedX) => {
        const lists = [...document.querySelectorAll('.wlx-nav-groups > ul')];
        const boxes = lists.map((ul) => ul.getBoundingClientRect());
        const mid = (b) => b.top + b.height / 2;
        const firstLine = boxes.filter((b) => Math.abs(mid(b) - mid(boxes[0])) < 4);
        const wrapped = boxes.filter((b) => Math.abs(mid(b) - mid(boxes[0])) >= 4);
        const gap = parseFloat(getComputedStyle(document.querySelector('.wlx-nav-groups')).columnGap) || 0;
        const room = loggedX - Math.max(...firstLine.map((b) => b.right));
        return Math.round(wrapped.reduce((sum, b) => sum + b.width + gap, 0) - room);
      }, logged.x);
      const short = await shortOf();
      const free = Math.round(logged.x - (pager.x + pager.width));
      const line = `${role} ${view} ${width - SCROLLBAR} px: ${oneLine ? `${free} px free after the pager` : `the pager wraps, ${short} px short`}`;
      testInfo.annotations.push({ type: 'width', description: line });
      console.log(`[width] ${line}`);
      expect(oneLine, `the pager on the first menu's line: ${line}`).toBe(true);
      // a margin for fonts a little wider than the runs' (Linux's DejaVu Sans is the widest seen)
      expect(free, `at least 10 px free after the pager: ${line}`).toBeGreaterThanOrEqual(10);
      await context.close();
    });
  }
}

// A bar without filters, order or pager (the rounds, the users: the maintainer's feedback on
// 26.11.9, its strip was empty with the pages behind the Pages menu): the staff pages as links,
// this page's marked, from 1280 px (main.css wlx-nav-pages-inline) on one line with the account
// menus, for root (the most pages: contests, contest files, users, rounds, statistics) with a long
// contest in the contest menu (put in when the account has none), in Ukrainian and in French (the
// longest names), with a classic scrollbar, also with Linux's fonts (node linux-fonts.mjs);
// narrower, the Pages menu instead.
const INLINE_FROM = 1280;
const LONG_CONTEST = 'Wiki Loves Monuments 2026 in Ukraine';
const INLINE_PAGES = {
  uk: { rounds: 'Раунди', users: 'Користувачі', pages: 'Перейти до' },
  fr: { rounds: 'Tours', users: 'Utilisateurs', pages: 'Aller à' },
};

/** The account menus' contest menu with a long contest, as logged.scala.html draws it, if the
  * account has no contest menu (one contest). */
async function longContestMenu(page) {
  await page.locator('nav .container-fluid > .navbar-nav').last().evaluate((ul, name) => {
    if (ul.querySelector('.bi-shuffle')) {
      const button = ul.querySelector('.bi-shuffle').closest('button');
      button.lastChild.textContent = ` ${name} `;
      return;
    }
    const li = document.createElement('li');
    li.className = 'nav-item dropdown';
    li.innerHTML = '<button type="button" class="nav-link dropdown-toggle"><i class="bi bi-shuffle"></i></button>';
    li.querySelector('button').append(` ${name} `);
    ul.prepend(li);
  }, LONG_CONTEST);
}

for (const [lang, names] of Object.entries(INLINE_PAGES)) {
  for (const [role, which] of [['admin', 'rounds'], ['root', 'users']]) {
    for (const width of [INLINE_FROM, 1440, INLINE_FROM - 1]) {
      const inline = width >= INLINE_FROM;
      test(`the ${role}'s ${which} bar in ${lang} at ${width} px: the pages ${inline ? 'as links on one line' : 'in the Pages menu'}`, { tag: '@O-03' }, async ({ contextAs, fixture }, testInfo) => {
        const context = await contextAs(role, { viewport: { width, height: 900 } });
        await context.addCookies([{ name: 'PLAY_LANG', value: lang, url: fixture.baseUrl }]);
        const page = await context.newPage();
        await page.goto(`/admin/${which}?contestId=${fixture.contestId}`);
        await expect(page.locator('html')).toHaveAttribute('lang', lang);
        const group = page.locator('.wlx-nav-links.wlx-nav-pages-inline');
        const links = group.locator('> li.wlx-page-link');
        const menu = page.locator('#pages-menu');
        const here = group.getByRole('link', { name: names[which], exact: true });
        if (!inline) {
          await expect(links.first()).toBeHidden();
          await expect(menu.locator('> button')).toBeVisible();
          await expect(menu.locator('> button')).toHaveAccessibleName(`${names.pages}: ${names[which]}`);
          await context.close();
          return;
        }
        await expect(menu).toBeHidden();
        await expect(links).toHaveCount(role === 'root' ? 5 : 4);
        for (const link of await links.all()) await expect(link).toBeVisible();
        await expect(here).toHaveAttribute('aria-current', 'page');
        await expect(links.locator('a[aria-current]')).toHaveCount(1);
        await longContestMenu(page);
        await page.evaluate((px) => { document.documentElement.style.marginRight = `${px}px`; }, SCROLLBAR);
        const boxes = await links.evaluateAll((lis) => lis.map((li) => li.getBoundingClientRect().toJSON()));
        const logged = await page.locator('nav .container-fluid > .navbar-nav').last().boundingBox();
        const middle = (box) => box.y + box.height / 2;
        const oneLine = boxes.every((b) => Math.abs(middle(b) - middle(logged)) < 4);
        const free = Math.round(logged.x - Math.max(...boxes.map((b) => b.right)));
        const line = `${role} ${which} ${lang} ${width - SCROLLBAR} px: ${oneLine ? `${free} px free before the account menus` : 'the links wrap'}`;
        testInfo.annotations.push({ type: 'width', description: line });
        console.log(`[width] ${line}`);
        expect(oneLine, `the links on the account menus' line: ${line}`).toBe(true);
        expect(free, `at least 10 px free before the account menus: ${line}`).toBeGreaterThanOrEqual(10);
        // a link opens its page, marked there
        const other = which === 'rounds' ? 'users' : 'rounds';
        await Promise.all([page.waitForURL(new RegExp(`/admin/${other}`)), group.getByRole('link', { name: names[other], exact: true }).click()]);
        await expect(page.locator('.wlx-nav-pages-inline').getByRole('link', { name: names[other], exact: true })).toHaveAttribute('aria-current', 'page');
        await context.close();
      });
    }
  }
}

// One count style (snippets/count.scala.html, main.css wlx-count; the maintainer's decision of
// 2026-11-16): the contest files page's tabs show their counts whether active or not, the
// conflicts of interest's too, without brackets, in the muted colour and figures of the Votes
// button's count in the bar.
test('the contest files tabs\' counts are shown on inactive tabs, styled as the bar\'s', { tag: '@O-03' }, async ({ contextAs, fixture }) => {
  const context = await contextAs('admin');
  const page = await context.newPage();
  await page.goto(galleryPath(fixture.roundBinaryId, fixture.accounts.juror.id));
  const style = (locator) => locator.evaluate((el) => {
    const s = getComputedStyle(el);
    return { color: s.color, figures: s.fontVariantNumeric, size: s.fontSize };
  });
  const bar = await style(page.locator('#votes-menu .wlx-votes-count'));
  await page.goto(`/contest/${fixture.contestId}/files`);
  const tabs = page.locator('#file-tabs a.nav-link');
  await expect(tabs).toHaveCount(3);
  await expect(page.locator('#coi-tab')).not.toHaveClass(/active/);
  for (const tab of await tabs.all()) {
    const count = tab.locator('> span.wlx-count');
    await expect(count).toBeVisible();
    // grouped as the page's language (en: 38,367), as roundstat-counts.js's toLocaleString
    await expect(count).toHaveText(/^\s\d{1,3}(,\d{3})*$/);
    await expect(tab).not.toContainText('(');
    expect(await style(count)).toEqual(bar);
  }
  // on its own tab the conflicts of interest's count stays the same
  const coiCount = (await page.locator('#coi-tab > span.wlx-count').textContent()).trim();
  await page.locator('#coi-tab').click();
  await expect(page.locator('#coi-tab')).toHaveClass(/active/);
  await expect(page.locator('#coi-tab > span.wlx-count')).toHaveText(` ${coiCount}`);
  await context.close();
});
