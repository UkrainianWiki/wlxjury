# Screenshots of the user guides

Made by `browser-test/tests/docs-screenshots.spec.js` (opt-in: `DOCS_SCREENSHOTS=1`, Chromium
only), from the browser tests' fixture: the dark theme (the tool's default), English, a 1280x800 viewport, placeholder
images and generated accounts. Each file is named after the guide and section that uses it.

The images are at device scale factor 2 (a 1280-pixel-wide page is 2560 pixels wide); the guides
show them at their CSS size with `width`. Every image is a Playwright page or element screenshot: no browser or operating-system window.

**Platform:** not recorded yet. The images have not been generated (the first run, by the
orchestrator, fills in this line). Text rendering depends on the operating system, so
regenerate all the images together on one platform and record here which one (operating
system, Playwright and Chromium versions) and the date.
