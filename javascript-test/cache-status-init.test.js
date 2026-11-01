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
