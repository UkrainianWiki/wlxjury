/**
 * @jest-environment jsdom
 */
"use strict";

// file-anchor.js: a list's page opened at #file-<pageId> (Back or Esc from the large view)
// scrolls to the file's element only when it is not wholly in view.

const anchor = require("../public/javascripts/file-anchor");

/** A window over jsdom's document: the element's box, the window's size, how it was opened. */
function fakeWindow(hash, rect, opts) {
    opts = opts || {};
    document.querySelectorAll("[data-file-anchor]").forEach((el) => {
        el.getBoundingClientRect = () => rect;
        el.scrollIntoView = jest.fn();
    });
    return {
        document,
        location: { hash },
        innerHeight: 900,
        innerWidth: 1366,
        getComputedStyle: () => ({ scrollMarginTop: opts.margin || "0px" }),
        performance: { getEntriesByType: () => [{ type: opts.type || "navigate" }] },
    };
}

beforeEach(() => {
    document.body.innerHTML = '<ul><li id="7" data-file-anchor="7">a</li><li data-file-anchor="8">b</li></ul>';
});

test("finds the fragment's file by data-file-anchor, not by a #<pageId> id", () => {
    expect(anchor.target(document, "#file-8").textContent).toBe("b");
    expect(anchor.target(document, "#7")).toBeNull();
    expect(anchor.target(document, "#file-9")).toBeNull();
    expect(anchor.target(document, "#comments")).toBeNull();
    expect(anchor.target(document, "")).toBeNull();
});

test("finds a rating view's cut divider by #cut (the round form's ratings link)", () => {
    document.body.innerHTML = '<ul><li data-file-anchor="7">a</li><li data-file-anchor="cut">Cut</li></ul>';
    expect(anchor.target(document, "#cut").textContent).toBe("Cut");
    expect(anchor.target(document, "#cuts")).toBeNull();
    expect(anchor.target(document, "#file-cut")).toBeNull();
});

test("leaves the page at its top when the file is wholly in view", () => {
    const win = fakeWindow("#file-7", { top: 120, bottom: 300, left: 10, right: 200 });
    expect(anchor.reveal(win)).toBe(false);
    expect(document.getElementById("7").scrollIntoView).not.toHaveBeenCalled();
});

test("scrolls a file below the window, or cut by its edge, to the middle", () => {
    let win = fakeWindow("#file-7", { top: 1500, bottom: 1700, left: 10, right: 200 });
    expect(anchor.reveal(win)).toBe(true);
    expect(document.getElementById("7").scrollIntoView).toHaveBeenCalledWith({ block: "center" });
    win = fakeWindow("#file-7", { top: 800, bottom: 1000, left: 10, right: 200 });
    expect(anchor.reveal(win)).toBe(true);
});

test("counts the room kept above the file (scroll-margin-top) as out of view", () => {
    const rect = { top: 20, bottom: 200, left: 10, right: 200 };
    expect(anchor.inView(fakeWindow("#file-7", rect), document.getElementById("7"))).toBe(true);
    expect(anchor.inView(fakeWindow("#file-7", rect, { margin: "56px" }), document.getElementById("7"))).toBe(false);
});

test("does nothing on a reload or Back/Forward, where the browser restores the position", () => {
    for (const type of ["reload", "back_forward"]) {
        const win = fakeWindow("#file-7", { top: 1500, bottom: 1700, left: 10, right: 200 }, { type });
        expect(anchor.reveal(win)).toBe(false);
        expect(document.getElementById("7").scrollIntoView).not.toHaveBeenCalled();
    }
});

test("a file taller than the window: in view with its top in the upper half, else scrolled to its top", () => {
    const el = () => document.getElementById("7");
    // 1200 px tall in a 900 px window, its top just below the navbar: left as it is
    let win = fakeWindow("#file-7", { top: 120, bottom: 1320, height: 1200, left: 10, right: 200 });
    expect(anchor.tall(win, el())).toBe(true);
    expect(anchor.reveal(win)).toBe(false);
    // further down: to its top, its title never above the window
    win = fakeWindow("#file-7", { top: 2000, bottom: 3200, height: 1200, left: 10, right: 200 });
    expect(anchor.reveal(win)).toBe(true);
    expect(el().scrollIntoView).toHaveBeenCalledWith({ block: "start" });
    // its top above the window (scrolled past)
    win = fakeWindow("#file-7", { top: -300, bottom: 900, height: 1200, left: 10, right: 200 });
    expect(anchor.reveal(win)).toBe(true);
    // the room kept above counts: 850 px tall with 56 px kept is taller than the window's 844
    win = fakeWindow("#file-7", { top: 100, bottom: 950, height: 850, left: 10, right: 200 }, { margin: "56px" });
    expect(anchor.tall(win, el())).toBe(true);
});

test("checks again once the page has loaded, unless the user has scrolled", () => {
    const listeners = {};
    const make = (scrollY) => {
        const win = fakeWindow("#file-7", { top: 120, bottom: 300, height: 180, left: 10, right: 200 });
        win.scrollY = scrollY;
        win.addEventListener = (type, f) => { listeners[type] = f; };
        return win;
    };
    // parsed: in view, no scroll
    let win = make(0);
    anchor.start(win);
    expect(document.getElementById("7").scrollIntoView).not.toHaveBeenCalled();
    // the images loaded and pushed the file below the window: scrolled to it
    document.getElementById("7").getBoundingClientRect = () => ({ top: 1500, bottom: 1680, height: 180, left: 10, right: 200 });
    listeners.load();
    expect(document.getElementById("7").scrollIntoView).toHaveBeenCalledWith({ block: "center" });
    // the same, but the user scrolled meanwhile: left where they are
    win = make(0);
    anchor.start(win);
    document.getElementById("7").getBoundingClientRect = () => ({ top: 1500, bottom: 1680, height: 180, left: 10, right: 200 });
    win.scrollY = 400;
    listeners.load();
    expect(document.getElementById("7").scrollIntoView).not.toHaveBeenCalled();
});

test("does nothing without a matching file", () => {
    document.body.innerHTML = "<div></div>";
    expect(anchor.reveal(fakeWindow("#file-7", null))).toBe(false);
});
