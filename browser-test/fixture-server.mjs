#!/usr/bin/env node
// Starts and stops the browser tests' server: gatling.setup.UiShotsServer, the Gatling fixture
// (a MariaDB testcontainer restored from data/cache/, built from the CSVs the first time) and the
// app with the production filters, the production content security policy (-Dui.csp=production)
// and CSRF checked on POSTs (-Dui.csrf=on). docs/testing/practices.md §10.
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

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHOTS = path.join(ROOT, 'target', 'ui-shots');
const SERVER_JSON = path.join(SHOTS, 'server.json');
const STOP_FILE = path.join(SHOTS, 'server.stop');
const LOG = path.join(SHOTS, 'server-browser-test.log');
const WINDOWS_LAUNCHER = 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar';
// a cold compile plus building the fixture from the CSVs; a cached fixture takes a few minutes
const START_TIMEOUT_MS = 40 * 60 * 1000;
const STOP_TIMEOUT_MS = 3 * 60 * 1000;
const CREDENTIALS = ['COMMONS_USER', 'COMMONS_PASSWORD', 'SCALAWIKI_LOGIN', 'SCALAWIKI_PASSWORD'];

const SBT_COMMANDS = [
  'set Test / javaOptions ++= Seq("-Xmx1g", "-Dfile.encoding=UTF-8", "-Dui.csp=production", "-Dui.csrf=on")',
  // Play's Assets serves public/ from the classpath, which the test classpath lacks
  'set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)',
  'Test/runMain gatling.setup.UiShotsServer',
];

function sbtCommand() {
  const launcher = process.env.SBT_LAUNCHER || (fs.existsSync(WINDOWS_LAUNCHER) ? WINDOWS_LAUNCHER : null);
  if (launcher) return ['java', ['-Xmx1g', '-Dfile.encoding=UTF-8', '-jar', launcher, ...SBT_COMMANDS]];
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
    console.log(`[fixture-server] already serving ${running.baseUrl}`);
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
  console.log('[fixture-server] stopped');
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
