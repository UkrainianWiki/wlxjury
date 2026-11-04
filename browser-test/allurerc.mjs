// The Allure 3 report of the browser tests (docs/testing/practices.md §10.10): one HTML file
// (the Awesome plugin's singleFile: scripts, styles and data inlined), built from the results of
// one or more engines, then stripped of the Google Analytics tag Allure always adds, so that it
// loads nothing from another host:
//   npx allure generate ../target/browser-test/allure-results && node allure-offline.mjs
// History: a JSONL file of earlier runs' results. CI restores it from its cache and appends this
// run only on pushes to master and ukwikidev/followups (ALLURE_APPEND_HISTORY=true); a PR's run
// reads it, so its trends are against its base. `.mjs`, not `.ts`: TypeScript config needs Node 22.18.
import { defineConfig } from 'allure';

export default defineConfig({
  name: 'WLX Jury browser tests',
  output: '../target/browser-test/allure-report',
  historyPath: '../target/browser-test/allure-history/history.jsonl',
  historyLimit: 30,
  appendHistory: process.env.ALLURE_APPEND_HISTORY === 'true',
  plugins: {
    awesome: {
      options: {
        singleFile: true,
        reportName: 'WLX Jury browser tests',
        reportLanguage: 'en',
        groupBy: ['epic', 'feature', 'story'],
      },
    },
  },
});
