// The browser tests: desktop Chromium, Firefox and WebKit against the UiShotsServer fixture
// (docs/testing/practices.md §10). Each engine is a project with its own sign-in project, so
// `--project=firefox` runs Firefox's sign-ins first, then its tests; CI runs one engine per job.
import { defineConfig, devices } from '@playwright/test';

const ENGINES = {
  chromium: devices['Desktop Chrome'],
  firefox: devices['Desktop Firefox'],
  webkit: devices['Desktop Safari'],
};

const VIEWPORT = { width: 1366, height: 900 };

export default defineConfig({
  testDir: './tests',
  outputDir: '../target/browser-test/results',
  globalSetup: './lib/global-setup.js',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  // a flaky test is a bug (testing §7.1): no retries
  retries: 0,
  forbidOnly: !!process.env.CI,
  workers: process.env.CI ? 2 : 3,
  reporter: [
    ['list'],
    ['html', { outputFolder: '../target/browser-test/report', open: 'never' }],
    ['json', { outputFile: '../target/browser-test/results.json' }],
  ],
  use: {
    locale: 'en-US',
    colorScheme: 'light',
    viewport: VIEWPORT,
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: Object.entries(ENGINES).flatMap(([name, device]) => [
    {
      name: `setup-${name}`,
      testMatch: /auth\.setup\.js$/,
      use: { ...device, viewport: VIEWPORT },
    },
    {
      name,
      testMatch: /\.spec\.js$/,
      dependencies: [`setup-${name}`],
      use: { ...device, viewport: VIEWPORT },
    },
  ]),
});
