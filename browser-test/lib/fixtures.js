// The browser tests' `test`: every context is guarded, and every test fails on a problem seen on
// any of its pages (docs/testing/practices.md §10.2):
//   - files from upload.wikimedia.org (requests of type image or media only: a video then
//     doesn't play) and this host's image proxy
//     (/wikipedia/commons/thumb/ on this host only) get a placeholder; a request to any other host,
//     other wikimedia.org hosts and lookalikes included, is refused and reported, so no test
//     depends on the network;
//   - CSP violations (the securitypolicyviolation event, in every engine), console errors,
//     uncaught page errors, responses of 400 or more and failed requests are reported.
// A test that opens a 403 or 404 page on purpose says so with diagnostics.expectStatus(code),
// which accepts that status for the page's own document only.
//
// `role` (test.use({ role: 'juror' })) signs the test's page in with that role's saved state
// (auth.setup.js); `contextAs(role)` opens another guarded context, for a second role or a
// fresh session; `routelessAs(role)` opens one without routes, for the Back checks (below).
//
// `storyLabels` (auto): a test's story tags ('@J-21', docs/testing/stories.md) become its Allure
// labels: epic the role, feature the guide section (else the role), story the ID and sentence; a
// tag the catalogue lacks or retired fails the test (testing practices §9.4).
import { test as base, expect } from '@playwright/test';
import * as allure from 'allure-js-commons';
import { PLACEHOLDER_PNG } from './placeholder.js';
import { isProxyConsoleLine } from './proxy-errors.js';
import { authFile, fixtureFor } from './server.js';
import { catalogue, ROLES, storyIds } from './stories.js';

export { expect };

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1']);
const COMMONS_UPLOAD = 'upload.wikimedia.org';

/** What the guard's route answers with the placeholder: a file from Commons' upload host (a
  * video's too: it then doesn't play), or a thumbnail from this host's image proxy. */
function isStubbedImage(url, request) {
  if (LOCAL_HOSTS.has(url.hostname)) return url.pathname.startsWith('/wikipedia/commons/thumb/');
  return isCommonsUpload(url, request);
}

// a Commons file's path on its upload host, an image's or a video's
const COMMONS_FILE = /^\/wikipedia\/commons\/.+\.(jpe?g|png|gif|tiff?|svg|webp|webm|ogv|ogg|mp4|mpe?g)$/i;

/** A file from Commons' upload host, as a page loads one: an image, or a video's source (the
  * fixture has some videos). WebKit on Linux types some image loads "other" (the large view's
  * image, 2026-11-04 CI): "other" counts only for a Commons file's path, so a fetch, a script or
  * any other path there is still refused. */
function isCommonsUpload(url, request) {
  if (url.protocol !== 'https:' || url.hostname !== COMMONS_UPLOAD) return false;
  const type = request.resourceType();
  return type === 'image' || type === 'media' || (type === 'other' && COMMONS_FILE.test(decodeURI(url.pathname)));
}

const pathOf = (page) => {
  try {
    const u = new URL(page.url());
    return u.pathname + u.search;
  } catch {
    return page.url();
  }
};

const pageOf = (request) => {
  try {
    return request.frame().page();
  } catch {
    return { url: () => '(no page)' }; // a service worker's request has no frame
  }
};

// Not problems in any test: Firefox logs a font whose download the next navigation cancelled
// (NS_BINDING_ABORTED, 0x804B0002 = 2152398850) as a console error.
const ALWAYS_ALLOWED = [
  /^console error: .*downloadable font: download failed .* status=2152398850 /,
];

// A request the page dropped by leaving it or replacing it, not a failure of the request
const CANCELLED = /^(net::ERR_ABORTED|NS_BINDING_ABORTED|cancelled|Load request cancelled)/i;

export class Diagnostics {
  constructor(browserName) {
    this.browserName = browserName;
    this.proxied = [];
    this.problems = [];
    this.allowed = [...ALWAYS_ALLOWED];
    this.otherHosts = [];
    this.refused = new WeakSet();
  }

  /** Accepts problems whose text matches `pattern` in this test (a page it expects to fail). */
  allow(pattern) {
    this.allowed.push(pattern);
  }

  /** The test opens a page answered with `status`: that response of the page's own document,
    * and Chromium's console line for it. */
  expectStatus(status) {
    this.allow(new RegExp(`^HTTP ${status} document: `));
    this.allow(new RegExp(`^console error: Failed to load resource: the server responded with a status of ${status}\\b`));
  }

  add(kind, page, text) {
    const line = `${kind}: ${text}`;
    if (this.allowed.some((p) => p.test(line))) return;
    this.problems.push(`${line} (on ${pathOf(page)})`);
  }

  /** Removes and returns the problems of `kind` (the guards' own tests). */
  take(kind) {
    const taken = this.problems.filter((p) => p.startsWith(`${kind}: `));
    this.problems = this.problems.filter((p) => !p.startsWith(`${kind}: `));
    return taken;
  }
}

/** The CSP probe and the listeners of every page of `context`; `routed`: whether the guard's
  * route answers the requests (else the routeless context's proxy refuses other hosts). */
async function watchPages(context, diagnostics, { routed }) {
  await context.exposeBinding('__wlxCspViolation', ({ page }, v) => {
    diagnostics.add('CSP violation', page, `${v.directive} blocked ${v.blocked || '(inline)'} at ${v.source}:${v.line}`);
  });
  await context.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      // eslint-disable-next-line no-underscore-dangle
      if (window.__wlxCspViolation) {
        window.__wlxCspViolation({
          directive: e.violatedDirective, blocked: e.blockedURI, source: e.sourceFile, line: e.lineNumber,
        });
      }
    });
  });
  const watch = (page) => {
    // the routeless browser: this page's refused uploads, and its dead-proxy console lines
    // without a location to tie them to one (WebKit's)
    const proxied = { uploadsRefused: 0, lines: [] };
    if (!routed) diagnostics.proxied.push({ page, proxied });
    page.on('console', (m) => {
      if (m.type() !== 'error') return;
      // the routeless browser: the engine's line for an upload the dead proxy refused, tied to
      // that upload by the failed resource's URL (the message's location), or, where the line
      // carries none, to a refused upload of the same page (checked after the test), never
      // allowed by its text alone
      if (!routed && isProxyConsoleLine(diagnostics.browserName, m.text())) {
        const location = m.location()?.url;
        if (isUploadUrl(location)) return;
        if (!location || location === page.url()) {
          proxied.lines.push(m.text().slice(0, 300));
          return;
        }
      }
      diagnostics.add('console error', page, m.text().slice(0, 300));
    });
    // a WebSocket: the guard's route doesn't see one (that takes routeWebSocket); the app opens none
    page.on('websocket', (ws) => {
      const url = new URL(ws.url());
      if (LOCAL_HOSTS.has(url.hostname)) return;
      diagnostics.otherHosts.push(url.hostname);
      diagnostics.add('other host', page, `${url.origin} (WebSocket)`);
    });
    // with the top of its stack: where it was thrown
    page.on('pageerror', (e) => diagnostics.add('page error', page,
      `${String(e).slice(0, 200)} at ${(e.stack ?? '').split('\n').slice(1, 3).join(' < ').slice(0, 300)}`));
    page.on('response', (r) => {
      if (r.status() < 400) return;
      const document = r.request().isNavigationRequest() && r.frame() === page.mainFrame();
      diagnostics.add(`HTTP ${r.status()}${document ? ' document' : ''}`, page, new URL(r.url()).pathname);
    });
    page.on('requestfailed', (r) => {
      const failure = r.failure()?.errorText ?? '';
      if (diagnostics.refused.has(r) || CANCELLED.test(failure)) return;
      const url = new URL(r.url());
      // the routeless browser: the dead proxy refuses upload.wikimedia.org's files (the large
      // image, which falls back to this host's)
      if (!routed && isCommonsUpload(url, r)) {
        proxied.uploadsRefused++;
        return;
      }
      diagnostics.add('request failed', page, `${url.origin}${url.pathname} (${failure})`);
    });
    if (!routed) {
      page.on('request', (r) => {
        const url = new URL(r.url());
        if (LOCAL_HOSTS.has(url.hostname)) return;
        if (isCommonsUpload(url, r)) return; // refused by the dead proxy
        diagnostics.otherHosts.push(url.hostname);
        diagnostics.add('other host', page, `${url.origin} refused`);
      });
    }
  };
  context.pages().forEach(watch);
  context.on('page', watch);
}

/** Routes, the CSP probe and the listeners of every page of `context`. */
export async function guard(context, diagnostics) {
  await context.route('**/*', (route) => {
    const request = route.request();
    const url = new URL(request.url());
    // The user guides' screenshots (DOCS_SCREENSHOTS=1, with the server's -Dui.images=docs): the
    // proxy's photos are not replaced by the grey placeholder, and an image of the upload host
    // is fetched from this host's proxy instead (a thumbnail by its own path, an original as a
    // 1280 px thumbnail); anything else of the upload host (a video) keeps the placeholder
    if (process.env.DOCS_SCREENSHOTS && isStubbedImage(url, request) && /\.(jpe?g|png)$/i.test(url.pathname)) {
      if (LOCAL_HOSTS.has(url.hostname)) return route.continue();
      const m = url.pathname.match(/^\/wikipedia\/commons\/(?:thumb\/)?(\w\/\w\w\/[^/]+?)(?:\/[^/]+)?$/);
      const name = m ? m[1] : null;
      const local = url.pathname.startsWith('/wikipedia/commons/thumb/') ? url.pathname
        : name ? `/wikipedia/commons/thumb/${name}/1280px-${name.split('/')[2]}` : null;
      if (local) return route.fetch({ url: new URL(local, fixtureFor('chromium').baseUrl).href }).then((r) => route.fulfill({ response: r }));
    }
    if (isStubbedImage(url, request)) return route.fulfill({ status: 200, contentType: 'image/png', body: PLACEHOLDER_PNG });
    if (LOCAL_HOSTS.has(url.hostname)) return route.continue();
    diagnostics.otherHosts.push(url.hostname);
    diagnostics.refused.add(request);
    diagnostics.add('other host', pageOf(request), `${url.origin} refused (${request.resourceType()})`);
    return route.abort('blockedbyclient');
  });
  await watchPages(context, diagnostics, { routed: true });
}

const CONTEXT_DEFAULTS = {
  viewport: { width: 1366, height: 900 },
  locale: 'en-US',
  colorScheme: 'light',
};

// The Back checks' browser. Any route turns the browser's HTTP cache off (Playwright, in all
// three engines), and Playwright starts Chromium with --disable-back-forward-cache: with them a
// page Back brings back is always loaded again, so a page without no-store would pass. This
// browser has neither: other hosts go to a proxy that isn't there (port 9, refused), this host
// bypasses it, and the fixture's image proxy fetches from its stub (-Dui.images=stub). The
// large image from upload.wikimedia.org fails through the proxy and falls back to this host's.
const DEAD_PROXY = { server: 'http://127.0.0.1:9', bypass: 'localhost,127.0.0.1' };

function routelessLaunch(browserName) {
  if (browserName === 'chromium') {
    // the full Chromium (not the headless shell), with its back/forward cache unless
    // WLX_CHROMIUM_BFCACHE=off (to tell the back/forward cache from the HTTP cache)
    return {
      channel: 'chromium',
      proxy: DEAD_PROXY,
      ignoreDefaultArgs: process.env.WLX_CHROMIUM_BFCACHE === 'off' ? [] : ['--disable-back-forward-cache'],
    };
  }
  return { proxy: DEAD_PROXY };
}

// the engine's console line for a resource the dead proxy refused: lib/proxy-errors.js, per
// engine and platform; accepted only for a file of upload.wikimedia.org (isUploadUrl), or a
// refused upload of the same page
function isUploadUrl(u) {
  try {
    const url = new URL(u);
    return url.protocol === 'https:' && url.hostname === COMMONS_UPLOAD;
  } catch {
    return false;
  }
}

export const test = base.extend({
  fixture: async ({ browserName }, use) => use(fixtureFor(browserName)),
  baseURL: async ({ fixture }, use) => use(fixture.baseUrl),
  role: [null, { option: true }],
  storageState: async ({ role, browserName, storageState }, use) => use(role ? authFile(browserName, role) : storageState),
  diagnostics: async ({ browserName }, use) => use(new Diagnostics(browserName)),
  context: async ({ context, diagnostics }, use) => {
    await guard(context, diagnostics);
    await use(context);
  },
  contextAs: async ({ browser, browserName, fixture, diagnostics }, use) => {
    const opened = [];
    await use(async (role, options = {}) => {
      const context = await browser.newContext({
        ...CONTEXT_DEFAULTS,
        baseURL: fixture.baseUrl,
        storageState: role ? authFile(browserName, role) : undefined,
        ...options,
      });
      await guard(context, diagnostics);
      opened.push(context);
      return context;
    });
    await Promise.all(opened.map((c) => c.close()));
  },
  routelessBrowser: [async ({ playwright, browserName }, use) => {
    const browser = await playwright[browserName].launch(routelessLaunch(browserName));
    await use(browser);
    await browser.close();
  }, { scope: 'worker' }],
  routelessAs: async ({ routelessBrowser, browserName, fixture, diagnostics }, use) => {
    const opened = [];
    await use(async (role) => {
      const context = await routelessBrowser.newContext({
        ...CONTEXT_DEFAULTS,
        baseURL: fixture.baseUrl,
        storageState: role ? authFile(browserName, role) : undefined,
      });
      await watchPages(context, diagnostics, { routed: false });
      opened.push(context);
      return context;
    });
    await Promise.all(opened.map((c) => c.close()));
  },
  storyLabels: [async ({}, use, testInfo) => {
    const ids = storyIds(testInfo.tags);
    const stories = catalogue();
    const epics = new Set(ids.map((id) => ROLES[id[0]]));
    const features = new Set(ids.map((id) => stories[id].section ?? ROLES[id[0]]));
    for (const epic of epics) await allure.epic(epic);
    for (const feature of features) await allure.feature(feature);
    for (const id of ids) await allure.story(`${id} ${stories[id].text}`);
    await use();
  }, { auto: true }],
  // after each test: nothing went wrong on any page it opened
  noPageProblems: [async ({ diagnostics }, use) => {
    await use();
    // a routeless page's dead-proxy console lines without a location: only with a refused upload
    for (const { page, proxied } of diagnostics.proxied) {
      if (proxied.uploadsRefused === 0) proxied.lines.forEach((line) => diagnostics.add('console error', page, line));
    }
    expect(diagnostics.problems, 'CSP violations, console errors, failed requests or other hosts').toEqual([]);
  }, { auto: true }],
});
