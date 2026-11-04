// How each engine words a connection refused by the routeless browser's dead proxy
// (lib/fixtures.js DEAD_PROXY), per platform: the texts differ, and a run on one platform doesn't
// prove another's (testing practices §10.3). Seen on 2026-11-04: Windows locally, Linux in CI
// (ubuntu-24.04), Chromium 156, Firefox 157, WebKit 27.2.
export const PROXY_ERRORS = {
  // the same on Windows and Linux
  chromium: [/^net::ERR_PROXY_CONNECTION_FAILED$/],
  firefox: [/^NS_ERROR_PROXY_CONNECTION_REFUSED$/],
  webkit: [
    /^Could not connect to server$/, // Windows
    /^Could not connect to proxy server 127\.0\.0\.1: Connection refused$/, // Linux
  ],
};

/** Whether `text` is `browserName`'s error for the dead proxy. */
export function isProxyError(browserName, text) {
  return (PROXY_ERRORS[browserName] ?? []).some((p) => p.test(text ?? ''));
}

/** Whether `text` is the engine's console line for a resource the dead proxy refused. */
export function isProxyConsoleLine(browserName, text) {
  const m = /^Failed to load resource: (.*)$/.exec(text ?? '');
  return !!m && isProxyError(browserName, m[1]);
}
