// Signs in once per role through the login form and saves the session for the engine's tests
// (lib/server.js authFile), instead of signing in per test.
import fs from 'node:fs';
import path from 'node:path';
import { test as setup } from '../lib/fixtures.js';
import { signIn } from '../lib/actions.js';
import { authFile, ROLES } from '../lib/server.js';

for (const role of ROLES) {
  setup(`sign in as ${role}`, async ({ page, fixture, browserName }) => {
    await signIn(page, fixture.accounts[role]);
    const file = authFile(browserName, role);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await page.context().storageState({ path: file });
  });
}
