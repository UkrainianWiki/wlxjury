/**
 * @jest-environment jsdom
 */
"use strict";

// The large view's next file's photo loaded by script (large-next-image.js): sizes set before
// srcset and src, nothing without #wlx-next-image, nothing with Save-Data.

const { prefetch, saveData } = require("../public/javascripts/large-next-image.js");

/** An Image that records the order its properties are set in. */
function recordingImage() {
  const log = [];
  class Recording {
    set sizes(v) { log.push(["sizes", v]); }
    set srcset(v) { log.push(["srcset", v]); }
    set src(v) { log.push(["src", v]); }
    set decoding(v) { log.push(["decoding", v]); }
  }
  Recording.log = log;
  return Recording;
}

function page(html) {
  document.body.innerHTML = html;
  return document;
}

const next = '<span id="wlx-next-image" hidden data-src="https://upload.wikimedia.org/a.jpg" ' +
  'data-srcset="https://upload.wikimedia.org/a.jpg 1280w, https://upload.wikimedia.org/b.jpg 2560w" ' +
  'data-sizes="1280px"></span>';

describe("prefetch", () => {

  test("sets sizes before srcset, then src, from the element's data", () => {
    const Img = recordingImage();
    const image = prefetch(page(next), { connection: { saveData: false } }, Img);
    expect(image).not.toBeNull();
    expect(Img.log.filter(([k]) => k !== "decoding")).toEqual([
      ["sizes", "1280px"],
      ["srcset", "https://upload.wikimedia.org/a.jpg 1280w, https://upload.wikimedia.org/b.jpg 2560w"],
      ["src", "https://upload.wikimedia.org/a.jpg"],
    ]);
  });

  test("does nothing without the element", () => {
    const Img = recordingImage();
    expect(prefetch(page("<p>last file</p>"), {}, Img)).toBeNull();
    expect(Img.log).toEqual([]);
  });

  test("respects Save-Data", () => {
    const Img = recordingImage();
    expect(prefetch(page(next), { connection: { saveData: true } }, Img)).toBeNull();
    expect(Img.log).toEqual([]);
  });

  test("loads where the browser has no connection information", () => {
    const Img = recordingImage();
    expect(prefetch(page(next), {}, Img)).not.toBeNull();
    expect(saveData(undefined)).toBe(false);
  });
});
