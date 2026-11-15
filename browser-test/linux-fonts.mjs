#!/usr/bin/env node
// Runs browser tests with Linux's fonts, as CI's Ubuntu runners have them, from a Windows or macOS
// machine: in the official Playwright image of the version in package-lock.json (its browsers and
// fonts), against the fixture server on the host (node fixture-server.mjs start first). The
// navbar's width tests measure text, which Linux draws wider than Windows (docs/testing/practices.md
// §10.6), in DejaVu Sans as on CI's runner (LINUX_FONTS=image: the image's own fonts). The
// container reaches the host as host.docker.internal; a forwarder inside it keeps the
// fixture's own address (http://localhost:<port> from server.json), so cookies and the server's
// allowed hosts are as on the host. The repository is mounted at /work: results go to the usual
// target/browser-test/ (written as root in the container: on a Linux host they are root's, to be
// removed with sudo or a container; Docker Desktop on Windows and macOS maps them to the user).
// Arguments are Playwright's:
//
//   node linux-fonts.mjs tests/navbar.spec.js -g "one line" --project=chromium
//   node linux-fonts.mjs tests/navbar.spec.js -g "one line"        # all three engines
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const SERVER_JSON = path.join(ROOT, 'target', 'ui-shots', 'server.json');

const lock = JSON.parse(fs.readFileSync(path.join(HERE, 'package-lock.json'), 'utf8'));
const version = lock.packages['node_modules/@playwright/test'].version;
// Ubuntu 24.04, as CI's ubuntu-24.04 runners (.github/workflows/browser.yml); pinned by digest
// for the versions measured with it (a tag can be pushed again), by tag for another version
const DIGESTS = {
  '1.64.1': 'sha256:2f05f8a734509a7b3f26a912639488ee08f58a7b292d14d62271bcff9038768f',
};
const image = `mcr.microsoft.com/playwright:v${version}-noble${DIGESTS[version] ? `@${DIGESTS[version]}` : ''}`;

if (!fs.existsSync(SERVER_JSON)) {
  console.error(`${SERVER_JSON} is missing: start the fixture first (node fixture-server.mjs start)`);
  process.exit(2);
}
const port = new URL(JSON.parse(fs.readFileSync(SERVER_JSON, 'utf8')).baseUrl).port;

// localhost:<port> in the container forwarded to the host's
const forwarder = `require('net').createServer((c) => { const s = require('net').connect(${port}, 'host.docker.internal');` +
  ` c.pipe(s); s.pipe(c); c.on('error', () => s.destroy()); s.on('error', () => c.destroy()); }).listen(${port}, '127.0.0.1')`;
const quoted = process.argv.slice(2).map((a) => `'${a.replace(/'/g, `'\\''`)}'`).join(' ');
// CI's runner has DejaVu Sans, which fontconfig gives for system-ui (Bootstrap's first font), wider
// than the image's own fallback (WenQuanYi Zen Hei); LINUX_FONTS=image keeps the image's fonts
// (an install that fails, or another font for system-ui, stops the run: its widths would be
// another machine's)
const fonts = process.env.LINUX_FONTS === 'image' ? 'true'
  : '{ apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq fonts-dejavu-core; } >/tmp/fonts.log 2>&1' +
    ' || { cat /tmp/fonts.log; echo "[linux-fonts] installing fonts-dejavu-core failed" >&2; exit 3; }; ' +
    'fc-match system-ui | grep -q DejaVu || { echo "[linux-fonts] system-ui is not DejaVu Sans: $(fc-match system-ui)" >&2; exit 3; }';
// the forwarder started, then waited for (up to 10 s) before the tests
const ready = `for i in $(seq 100); do (exec 3<>/dev/tcp/127.0.0.1/${port}) 2>/dev/null && break; sleep 0.1; done; ` +
  `(exec 3<>/dev/tcp/127.0.0.1/${port}) 2>/dev/null || { echo "[linux-fonts] the forwarder isn't listening" >&2; exit 4; }`;
const script = `set -o pipefail; ${fonts}; echo "[linux-fonts] system-ui: $(fc-match system-ui)"; ` +
  `node -e "${forwarder}" & ${ready}; ` +
  `cd /work/browser-test && node node_modules/@playwright/test/cli.js test ${quoted}`;

// Chromium's shared memory: 1 GB, not the host's IPC namespace
const args = ['run', '--rm', '--shm-size=1g', '--add-host=host.docker.internal:host-gateway',
  '-v', `${ROOT}:/work`, '-e', 'CI=', image, 'bash', '-c', script];
console.log(`[linux-fonts] ${image}, the fixture at localhost:${port}`);
const run = spawnSync('docker', args, { stdio: 'inherit', env: { ...process.env, MSYS_NO_PATHCONV: '1' } });
process.exit(run.status ?? 1);
