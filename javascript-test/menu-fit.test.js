/**
 * @jest-environment jsdom
 */
"use strict";

// menu-fit.js: an open top-bar menu in a narrow window is moved left (or right) to stay inside it,
// fitted again as it or the window changes size, and put back when it closes; a wide window and
// a menu outside the navbar are left alone.

const menuFit = require("../public/javascripts/menu-fit");

const WIDTH = 390;

/** A fake window over jsdom's document: its media query, resize listeners and ResizeObserver. */
function fakeWindow(narrow) {
    const listeners = {};
    const observed = [];
    function ResizeObserver(callback) {
        this.callback = callback;
        this.disconnected = false;
        observed.push(this);
    }
    ResizeObserver.prototype.observe = function () {};
    ResizeObserver.prototype.disconnect = function () { this.disconnected = true; };
    return {
        document,
        narrow,
        observed,
        matchMedia(query) {
            return { matches: query === menuFit.NARROW && this.narrow };
        },
        addEventListener(type, fn) { listeners[type] = fn; },
        fire(type) { listeners[type](); },
        ResizeObserver,
    };
}

/** The bar with one menu; `box` gives the menu's position as Bootstrap left it (no transform). */
function setUp(box, inBar = true, bar = { left: 0, right: WIDTH }) {
    const item =
        '<li class="nav-item dropdown"><button id="toggle" class="nav-link dropdown-toggle">Comments</button>' +
        '<ul class="dropdown-menu" id="menu"><li><a class="dropdown-item" href="#">All files</a></li></ul></li>';
    document.body.innerHTML = inBar ? `<nav class="navbar"><ul class="navbar-nav">${item}</ul></nav>` : `<ul>${item}</ul>`;
    Object.defineProperty(document.documentElement, "clientWidth", { configurable: true, value: WIDTH });
    const menu = document.getElementById("menu");
    menu.getBoundingClientRect = () => ({ left: box.left, right: box.right, top: 0, bottom: 100 });
    const nav = document.querySelector("nav");
    if (nav) nav.getBoundingClientRect = () => ({ left: bar.left, right: bar.right, top: 0, bottom: 50 });
    return { toggle: document.getElementById("toggle"), menu };
}

function open(toggle, menu) {
    menu.classList.add("show");
    toggle.dispatchEvent(new Event("shown.bs.dropdown", { bubbles: true }));
}

function close(toggle, menu) {
    menu.classList.remove("show");
    toggle.dispatchEvent(new Event("hidden.bs.dropdown", { bubbles: true }));
}

let removers = [];
function startOn(win) {
    const state = menuFit.start(win);
    removers.push(() => {
        document.removeEventListener("shown.bs.dropdown", state.onShown);
        document.removeEventListener("hidden.bs.dropdown", state.onHidden);
    });
    return state;
}
afterEach(() => {
    removers.forEach((r) => r());
    removers = [];
});

test("the shift keeps a box inside the window with the gutter, the left edge first", () => {
    expect(menuFit.shift(211, 420, WIDTH)).toBe(-38);
    expect(menuFit.shift(8, 300, WIDTH)).toBe(0);
    expect(menuFit.shift(140, 543, WIDTH)).toBe(-132); // 403 px wide: its left edge at the gutter
    expect(menuFit.shift(2, 200, WIDTH)).toBe(6);
    expect(menuFit.shift(100, 382, WIDTH)).toBe(0);
    // within from..to: a bar whose edges are inside the window
    expect(menuFit.shift(181, 390, 360, 0)).toBe(-38);
    expect(menuFit.shift(10, 200, WIDTH, 20)).toBe(18);
});

test("keeps the menu inside the bar too, on a page scrolled sideways (main.css clips the bar)", () => {
    // the page 30 px wider than the window, scrolled to its right: the bar spans -30..360
    const { toggle, menu } = setUp({ left: 181, right: 390 }, true, { left: -30, right: 360 });
    startOn(fakeWindow(true));
    open(toggle, menu);
    expect(menu.style.transform).toBe("translateX(-38px)");
});

test("moves an open menu that ends past the right edge left by as much, and back on close", () => {
    const { toggle, menu } = setUp({ left: 211, right: 420 });
    startOn(fakeWindow(true));
    open(toggle, menu);
    expect(menu.style.transform).toBe("translateX(-38px)");
    close(toggle, menu);
    expect(menu.style.transform).toBe("");
});

test("leaves a menu that fits, a wide window's and one outside the navbar alone", () => {
    let { toggle, menu } = setUp({ left: 8, right: 300 });
    startOn(fakeWindow(true));
    open(toggle, menu);
    expect(menu.style.transform).toBe("");
    removers.forEach((r) => r());
    removers = [];

    ({ toggle, menu } = setUp({ left: 211, right: 420 }));
    startOn(fakeWindow(false));
    open(toggle, menu);
    expect(menu.style.transform).toBe("");
    removers.forEach((r) => r());
    removers = [];

    ({ toggle, menu } = setUp({ left: 211, right: 420 }, false));
    startOn(fakeWindow(true));
    open(toggle, menu);
    expect(menu.style.transform).toBe("");
});

test("fits the menu again when its size changes while open (the region menu fills itself)", () => {
    const box = { left: 100, right: 300 };
    const { toggle, menu } = setUp(box);
    const win = fakeWindow(true);
    startOn(win);
    open(toggle, menu);
    expect(menu.style.transform).toBe("");
    expect(win.observed).toHaveLength(1);
    box.right = 399;
    win.observed[0].callback();
    expect(menu.style.transform).toBe("translateX(-17px)");
    close(toggle, menu);
    expect(win.observed[0].disconnected).toBe(true);
});

test("fits open menus again when the window is resized, and clears the move in a wide one", () => {
    const { toggle, menu } = setUp({ left: 211, right: 420 });
    const win = fakeWindow(true);
    startOn(win);
    open(toggle, menu);
    expect(menu.style.transform).toBe("translateX(-38px)");
    win.narrow = false;
    win.fire("resize");
    expect(menu.style.transform).toBe("");
    win.narrow = true;
    win.fire("resize");
    expect(menu.style.transform).toBe("translateX(-38px)");
});

test("without ResizeObserver or matchMedia it still works or does nothing", () => {
    const { toggle, menu } = setUp({ left: 211, right: 420 });
    const win = fakeWindow(true);
    delete win.ResizeObserver;
    startOn(win);
    open(toggle, menu);
    expect(menu.style.transform).toBe("translateX(-38px)");
    close(toggle, menu);
    removers.forEach((r) => r());
    removers = [];
    const bare = fakeWindow(true);
    delete bare.matchMedia;
    startOn(bare);
    open(toggle, menu);
    expect(menu.style.transform).toBe("");
});

test("finds a toggle's menu among its siblings, none without one", () => {
    const { toggle, menu } = setUp({ left: 0, right: 0 });
    expect(menuFit.menuOf(toggle)).toBe(menu);
    expect(menuFit.menuOf(null)).toBeNull();
    menu.remove();
    expect(menuFit.menuOf(toggle)).toBeNull();
});
