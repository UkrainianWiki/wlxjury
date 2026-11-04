// Steps the tests share, by role and accessible name (testing §6.1).
import { expect } from './fixtures.js';

/** Signs in through the login form; the page ends on the account's start page. */
export async function signIn(page, account) {
  await page.goto('/auth');
  await page.getByLabel('Your login').fill(account.login);
  await page.getByLabel('Your password').fill(account.password);
  await expect(page.getByRole('button', { name: 'Sign In' })).toBeVisible();
  // Enter in the password field, as a user signs in: WebKit on Windows, with other engines
  // running, at times never reports the button "stable" for a click
  await page.getByLabel('Your password').press('Enter');
  await expect(page).not.toHaveURL(/\/auth(\?|$)/);
}

/** U22 (UI practices §9, open): in Firefox, leaving a gallery by a form POST at times raises this
  * page error on the page being left. Accepted only in the tests that leave a gallery so, and
  * only in Firefox. */
export function allowFirefoxLeavingGallery(diagnostics, browserName) {
  if (browserName === 'firefox') {
    diagnostics.allow(/^page error: TypeError: can't access property "hasAttribute", document\.body is null/);
  }
}

/** Exactly one element matches (testing §6.2), then returns it. */
export async function one(locator) {
  await expect(locator).toHaveCount(1);
  return locator;
}

/** The juror's large view of a file in a round. */
export const largePath = (round, juror, pageId) => `/large/round/${round}/user/${juror}/pageid/${pageId}`;
export const galleryPath = (round, juror, page = 1) => `/gallery/round/${round}/user/${juror}/page/${page}`;
