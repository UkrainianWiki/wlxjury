"use strict";

const { formatCacheStatus, pollCacheStatus } = require("../public/javascripts/cache-status");

// ---------------------------------------------------------------------------
// formatCacheStatus – pure logic, no DOM needed
// ---------------------------------------------------------------------------

describe("formatCacheStatus", () => {

    test("returns null when total is 0 and not running", () => {
        expect(formatCacheStatus({ total: 0, done: 0, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 }))
            .toBeNull();
    });

    test("returns 'Starting…' when total is 0 and running", () => {
        expect(formatCacheStatus({ total: 0, done: 0, errors: 0, running: true, ratePerSec: 0, etaSeconds: 0 }))
            .toBe("Starting\u2026");
    });

    test("shows done/total/pct and ' – done' when not running", () => {
        const result = formatCacheStatus({
            total: 100, done: 100, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).toBe("100 / 100 downloaded (100%) \u2013 done");
    });

    test("shows ' – running…' suffix when running", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: true, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).toBe("50 / 100 downloaded (50%) \u2013 running\u2026");
    });

    test("rounds percentage correctly", () => {
        const result = formatCacheStatus({
            total: 3, done: 1, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).toContain("33%");
    });

    test("includes error count when errors > 0", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 3, running: false, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).toContain(", 3 errors");
    });

    test("omits error string when errors is 0", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).not.toContain("errors");
    });

    test("shows rate when ratePerSec > 0", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: true, ratePerSec: 4.19, etaSeconds: 0
        });
        expect(result).toContain("\u2013 4.2 img/s");
    });

    test("omits rate when ratePerSec is 0", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: true, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).not.toContain("img/s");
    });

    test("shows ETA in seconds when etaSeconds < 60 and running", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: true, ratePerSec: 1, etaSeconds: 45
        });
        expect(result).toContain("\u2013 ETA 45s");
    });

    test("shows ETA in minutes and seconds when etaSeconds >= 60 and running", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: true, ratePerSec: 1, etaSeconds: 125
        });
        expect(result).toContain("\u2013 ETA 2m 5s");
    });

    test("omits ETA when not running", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: false, ratePerSec: 1, etaSeconds: 45
        });
        expect(result).not.toContain("ETA");
    });

    test("omits ETA when etaSeconds is 0 even if running", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 0, running: true, ratePerSec: 1, etaSeconds: 0
        });
        expect(result).not.toContain("ETA");
    });

    test("full in-progress status with rate and ETA under 60s", () => {
        const result = formatCacheStatus({
            total: 1000, done: 500, errors: 2, running: true, ratePerSec: 5.5, etaSeconds: 55
        });
        expect(result).toBe(
            "500 / 1000 downloaded (50%, 2 errors)" +
            " \u2013 5.5 img/s" +
            " \u2013 ETA 55s" +
            " \u2013 running\u2026"
        );
    });

    test("full in-progress status with rate and ETA over 60s", () => {
        const result = formatCacheStatus({
            total: 35000, done: 12450, errors: 0, running: true, ratePerSec: 4.19, etaSeconds: 532
        });
        expect(result).toBe(
            "12450 / 35000 downloaded (36%)" +
            " \u2013 4.2 img/s" +
            " \u2013 ETA 8m 52s" +
            " \u2013 running\u2026"
        );
    });
});

// ---------------------------------------------------------------------------
// pollCacheStatus – uses jest fake timers + fetch mock
// ---------------------------------------------------------------------------

describe("pollCacheStatus", () => {

    let statusEl;

    beforeEach(() => {
        jest.useFakeTimers();

        // Minimal DOM stub
        statusEl = { innerHTML: "" };
        global.document = {
            getElementById: jest.fn((id) => id === "cache-status" ? statusEl : null)
        };
    });

    afterEach(() => {
        delete global.fetch;
        jest.useRealTimers();
    });

    // Flush all pending microtasks (Promise chain has 3 hops).
    async function flushPromises() {
        for (var i = 0; i < 10; i++) { await Promise.resolve(); }
    }

    function mockFetch(payload) {
        global.fetch = jest.fn(() =>
            Promise.resolve({ json: () => Promise.resolve(payload) })
        );
    }

    test("calls fetch with the supplied URL", async () => {
        mockFetch({ total: 0, done: 0, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/42");
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledWith("/status/42");
    });

    test("updates #cache-status innerHTML", async () => {
        mockFetch({ total: 100, done: 60, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/1");
        await flushPromises();
        expect(statusEl.innerHTML).toBe("60 / 100 downloaded (60%) \u2013 done");
    });

    test("schedules a follow-up poll when running", async () => {
        mockFetch({ total: 100, done: 50, errors: 0, running: true, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/1");
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledTimes(1);
        jest.advanceTimersByTime(3000);
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledTimes(2);
    });

    test("does not schedule a follow-up poll when not running", async () => {
        mockFetch({ total: 100, done: 100, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/1");
        await flushPromises();
        jest.advanceTimersByTime(10000);
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    test("retries after 5 s on fetch error", async () => {
        global.fetch = jest.fn(() => Promise.reject(new Error("network")));
        pollCacheStatus("/status/1");
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledTimes(1);
        jest.advanceTimersByTime(5000);
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledTimes(2);
    });

    test("uses custom elementId when provided", async () => {
        const customEl = { innerHTML: "" };
        global.document = {
            getElementById: jest.fn((id) => id === "round-cache-status" ? customEl : null)
        };
        mockFetch({ total: 100, done: 60, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/1", "round-cache-status");
        await flushPromises();
        expect(customEl.innerHTML).toBe("60 / 100 downloaded (60%) \u2013 done");
    });
});
