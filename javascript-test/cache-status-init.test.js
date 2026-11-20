/**
 * @jest-environment jsdom
 */
"use strict";

// cache-status.js starts itself on each element with a data-cache-status-url (contest_images,
// editRound), in place of the inline pollCacheStatus(url, id) calls.

const { initCacheStatuses } = require("../public/javascripts/cache-status");

afterEach(() => { delete global.fetch; });

test("polls each element's URL and writes the status into it", async () => {
    document.body.innerHTML = '<div id="round-cache-status" data-cache-status-url="/contest/1/round/5/cache-status"></div>' +
        '<div id="other"></div>';
    const urls = [];
    global.fetch = jest.fn(url => {
        urls.push(url);
        return Promise.resolve({ status: 200, json: () => Promise.resolve({ total: 10, done: 10, errors: 0, running: false }) });
    });
    expect(initCacheStatuses(document)).toBe(1);
    await new Promise(r => setTimeout(r, 0));
    expect(urls).toEqual(["/contest/1/round/5/cache-status"]);
    expect(global.fetch.mock.calls[0][1].headers["X-Requested-With"]).toBe("XMLHttpRequest");
    expect(document.getElementById("round-cache-status").textContent).toBe("10 / 10 downloaded (100%) – done");
    expect(document.getElementById("other").textContent).toBe("");
});

test("does nothing on a page without one", () => {
    document.body.innerHTML = "<div id='cache-status'></div>";
    global.fetch = jest.fn();
    expect(initCacheStatuses(document)).toBe(0);
    expect(global.fetch).not.toHaveBeenCalled();
});

test("writes a finished run's 'all cached' text from the element's own messages, as text", async () => {
    document.body.innerHTML = '<div id="cache-status" data-cache-status-url="/contest/1/images/cache/status"' +
        ' data-text-all-cached="All {0} images are &lt;b&gt;cached&lt;/b&gt; ({1})."' +
        ' data-text-nothing="No images to download ({0})." data-text-also-cached="{0} already cached"></div>';
    global.fetch = jest.fn(() => Promise.resolve({ status: 200, json: () => Promise.resolve(
        { total: 0, done: 0, errors: 0, running: false, cached: 300, startedAtMs: Date.UTC(2026, 10, 19, 12, 5) }) }));
    initCacheStatuses(document);
    await new Promise(r => setTimeout(r, 0));
    const el = document.getElementById("cache-status");
    expect(el.textContent).toMatch(/^All 300 images are <b>cached<\/b> \(.+\)\.$/);
    expect(el.querySelector("b")).toBeNull();
});

test("a wlx:refresh event fetches the status at once and polls on while it runs, one loop at a time", async () => {
    jest.useFakeTimers();
    try {
        document.body.innerHTML = '<div id="cache-status" data-cache-status-url="/contest/1/images/cache/status"></div>';
        const answers = [
            { total: 0, done: 0, errors: 0, running: false },
            { total: 0, done: 0, errors: 0, running: true },
            { total: 0, done: 0, errors: 0, running: true },
            { total: 4, done: 4, errors: 0, running: false },
        ];
        global.fetch = jest.fn(() => Promise.resolve({ status: 200, json: () => Promise.resolve(answers.shift()) }));
        initCacheStatuses(document);
        await jest.advanceTimersByTimeAsync(0);
        expect(global.fetch).toHaveBeenCalledTimes(1);
        const el = document.getElementById("cache-status");
        el.dispatchEvent(new CustomEvent("wlx:refresh"));
        await jest.advanceTimersByTimeAsync(0);
        expect(global.fetch).toHaveBeenCalledTimes(2);
        expect(el.textContent).toBe("Starting\u2026");
        // a second refresh while a poll is due: the due poll replaced, not added to
        el.dispatchEvent(new CustomEvent("wlx:refresh"));
        await jest.advanceTimersByTimeAsync(0);
        expect(global.fetch).toHaveBeenCalledTimes(3);
        await jest.advanceTimersByTimeAsync(3000);
        expect(global.fetch).toHaveBeenCalledTimes(4);
        expect(el.textContent).toBe("4 / 4 downloaded (100%) \u2013 done");
        await jest.advanceTimersByTimeAsync(10000);
        expect(global.fetch).toHaveBeenCalledTimes(4);
    } finally {
        jest.useRealTimers();
    }
});
