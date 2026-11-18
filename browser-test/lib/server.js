// The fixture's accounts and ids, from target/ui-shots/server.json (gatling.setup.UiShotsServer),
// and which of them each engine uses, so that the three engines can share one server: each votes
// as its own juror and signs in and out as another.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const SERVER_JSON = path.join(ROOT, 'target', 'ui-shots', 'server.json');
export const OUT = path.join(ROOT, 'target', 'browser-test');

export function readServer() {
  if (!fs.existsSync(SERVER_JSON)) {
    throw new Error(`${SERVER_JSON} is missing: start the fixture first (node fixture-server.mjs start)`);
  }
  return JSON.parse(fs.readFileSync(SERVER_JSON, 'utf8'));
}

/** Whether DOCS_SCREENSHOTS asks for the user guides' screenshots: 1, true, yes or on; unset,
  * empty, 0, false, no or off is off; any other value is an error, not a guess. */
export function docsScreenshotsOn(env = process.env) {
  const value = (env.DOCS_SCREENSHOTS ?? '').trim().toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(value)) return true;
  if (['', '0', 'false', 'no', 'off'].includes(value)) return false;
  throw new Error(`DOCS_SCREENSHOTS=${env.DOCS_SCREENSHOTS}: use 1 (on) or 0 (off)`);
}

const ENGINE_INDEX = { chromium: 0, firefox: 1, webkit: 2 };

/** The fixture's ids and the accounts of `browserName`'s run. */
export function fixtureFor(browserName) {
  const s = readServer();
  const i = ENGINE_INDEX[browserName];
  if (i === undefined) throw new Error(`unknown engine ${browserName}`);
  if (!s.jurors || s.jurors.length < 7) {
    throw new Error('server.json lists fewer than 7 jurors with files in both rounds: restart the fixture');
  }
  return {
    baseUrl: s.baseUrl,
    contestId: s.contestId,
    roundBinaryId: s.roundBinaryId,
    roundRatingId: s.roundRatingId,
    accounts: {
      juror: s.jurors[i], // votes
      admin: { ...s.organizer, id: null }, // the fixture's organizer is the contest's admin
      organizer: s.organizerRole,
      root: s.root,
    },
    // signs in and out, and switches the theme, in its own session
    freshJuror: s.jurors[3 + i],
    // a juror whose votes the other jurors must not see
    otherJuror: s.jurors[6],
    // a binary round's file none of the listed jurors has, and its juror (id): where a test may
    // write a comment without giving the jurors above one (UiShotsServer)
    commentFile: s.commentFile,
    // an account in no contest, this engine's, for the users page's search (users.spec.js)
    searchTarget: (s.searchTargets || [])[i],
    // this engine's pair for the Edit user form's "wiki account taken" panel (users.spec.js): a
    // member with no role and no wiki account, and an account in no contest with the wiki account
    wikiTaken: (s.wikiTaken || [])[i],
    // this engine's two accounts in no contest with one wiki account nobody verified, for the add
    // row's confirmation (users.spec.js, A-3)
    wikiConfirm: (s.wikiConfirm || [])[i],
    // "Add people from another of your contests" (users.spec.js): its source and target contests,
    // their admin, an account known only by an unverified wiki account, and this engine's three
    // jurors in the source; null in the guides' screenshot mode
    addFrom: s.addFrom ? { ...s.addFrom, targets: (s.addFrom.targets || [])[i] || [] } : null,
    // this engine's groups of possible duplicates (their shared wiki accounts), for the
    // duplicates report (duplicates.spec.js); none in the guides' screenshot mode
    duplicateGroups: (s.duplicateGroups || [])[i] || [],
    // this engine's pairs for retiring an account from that panel as root (users.spec.js, A-4), in
    // a contest of their own: an empty member without a role, with a wiki account, and an account
    // in no contest with another one; none in the guides' screenshot mode
    retireTargets: (s.retireTargets || [])[i] || [],
    // this engine's pairs of accounts to join as root (duplicates.spec.js, J-2), in a contest of
    // their own: two members without a role sharing a wiki account written two ways; none in the
    // guides' screenshot mode
    joinPairs: (s.joinPairs || [])[i] || [],
    // the contest health page's Nepal case (health.spec.js): an old WLE 2020 contest of its own,
    // reused for WLM 2026 in Nepal; 0 in the guides' screenshot mode
    healthContestId: s.healthContestId || 0,
  };
}

export const ROLES = ['juror', 'organizer', 'admin', 'root'];

export function authFile(browserName, role) {
  return path.join(OUT, 'auth', `${browserName}-${role}.json`);
}
