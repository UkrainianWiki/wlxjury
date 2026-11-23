#!/usr/bin/env node
// Starts and stops the browser tests' server: gatling.setup.UiShotsServer, the Gatling fixture
// (a MariaDB testcontainer restored from data/cache/, built from the CSVs the first time) and the
// app with the production filters, the production content security policy (-Dui.csp=production)
// and CSRF checked on POSTs (-Dui.csrf=on); its image proxy fetches from a placeholder stub,
// never from Commons (-Dui.images=stub; -Dui.images=docs for the guides' screenshots, below).
// docs/testing/practices.md §10.
//
//   node fixture-server.mjs start   # compiles, starts it in the background, waits for server.json
//   node fixture-server.mjs stop    # asks it to stop (target/ui-shots/server.stop) and waits
//
// The server's output goes to target/ui-shots/server-browser-test.log. The run is anonymous on
// Wikimedia: the Commons and scalawiki credentials are removed from the server's environment
// (CLAUDE.md, Testing). sbt: `java -jar $SBT_LAUNCHER` if set or at the Windows installer's
// path, else `sbt` on the PATH (CI).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { docsScreenshotsOn } from './lib/server.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHOTS = path.join(ROOT, 'target', 'ui-shots');
const SERVER_JSON = path.join(SHOTS, 'server.json');
const STOP_FILE = path.join(SHOTS, 'server.stop');
// the sbt process's id, which `stop` waits for: the server removes server.json before it shuts
// down (the app, its database container, sbt's own exit writing and removing files under target/)
const PID_FILE = path.join(SHOTS, 'server.pid');
const LOG = path.join(SHOTS, 'server-browser-test.log');
const WINDOWS_LAUNCHER = 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar';
// a cold compile plus building the fixture from the CSVs; a cached fixture takes a few minutes
const START_TIMEOUT_MS = 40 * 60 * 1000;
const STOP_TIMEOUT_MS = 3 * 60 * 1000;
const CREDENTIALS = ['COMMONS_USER', 'COMMONS_PASSWORD', 'SCALAWIKI_LOGIN', 'SCALAWIKI_PASSWORD'];

// Docs mode (the user guides' screenshots, docs/user/images/README.md): the photos that
// docs-photos.py cached instead of the grey stub, with DOCS_SCREENSHOTS=1 or UI_IMAGES=docs;
// UI_IMAGES unset or stub keeps the stub, any other value is an error, and so is
// DOCS_SCREENSHOTS=1 with UI_IMAGES=stub (one of them is a mistake)
const UI_IMAGES = (process.env.UI_IMAGES ?? '').trim();
if (!['', 'stub', 'docs'].includes(UI_IMAGES)) throw new Error(`UI_IMAGES=${UI_IMAGES}: use docs or stub`);
if (docsScreenshotsOn() && UI_IMAGES === 'stub') {
  throw new Error('DOCS_SCREENSHOTS=1 needs the docs photos, not UI_IMAGES=stub: unset one of them');
}
const IMAGES = docsScreenshotsOn() || UI_IMAGES === 'docs' ? 'docs' : 'stub';
// DOCS_PHOTOS: the photo lists of docs mode (wlm-ua, wlm-intl, wle-ua, wle-intl, comma separated; default wlm-ua,wlm-intl)
const PHOTOS = (process.env.DOCS_PHOTOS || 'wlm-ua,wlm-intl').split(',').map((n) => n.trim()).filter(Boolean);
for (const name of PHOTOS) {
  if (!/^[a-z]+(-[a-z]+)*$/.test(name)) throw new Error(`DOCS_PHOTOS: '${name}' is not a list name (wlm-ua, wlm-intl, wle-ua, wle-intl)`);
}
const SBT_COMMANDS = [
  `set Test / javaOptions ++= Seq("-Xmx1g", "-Dfile.encoding=UTF-8", "-Dui.csp=production", "-Dui.csrf=on", "-Dui.images=${IMAGES}", "-Dui.photos=${PHOTOS.join(',')}")`,
  // Play's Assets serves public/ from the classpath, which the test classpath lacks
  'set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)',
  'Test/runMain gatling.setup.UiShotsServer',
];

function sbtCommand() {
  const launcher = process.env.SBT_LAUNCHER || (fs.existsSync(WINDOWS_LAUNCHER) ? WINDOWS_LAUNCHER : null);
  // sbt's own JVM: 2g, as the sbt script below; with 1g a cold worktree's first compile ran out of heap
  if (launcher) return ['java', ['-Xmx2g', '-Dfile.encoding=UTF-8', '-jar', launcher, ...SBT_COMMANDS]];
  return ['sbt', ['-J-Xmx2g', '-J-Dfile.encoding=UTF-8', ...SBT_COMMANDS]];
}

async function answers(baseUrl) {
  try {
    const response = await fetch(new URL('/health/ready', baseUrl), { signal: AbortSignal.timeout(5000) });
    return response.ok;
  } catch {
    return false;
  }
}

function readServerJson() {
  try {
    return JSON.parse(fs.readFileSync(SERVER_JSON, 'utf8'));
  } catch {
    return null;
  }
}

async function start() {
  const running = readServerJson();
  if (running && (await answers(running.baseUrl))) {
    // a server of the other image mode would serve the wrong images: never reuse it
    if (running.images !== IMAGES) {
      throw new Error(`already serving ${running.baseUrl} with images '${running.images ?? 'unknown'}', ` +
        `not '${IMAGES}': stop it first (node fixture-server.mjs stop)`);
    }
    console.log(`[fixture-server] already serving ${running.baseUrl} (images: ${IMAGES})`);
    return;
  }
  fs.mkdirSync(SHOTS, { recursive: true });
  for (const f of [SERVER_JSON, STOP_FILE]) fs.rmSync(f, { force: true });
  const env = { ...process.env };
  for (const name of CREDENTIALS) delete env[name];
  const [command, args] = sbtCommand();
  const log = fs.openSync(LOG, 'w');
  console.log(`[fixture-server] starting ${command} … ${SBT_COMMANDS.at(-1)}; log: ${path.relative(ROOT, LOG)}`);
  const started = Date.now();
  const child = spawn(command, args, {
    cwd: ROOT, env, detached: true, windowsHide: true, stdio: ['ignore', log, log],
    shell: process.platform === 'win32' && command === 'sbt',
  });
  let exited = null;
  child.on('exit', (code) => { exited = code ?? 'signal'; });
  fs.writeFileSync(PID_FILE, String(child.pid));
  while (true) {
    if (exited !== null) throw new Error(`the server exited (${exited}) before it was ready; see ${LOG}`);
    if (Date.now() - started > START_TIMEOUT_MS) {
      child.kill();
      throw new Error(`the server wasn't ready in ${START_TIMEOUT_MS / 60000} minutes; see ${LOG}`);
    }
    const ready = readServerJson();
    if (ready && (await answers(ready.baseUrl))) {
      console.log(`[fixture-server] serving ${ready.baseUrl} after ${Math.round((Date.now() - started) / 1000)} s`);
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  child.unref();
}

async function stop() {
  if (!fs.existsSync(SERVER_JSON)) {
    console.log('[fixture-server] not running (no server.json)');
    return;
  }
  fs.writeFileSync(STOP_FILE, '');
  const started = Date.now();
  while (fs.existsSync(SERVER_JSON)) {
    if (Date.now() - started > STOP_TIMEOUT_MS) throw new Error('the server did not stop in 3 minutes');
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  // then sbt's exit: until it is gone it still writes and removes files in the checkout (a CI
  // step hashing the checkout right after failed on a file gone under it), and a new sbt in the
  // same directory is refused ("server already booting")
  const pid = Number(fs.existsSync(PID_FILE) ? fs.readFileSync(PID_FILE, 'utf8') : 0);
  while (pid > 0 && alive(pid)) {
    if (Date.now() - started > STOP_TIMEOUT_MS) throw new Error(`sbt (pid ${pid}) did not exit in 3 minutes`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  fs.rmSync(PID_FILE, { force: true });
  console.log(`[fixture-server] stopped after ${Math.round((Date.now() - started) / 1000)} s`);
}

/** Whether the process `pid` still runs (signal 0 only checks; EPERM: it runs as another user). */
function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code === 'EPERM';
  }
}

const action = process.argv[2];
const actions = { start, stop };
if (!actions[action]) {
  console.error('usage: node fixture-server.mjs start|stop');
  process.exit(2);
}
actions[action]().catch((error) => {
  console.error(`[fixture-server] ${error.message}`);
  process.exit(1);
});
