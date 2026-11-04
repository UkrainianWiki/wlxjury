// Before any project: the fixture answers, with the accounts the tests need.
import { readServer } from './server.js';

export default async function globalSetup() {
  const s = readServer();
  let ok = false;
  try {
    ok = (await fetch(new URL('/health/ready', s.baseUrl), { signal: AbortSignal.timeout(10_000) })).ok;
  } catch {
    ok = false;
  }
  if (!ok) throw new Error(`the fixture at ${s.baseUrl} doesn't answer: start it (node fixture-server.mjs start)`);
  if (!s.organizerRole || !s.jurors) {
    throw new Error('server.json has no organizerRole or jurors: restart the fixture with this checkout');
  }
}
