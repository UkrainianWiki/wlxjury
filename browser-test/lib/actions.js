// Steps the tests share, by role and accessible name (testing §6.1).
import { expect } from './fixtures.js';

/** Signs in through the login form; the page ends on the account's start page. */
export async function signIn(page, account) {
  await page.goto('/auth');
  await page.getByLabel('Your login').fill(account.login);
  await page.getByLabel('Your password').fill(account.password);
  await page.getByRole('button', { name: 'Sign In' }).click();
  await expect(page).not.toHaveURL(/\/auth(\?|$)/);
}

/** Exactly one element matches (testing §6.2), then returns it. */
export async function one(locator) {
  await expect(locator).toHaveCount(1);
  return locator;
}

/** The juror's large view of a file in a round. */
export const largePath = (round, juror, pageId) => `/large/round/${round}/user/${juror}/pageid/${pageId}`;
export const galleryPath = (round, juror, page = 1) => `/gallery/round/${round}/user/${juror}/page/${page}`;
