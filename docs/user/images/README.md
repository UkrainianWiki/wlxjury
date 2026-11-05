# Screenshots of the user guides

Made by `browser-test/tests/docs-screenshots.spec.js` (opt-in: `DOCS_SCREENSHOTS=1`, Chromium
only), from the browser tests' fixture: the dark theme (the tool's default), English, a 1280x800
viewport at device scale factor 2, placeholder images and generated accounts. Each file is named
after the guide and section that uses it; the guides show them at their CSS size (`width`).

Every image is a Playwright page or element screenshot: no browser or operating-system window.
The fixture's files carry real Commons file names and authors, so the spec replaces them with
numbered examples before each shot, and the link to share shows a neutral host.

**Platform of the current set:** Windows 11 Pro (10.0.26200), Playwright 1.64.1 with its bundled
Chromium (156), the app's own font stack, 2026-11-05. Text rendering depends on the operating
system, so regenerate all the images together on one platform and update this line.

**Size:** 19 images, about 2.0 MB in all; the largest, `organizer-round-statistics.png`, is
about 290 KB, and every image is under 400 KB.
