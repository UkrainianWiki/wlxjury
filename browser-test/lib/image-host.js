// A local stand-in for upload.wikimedia.org that a real HTTP cache can use, for the tests of what
// the browser caches (large-next-image.js): https on a local port, one PNG for every path, with
// Wikimedia's Cache-Control and Timing-Allow-Origin, and a log of the paths asked for. A Chromium
// started with `--host-resolver-rules=MAP upload.wikimedia.org 127.0.0.1:<port>` and
// `--ignore-certificate-errors` (launchChromiumOnImageHost) reaches it as the real host. Not a
// route: any route turns the HTTP cache off (fixtures.js, routelessBrowser).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { png } from './placeholder.js';

export async function startImageHost() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wlx-image-host-'));
  const key = path.join(dir, 'key.pem');
  const cert = path.join(dir, 'cert.pem');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-subj', '/CN=upload.wikimedia.org', '-keyout', key, '-out', cert], { stdio: 'ignore' });
  const body = png(64, 48, [120, 140, 160]);
  const seen = [];
  const server = https.createServer({ key: fs.readFileSync(key), cert: fs.readFileSync(cert) }, (req, res) => {
    seen.push(decodeURI(req.url));
    res.writeHead(200, {
      'content-type': 'image/png',
      'content-length': body.length,
      'cache-control': 'public, max-age=3600',
      'timing-allow-origin': '*',
    });
    res.end(body);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  return {
    port,
    /** the paths asked for, in order */
    seen,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

/** The full Chromium with the upload host mapped to `host` (no proxy, no routes: its HTTP cache works). */
export function launchChromiumOnImageHost(playwright, host) {
  return playwright.chromium.launch({
    channel: 'chromium',
    args: [`--host-resolver-rules=MAP upload.wikimedia.org 127.0.0.1:${host.port}`, '--ignore-certificate-errors'],
  });
}
