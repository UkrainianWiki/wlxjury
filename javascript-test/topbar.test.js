/**
 * @jest-environment jsdom
 */
"use strict";

// topbar.js: the sticky top bar's script only keeps --wlx-topbar-height (the root's
// scroll-padding-top, main.css) at the bar's height, on a page that opts in.

const topbar = require("../public/javascripts/topbar");

function setUp(bodyClass, height) {
    document.documentElement.removeAttribute("style");
    document.body.className = bodyClass;
    document.body.innerHTML = '<nav class="navbar"></nav><main></main>';
    const bar = document.querySelector("nav");
    Object.defineProperty(bar, "offsetHeight", { configurable: true, writable: true, value: height });
    return bar;
}

const prop = () => document.documentElement.style.getPropertyValue("--wlx-topbar-height");

test("finds the navbar only on a page that opts in", () => {
    const bar = setUp("wlx-topbar-sticky", 50);
    expect(topbar.find(document)).toBe(bar);
    setUp("gallery", 50);
    expect(topbar.find(document)).toBeNull();
    document.body.innerHTML = "<main></main>";
    document.body.className = "wlx-topbar-sticky";
    expect(topbar.find(document)).toBeNull();
});

test("sets --wlx-topbar-height to the bar's height", () => {
    const bar = setUp("wlx-topbar-sticky", 50);
    expect(topbar.measure(document, bar)).toBe(50);
    expect(prop()).toBe("50px");
});

test("start measures now and again on a resize or when the bar's size changes", () => {
    const bar = setUp("wlx-topbar-sticky", 50);
    const listeners = {};
    let observe;
    const win = {
        document,
        addEventListener: (type, fn) => { listeners[type] = fn; },
        ResizeObserver: function (fn) { observe = fn; this.observe = jest.fn(); },
    };
    expect(topbar.start(win)).toBe(bar);
    expect(prop()).toBe("50px");
    bar.offsetHeight = 205;
    listeners.resize();
    expect(prop()).toBe("205px");
    bar.offsetHeight = 60;
    observe();
    expect(prop()).toBe("60px");
});

test("start does nothing on a page that doesn't opt in", () => {
    setUp("gallery", 50);
    expect(topbar.start({ document, addEventListener: jest.fn() })).toBeNull();
    expect(prop()).toBe("");
});
