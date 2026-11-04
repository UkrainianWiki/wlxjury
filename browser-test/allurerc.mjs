// The Allure 3 report of the browser tests (docs/testing/practices.md §10.10): one HTML file
// (the allure2 plugin's singleFile: scripts, styles and data inlined), built from the results of
// one or more engines, then checked by allure-offline.mjs (no analytics, nothing from another
// host; a CSP meta added):
//   npm run report
// The allure2 plugin, not the default Awesome one (the user, 2026-11-04): Awesome hard-codes
// Google Analytics with no opt-out (upstream allure3#476 and PR #784 open); allure2 honours
// ALLURE_NO_ANALYTICS, set here and in browser.yml's report job. Recheck on every Allure update.
// History: a JSONL file of earlier runs' results. CI restores it from its cache and appends this
// run only on pushes to master and ukwikidev/followups (ALLURE_APPEND_HISTORY=true); a PR's run
// reads it, so its trends are against its base. `.mjs`, not `.ts`: TypeScript config needs Node 22.18.
import { defineConfig } from 'allure';

process.env.ALLURE_NO_ANALYTICS ??= 'true';

export default defineConfig({
  name: 'WLX Jury browser tests',
  output: '../target/browser-test/allure-report',
  historyPath: '../target/browser-test/allure-history/history.jsonl',
  historyLimit: 30,
  appendHistory: process.env.ALLURE_APPEND_HISTORY === 'true',
  plugins: {
    allure2: {
      options: {
        singleFile: true,
        reportName: 'WLX Jury browser tests',
        reportLanguage: 'en',
      },
    },
  },
});
