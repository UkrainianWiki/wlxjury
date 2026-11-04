// The story catalogue (docs/testing/stories.md, testing practices §9.4), read once per worker: a
// test's tags ('@J-21') name its stories, and the storyLabels fixture (lib/fixtures.js) turns them
// into the Allure report's epic (the role), feature (the guide section, else the role) and story
// (the ID and the catalogue's sentence). scripts/story-coverage.py reads the same table.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const CATALOGUE = fileURLToPath(new URL('../../docs/testing/stories.md', import.meta.url));

export const ROLES = { J: 'Juror', O: 'Organizer', A: 'Admin', R: 'Root', S: 'System and security' };

// a table row: | ID | Story | Guide section | Notes |
const ROW = /^\|\s*([JOARS]-\d{2})\s*\|(.*)\|\s*$/;
// what looks like a story ID, valid or not (the script fails on a malformed one)
const STORY_TAG = /^@[A-Z]-\d+$/;

let stories;

/** id -> { text, section (the guide link's text, or null), retired } */
export function catalogue() {
  if (stories) return stories;
  stories = {};
  for (const line of fs.readFileSync(CATALOGUE, 'utf8').split(/\r?\n/)) {
    const m = ROW.exec(line);
    if (!m) continue;
    const [text, guide, notes] = m[2].split(' | ').map((c) => c.trim());
    stories[m[1]] = {
      text,
      section: /\[([^\]]+)\]\(/.exec(guide ?? '')?.[1] ?? null,
      retired: (notes ?? '').includes('(retired)'),
    };
  }
  return stories;
}

/** The story IDs of a test's tags ('@J-21' -> 'J-21'); other tags are left out. Throws on a
  * story tag the catalogue lacks or retired, so the test fails as the script would. */
export function storyIds(tags) {
  const known = catalogue();
  const ids = [...new Set(tags.filter((t) => STORY_TAG.test(t)).map((t) => t.slice(1)))];
  const bad = ids.filter((id) => !known[id] || known[id].retired);
  if (bad.length) throw new Error(`story tags not in docs/testing/stories.md, or retired there: ${bad.join(', ')}`);
  return ids;
}
