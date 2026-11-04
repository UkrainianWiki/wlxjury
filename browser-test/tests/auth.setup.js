// Signs in once per role through the login form and saves the session for the engine's tests
// (lib/server.js authFile), instead of signing in per test. One setup per role, each tagged with
// that role's sign-in story (written out, so scripts/story-coverage.py reads the tags).
import fs from 'node:fs';
import path from 'node:path';
import { test as setup } from '../lib/fixtures.js';
import { signIn } from '../lib/actions.js';
import { authFile, ROLES } from '../lib/server.js';

// every role of lib/server.js has its setup below
if (ROLES.join() !== 'juror,organizer,admin,root') throw new Error(`a setup per role: ${ROLES.join()}`);

async function signInAs(role, page, fixture, browserName) {
  await signIn(page, fixture.accounts[role]);
  const file = authFile(browserName, role);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  await page.context().storageState({ path: file });
}

setup('sign in as juror', { tag: '@J-02' }, async ({ page, fixture, browserName }) => signInAs('juror', page, fixture, browserName));
setup('sign in as organizer', { tag: '@O-06' }, async ({ page, fixture, browserName }) => signInAs('organizer', page, fixture, browserName));
setup('sign in as admin', { tag: '@A-18' }, async ({ page, fixture, browserName }) => signInAs('admin', page, fixture, browserName));
setup('sign in as root', { tag: '@R-04' }, async ({ page, fixture, browserName }) => signInAs('root', page, fixture, browserName));
