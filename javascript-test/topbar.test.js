/**
 * @jest-environment jsdom
 */
"use strict";

// topbar.js: the reappearing top bar (a trial) hides while the user scrolls down and comes back
// when they scroll up; it stays shown near the top, under an open menu, for a keyboard user's
// focus and after file-anchor.js scrolled to a file; reduced motion is main.css's.

const fs = require("fs");
const path = require("path");
const topbar = require("../public/javascripts/topbar");

const BAR_HEIGHT = 50;

/** A window over jsdom's document whose scrollY the test sets. */
function fakeWindow() {
    return { document, scrollY: 0 };
}

function setUp(bodyClass) {
    document.body.className = bodyClass === undefined ? "wlx-topbar-reappears" : bodyClass;
    document.body.innerHTML =
        '<nav class="navbar"><ul class="navbar-nav"><li class="dropdown">' +
        '<button id="toggle" class="nav-link dropdown-toggle">Region</button>' +
        '<ul class="dropdown-menu"><li><a class="dropdown-item" href="#">A</a></li></ul></li></ul></nav>' +
        '<main><button id="outside">x</button></main>';
    const bar = document.querySelector("nav");
    Object.defineProperty(bar, "offsetHeight", { configurable: true, value: BAR_HEIGHT });
    return bar;
}

/** Scrolls the fake window to y and runs the bar's handler. */
function scrollTo(win, state, y) {
    win.scrollY = y;
    state.onScroll();
}

let win, bar, state;
beforeEach(() => {
    bar = setUp();
    win = fakeWindow();
    state = topbar.create(win, bar);
});

test("finds the navbar only on a page that opts in", () => {
    expect(topbar.find(document)).toBe(bar);
    setUp("gallery");
    expect(topbar.find(document)).toBeNull();
    expect(topbar.start(fakeWindow())).toBeNull();
});

test("hides on a downward scroll past its height and comes back on a scroll up", () => {
    scrollTo(win, state, 400);
    expect(state.hidden()).toBe(true);
    expect(bar.classList.contains(topbar.HIDDEN)).toBe(true);
    scrollTo(win, state, 800);
    expect(state.hidden()).toBe(true);
    scrollTo(win, state, 770);
    expect(state.hidden()).toBe(false);
    scrollTo(win, state, 900);
    expect(state.hidden()).toBe(true);
});

test("stays shown near the page's top, within its own height", () => {
    scrollTo(win, state, 30);
    expect(state.hidden()).toBe(false);
    scrollTo(win, state, BAR_HEIGHT);
    expect(state.hidden()).toBe(false);
    scrollTo(win, state, 400);
    expect(state.hidden()).toBe(true);
    scrollTo(win, state, 0);
    expect(state.hidden()).toBe(false);
});

test("ignores movements smaller than the threshold, but adds them up", () => {
    scrollTo(win, state, 400);
    expect(state.hidden()).toBe(true);
    const step = topbar.THRESHOLD - 1;
    scrollTo(win, state, 400 - step);
    expect(state.hidden()).toBe(true);
    // two small steps up make one past the threshold
    scrollTo(win, state, 400 - 2 * step);
    expect(state.hidden()).toBe(false);
    scrollTo(win, state, 400 - 2 * step + step);
    expect(state.hidden()).toBe(false);
});

test("stays shown while one of its menus is open, also when the page scrolls under it", () => {
    bar.querySelector(".dropdown-menu").classList.add("show");
    scrollTo(win, state, 400);
    scrollTo(win, state, 900);
    expect(state.hidden()).toBe(false);
    bar.querySelector(".dropdown-menu").classList.remove("show");
    scrollTo(win, state, 1200);
    expect(state.hidden()).toBe(true);
    // opening a menu shows the bar (Bootstrap's show.bs.dropdown)
    state.onMenuShow();
    expect(state.hidden()).toBe(false);
});

test("comes back when the focus moves into it, and stays while a keyboard user's focus is there", () => {
    scrollTo(win, state, 400);
    expect(state.hidden()).toBe(true);
    state.onKey({ key: "Tab" });
    document.getElementById("toggle").focus();
    state.onFocusIn();
    expect(state.hidden()).toBe(false);
    scrollTo(win, state, 900);
    expect(state.hidden()).toBe(false);
    // the focus leaves the bar: it hides again on the next scroll down
    document.getElementById("outside").focus();
    scrollTo(win, state, 1300);
    expect(state.hidden()).toBe(true);
});

test("a click leaves the focus on a toggle: that doesn't keep the bar shown", () => {
    state.onPointer();
    document.getElementById("toggle").focus();
    scrollTo(win, state, 400);
    expect(state.hidden()).toBe(true);
});

test("after file-anchor.js scrolled to a file, stays shown until the user's own input", () => {
    state.onAnchor();
    scrollTo(win, state, 600);
    scrollTo(win, state, 1000);
    expect(state.hidden()).toBe(false);
    // the user's wheel: from here, a scroll down hides it
    state.onWheel();
    scrollTo(win, state, 1005);
    expect(state.hidden()).toBe(false);
    scrollTo(win, state, 1100);
    expect(state.hidden()).toBe(true);
    // a tap or a key ends the hold as well
    for (const input of [state.onPointer, () => state.onKey({ key: "Escape" })]) {
        scrollTo(win, state, 0);
        state.onAnchor();
        scrollTo(win, state, 500);
        expect(state.hidden()).toBe(false);
        input();
        document.getElementById("outside").focus();
        scrollTo(win, state, 700);
        expect(state.hidden()).toBe(true);
    }
});

test("only Tab makes the focus a keyboard user's: another key on a toggle doesn't keep the bar", () => {
    state.onKey({ key: "Enter" });
    document.getElementById("toggle").focus();
    scrollTo(win, state, 400);
    expect(state.hidden()).toBe(true);
    scrollTo(win, state, 300);
    state.onKey({ key: "Tab" });
    scrollTo(win, state, 800);
    expect(state.hidden()).toBe(false);
});

test("reads the bar's height when measuring, not on each scroll", () => {
    // taller now, but not measured: the old height still decides what is near the top
    Object.defineProperty(bar, "offsetHeight", { configurable: true, value: 300 });
    scrollTo(win, state, 200);
    expect(state.hidden()).toBe(true);
    state.measure();
    scrollTo(win, state, 0);
    scrollTo(win, state, 250);
    expect(state.hidden()).toBe(false);
});

test("a page from the back/forward cache: no hold, its position taken as it is, measured again", () => {
    state.onAnchor();
    win.scrollY = 2000;
    Object.defineProperty(bar, "offsetHeight", { configurable: true, value: 70 });
    state.onPageShow({ persisted: false });
    scrollTo(win, state, 2100);
    expect(state.hidden()).toBe(false); // still held: not a restored page
    state.onPageShow({ persisted: true });
    expect(document.documentElement.style.getPropertyValue("--wlx-topbar-height")).toBe("70px");
    scrollTo(win, state, 2105);
    expect(state.hidden()).toBe(false); // below the threshold from where it was restored
    scrollTo(win, state, 2200);
    expect(state.hidden()).toBe(true);
});

describe("a keyboard user's focus under the bar's place is moved below it (WebKit ignores scroll-padding)", () => {
    let el, frames;
    /** The element at `docTop` in the page (moving with it), or fixed at `fixedTop` in the window;
      * scrollTo moves the page; requestAnimationFrame queues its callbacks for `flush`. */
    function page(docTop, fixedTop) {
        el = document.getElementById("outside");
        el.focus();
        el.getBoundingClientRect = () => ({ top: fixedTop !== undefined ? fixedTop : docTop - win.scrollY });
        win.scrollTo = jest.fn((o) => { win.scrollY = o.top; });
        frames = [];
        win.requestAnimationFrame = (f) => frames.push(f);
    }
    const flush = () => frames.splice(0).forEach((f) => f());

    test("after the next frame, once the browser scrolled it to the window's top edge", () => {
        page(1000);
        win.scrollY = 600;
        state.onKey({ key: "Tab" });
        state.onPageFocus({ target: el });
        // not before the frame: the browser's own scroll to it comes first
        expect(win.scrollTo).not.toHaveBeenCalled();
        win.scrollY = 1000; // the browser's focus scroll: the element at the top edge
        flush();
        expect(win.scrollTo).toHaveBeenCalledWith({ top: 1000 - BAR_HEIGHT, behavior: "instant" });
        expect(el.getBoundingClientRect().top).toBe(BAR_HEIGHT);
    });

    test("a click's focus is left where it is", () => {
        page(1000);
        win.scrollY = 1000;
        state.onPointer();
        state.onPageFocus({ target: el });
        flush();
        expect(win.scrollTo).not.toHaveBeenCalled();
    });

    test("a later scroll of the browser's that puts it under the bar again is corrected", () => {
        page(1000);
        win.scrollY = 990;
        state.onKey({ key: "Tab" });
        state.onPageFocus({ target: el });
        flush(); // 10 px under the bar's place: corrected
        expect(win.scrollTo).toHaveBeenCalledTimes(1);
        win.scrollY = 1000; // the browser's (animated) focus scroll goes on
        state.onScroll();
        expect(win.scrollTo).toHaveBeenCalledTimes(2);
        expect(el.getBoundingClientRect().top).toBe(BAR_HEIGHT);
    });

    test("ends on the first scroll that leaves it below the bar: a scrollbar drag is the user's", () => {
        page(1000);
        win.scrollY = 1000;
        state.onKey({ key: "Tab" });
        state.onPageFocus({ target: el });
        flush();
        win.scrollTo.mockClear();
        state.onScroll(); // its own correction's scroll: the element below the bar
        win.scrollY = 1200; // the user drags the scrollbar down: the element under the bar
        state.onScroll();
        expect(win.scrollTo).not.toHaveBeenCalled();
    });

    test("stops when a correction didn't move the element (fixed or sticky in the window)", () => {
        page(0, 5);
        win.scrollY = 600;
        state.onKey({ key: "Tab" });
        state.onPageFocus({ target: el });
        flush();
        expect(win.scrollTo).toHaveBeenCalledTimes(1);
        win.scrollY = 700;
        state.onScroll();
        expect(win.scrollTo).toHaveBeenCalledTimes(1);
    });

    test("ends with the user's next key, or after FOCUS_MS", () => {
        page(1000);
        win.scrollY = 600;
        state.onKey({ key: "Tab" });
        state.onPageFocus({ target: el });
        state.onKey({ key: "ArrowDown" });
        win.scrollY = 1000;
        flush();
        state.onScroll();
        expect(win.scrollTo).not.toHaveBeenCalled();
        state.onKey({ key: "Tab" });
        const now = Date.now();
        const spy = jest.spyOn(Date, "now").mockReturnValue(now);
        state.onPageFocus({ target: el });
        spy.mockReturnValue(now + topbar.FOCUS_MS + 1);
        flush();
        expect(win.scrollTo).not.toHaveBeenCalled();
        spy.mockRestore();
    });

    test("nothing for the bar's own items, a focus gone elsewhere, or at the page's top", () => {
        page(1000);
        state.onKey({ key: "Tab" });
        win.scrollY = 1000;
        state.onPageFocus({ target: document.getElementById("toggle") });
        flush();
        state.onPageFocus({ target: el });
        document.getElementById("toggle").focus();
        flush();
        page(20);
        win.scrollY = 0;
        state.onPageFocus({ target: el });
        flush();
        expect(win.scrollTo).not.toHaveBeenCalled();
    });
});

test("keeps --wlx-topbar-height on the root element up to date", () => {
    state.measure();
    expect(document.documentElement.style.getPropertyValue("--wlx-topbar-height")).toBe(BAR_HEIGHT + "px");
    Object.defineProperty(bar, "offsetHeight", { configurable: true, value: 120 });
    state.measure();
    expect(document.documentElement.style.getPropertyValue("--wlx-topbar-height")).toBe("120px");
});

test("start listens to the window's scroll and to file-anchor.js's event", () => {
    const listeners = {};
    const w = { document, scrollY: 0, addEventListener: (type, f) => { listeners[type] = f; } };
    const s = topbar.start(w);
    expect(s).not.toBeNull();
    w.scrollY = 400;
    listeners.scroll();
    expect(bar.classList.contains(topbar.HIDDEN)).toBe(true);
    document.dispatchEvent(new CustomEvent("wlx:file-anchor"));
    expect(bar.classList.contains(topbar.HIDDEN)).toBe(false);
    // focus moving into the bar (a keyboard user's Tab)
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    w.scrollY = 1000;
    listeners.scroll();
    expect(bar.classList.contains(topbar.HIDDEN)).toBe(true);
    document.getElementById("toggle").focus();
    expect(bar.classList.contains(topbar.HIDDEN)).toBe(false);
});

test("main.css: sticky, hidden by a transform, no animation for reduced motion, scroll padding", () => {
    const css = fs.readFileSync(path.join(__dirname, "../public/stylesheets/main.css"), "utf8");
    expect(css).toMatch(/html \{ scroll-padding-top: var\(--wlx-topbar-height, 0px\); \}/);
    expect(css).toMatch(/body\.wlx-topbar-reappears > \.navbar \{[^}]*position: sticky;[^}]*top: 0;[^}]*transition: transform/);
    expect(css).toMatch(/body\.wlx-topbar-reappears > \.navbar\.wlx-topbar-hidden \{ transform: translateY\(-100%\); \}/);
    expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*body\.wlx-topbar-reappears > \.navbar \{ transition: none; \}/);
});
