// A unit check of the dead proxy's error patterns against the texts each platform gave
// (node lib/proxy-errors.check.mjs): Windows' only run locally, Linux' only in CI.
import assert from 'node:assert/strict';
import { isProxyConsoleLine, isProxyError } from './proxy-errors.js';

const seen = {
  chromium: ['net::ERR_PROXY_CONNECTION_FAILED'],
  firefox: ['NS_ERROR_PROXY_CONNECTION_REFUSED'],
  webkit: ['Could not connect to server', 'Could not connect to proxy server 127.0.0.1: Connection refused'],
};
for (const [engine, texts] of Object.entries(seen)) {
  for (const text of texts) {
    assert.ok(isProxyError(engine, text), `${engine}: ${text}`);
    assert.ok(isProxyConsoleLine(engine, `Failed to load resource: ${text}`), `${engine}'s console line: ${text}`);
    // another engine's text, or a near one, isn't
    for (const other of Object.keys(seen).filter((e) => e !== engine)) {
      assert.ok(!isProxyError(other, text), `${other} must not accept ${engine}'s ${text}`);
    }
  }
}
for (const near of ['Could not connect to proxy server 10.0.0.1: Connection refused', 'Could not connect to server.',
  'Could not connect to proxy server 127.0.0.1: Connection timed out', 'net::ERR_CONNECTION_REFUSED', '']) {
  assert.ok(!isProxyError('webkit', near) && !isProxyError('chromium', near), `not a dead-proxy error: ${near}`);
}
assert.ok(!isProxyConsoleLine('webkit', 'Could not connect to server'), 'a console line starts "Failed to load resource: "');
console.log('[proxy-errors] the patterns accept each platform\'s text and nothing near it');
