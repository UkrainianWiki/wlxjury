// The browser tests' `test`: every context is guarded, and every test fails on a problem seen on
// any of its pages (docs/testing/practices.md §10):
//   - images from Commons and the image proxy get a placeholder; any other host is refused and
//     reported, so no test depends on the network;
//   - CSP violations (the securitypolicyviolation event, in every engine), console errors,
//     uncaught page errors and responses of 400 or more are reported.
// A test that expects a 403 or 404 page says so with diagnostics.expectStatus(code).
//
// `role` (test.use({ role: 'juror' })) signs the test's page in with that role's saved state
// (auth.setup.js); `contextAs(role)` opens another guarded context, for a second role or a
// fresh session.
import { test as base, expect } from '@playwright/test';
import { PLACEHOLDER_PNG } from './placeholder.js';
import { authFile, fixtureFor } from './server.js';

export { expect };

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1']);

/** An image the pages load from Commons, or through this host's image proxy, or a thumbnail
  * URL of an image without one ("/<width>px-…"), as scripts/ui-shots.py stubs them. */
function isCommonsImage(url) {
  return url.pathname.startsWith('/wikipedia/commons/') || url.hostname.endsWith('wikimedia.org')
    || /^\/\d+px-/.test(url.pathname);
}

const pathOf = (page) => {
  try {
    const u = new URL(page.url());
    return u.pathname + u.search;
  } catch {
    return page.url();
  }
};

export class Diagnostics {
  constructor() {
    this.problems = [];
    this.allowed = [];
    this.otherHosts = [];
  }

  /** Accepts problems whose text matches `pattern` in this test (a page it expects to fail). */
  allow(pattern) {
    this.allowed.push(pattern);
  }

  /** The test opens a page answered with `status`: its response, and Chromium's console line for it. */
  expectStatus(status) {
    this.allow(new RegExp(`^HTTP ${status} `));
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

/** Routes, the CSP probe and the listeners of every page of `context`. */
export async function guard(context, diagnostics) {
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (isCommonsImage(url)) return route.fulfill({ status: 200, contentType: 'image/png', body: PLACEHOLDER_PNG });
    if (LOCAL_HOSTS.has(url.hostname)) return route.continue();
    let page = { url: () => '(no page)' };
    try {
      page = route.request().frame().page();
    } catch {
      // a service worker's request has no frame
    }
    diagnostics.otherHosts.push(url.hostname);
    diagnostics.add('other host', page, `${url.origin} refused`);
    return route.abort('blockedbyclient');
  });
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
    page.on('console', (m) => {
      if (m.type() === 'error') diagnostics.add('console error', page, m.text().slice(0, 300));
    });
    page.on('pageerror', (e) => diagnostics.add('page error', page, String(e).slice(0, 300)));
    page.on('response', (r) => {
      if (r.status() >= 400) diagnostics.add(`HTTP ${r.status()}`, page, new URL(r.url()).pathname);
    });
  };
  context.pages().forEach(watch);
  context.on('page', watch);
}

const CONTEXT_DEFAULTS = {
  viewport: { width: 1366, height: 900 },
  locale: 'en-US',
  colorScheme: 'light',
};

export const test = base.extend({
  fixture: async ({ browserName }, use) => use(fixtureFor(browserName)),
  baseURL: async ({ fixture }, use) => use(fixture.baseUrl),
  role: [null, { option: true }],
  storageState: async ({ role, browserName, storageState }, use) => use(role ? authFile(browserName, role) : storageState),
  diagnostics: async ({}, use) => use(new Diagnostics()),
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
  // after each test: nothing went wrong on any page it opened
  noPageProblems: [async ({ diagnostics }, use) => {
    await use();
    expect(diagnostics.problems, 'CSP violations, console errors, failed responses or other hosts').toEqual([]);
  }, { auto: true }],
});
