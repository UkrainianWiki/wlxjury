"use strict";

const { formatCacheStatus, formatCacheTime, cacheStatusTexts, pollCacheStatus } = require("../public/javascripts/cache-status");

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

    test("includes files renamed and deleted on Commons before the errors", () => {
        const result = formatCacheStatus({
            total: 100, done: 50, errors: 3, renamed: 2, deleted: 5, running: false, ratePerSec: 0, etaSeconds: 0
        });
        expect(result).toBe("50 / 100 downloaded (50%, 2 renamed on Commons, 5 deleted on Commons, 3 errors) – done");
    });

    test("omits renamed and deleted when they are 0 or absent", () => {
        expect(formatCacheStatus({
            total: 10, done: 10, errors: 0, renamed: 0, deleted: 0, running: false, ratePerSec: 0, etaSeconds: 0
        })).toBe("10 / 10 downloaded (100%) – done");
        expect(formatCacheStatus({
            total: 10, done: 10, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0
        })).toBe("10 / 10 downloaded (100%) – done");
    });

    test("says a download stopped by an error failed, not done", () => {
        expect(formatCacheStatus({ total: 0, done: 0, errors: 0, running: false, failed: true, ratePerSec: 0, etaSeconds: 0 }))
            .toBe("Download failed");
        expect(formatCacheStatus({ total: 10, done: 4, errors: 0, running: false, failed: true, ratePerSec: 0, etaSeconds: 0 }))
            .toBe("4 / 10 downloaded (40%) \u2013 failed");
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
// formatCacheStatus – a finished run with nothing to download, or some cached before
// ---------------------------------------------------------------------------

describe("formatCacheStatus after a finished run", () => {

    // the page's texts, as contest_images and editRound render them (data-text-*)
    const texts = {
        allCached: "All {0} images are already cached (checked {1}).",
        nothing: "No images to download (checked {0}).",
        alsoCached: "{0} already cached",
        lang: "en"
    };
    const startedAtMs = Date.UTC(2026, 10, 19, 12, 5);
    const when = formatCacheTime(startedAtMs, "en");
    const finished = { done: 0, total: 0, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0, startedAtMs };

    test("says all the images were already cached, with when it checked", () => {
        expect(formatCacheStatus({ ...finished, cached: 300 }, texts))
            .toBe("All 300 images are already cached (checked " + when + ").");
    });

    test("says there was nothing to download when the run had no images", () => {
        expect(formatCacheStatus({ ...finished, cached: 0 }, texts))
            .toBe("No images to download (checked " + when + ").");
        expect(formatCacheStatus(finished, texts)) // a status without cached
            .toBe("No images to download (checked " + when + ").");
    });

    test("dates the text, so an old run's status read later is not taken as current", () => {
        const short = lang => new Intl.DateTimeFormat(lang, { dateStyle: "short", timeStyle: "short" }).format(startedAtMs);
        expect(when).toBe(short("en"));
        expect(formatCacheTime(startedAtMs, "uk")).toBe(short("uk"));
        expect(formatCacheTime(startedAtMs, "fr")).toBe(short("fr"));
    });

    test("formats the counts in the page's language", () => {
        const count = lang => new Intl.NumberFormat(lang).format(38367);
        expect(count("en")).toBe("38,367");
        expect(formatCacheStatus({ ...finished, cached: 38367 }, texts))
            .toBe("All 38,367 images are already cached (checked " + when + ").");
        expect(formatCacheStatus({ ...finished, done: 12, total: 12, cached: 38367 }, { ...texts, lang: "uk" }))
            .toBe("12 / 12 downloaded (100%) – done – " + count("uk") + " already cached");
    });

    test("adds the images cached before to a run that downloaded some", () => {
        expect(formatCacheStatus({ ...finished, done: 12, total: 12, cached: 300 }, texts))
            .toBe("12 / 12 downloaded (100%) – done – 300 already cached");
    });

    test("keeps the text of a run that downloaded all its images", () => {
        expect(formatCacheStatus({ ...finished, done: 12, total: 12, cached: 0 }, texts))
            .toBe("12 / 12 downloaded (100%) – done");
    });

    test("keeps the running, starting and failed texts", () => {
        expect(formatCacheStatus({ ...finished, done: 5, total: 12, cached: 300, running: true }, texts))
            .toBe("5 / 12 downloaded (42%) – running…");
        expect(formatCacheStatus({ ...finished, cached: 300, running: true }, texts)).toBe("Starting…");
        expect(formatCacheStatus({ ...finished, cached: 300, failed: true }, texts)).toBe("Download failed");
        expect(formatCacheStatus({ ...finished, done: 4, total: 12, cached: 300, failed: true }, texts))
            .toBe("4 / 12 downloaded (33%) – failed");
    });

    test("shows nothing before any run (startedAtMs 0), as before", () => {
        expect(formatCacheStatus({ ...finished, startedAtMs: 0 }, texts)).toBeNull();
        expect(formatCacheStatus({ total: 0, done: 0, errors: 0, running: false }, texts)).toBeNull();
    });

    test("writes no English of its own: without the page's texts it shows nothing new", () => {
        expect(formatCacheStatus({ ...finished, cached: 300 })).toBeNull();
        expect(formatCacheStatus({ ...finished, cached: 0 }, {})).toBeNull();
        expect(formatCacheStatus({ ...finished, done: 12, total: 12, cached: 300 }))
            .toBe("12 / 12 downloaded (100%) – done");
    });

    test("reads the texts from the element's data-text-* attributes", () => {
        const attrs = {
            "data-text-all-cached": "Усі зображення ({0}) уже в кеші (перевірено {1}).",
            "data-text-nothing": "Немає зображень для завантаження (перевірено {0}).",
            "data-text-also-cached": "уже в кеші: {0}"
        };
        const el = { getAttribute: name => attrs[name] || null };
        const t = cacheStatusTexts(el);
        expect(t.allCached).toBe(attrs["data-text-all-cached"]);
        expect(t.nothing).toBe(attrs["data-text-nothing"]);
        expect(t.alsoCached).toBe(attrs["data-text-also-cached"]);
        expect(formatCacheStatus({ ...finished, cached: 7 }, t))
            .toBe("Усі зображення (7) уже в кеші (перевірено " + formatCacheTime(startedAtMs, t.lang) + ").");
        expect(cacheStatusTexts(null).allCached).toBeUndefined();
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
        statusEl = { textContent: "" };
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
        expect(global.fetch).toHaveBeenCalledWith("/status/42",
            { credentials: "same-origin", headers: { "X-Requested-With": "XMLHttpRequest" } });
    });

    test("updates #cache-status text", async () => {
        mockFetch({ total: 100, done: 60, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/1");
        await flushPromises();
        expect(statusEl.textContent).toBe("60 / 100 downloaded (60%) \u2013 done");
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

    test("stops polling and leaves the text when access is denied (401/403)", async () => {
        for (const code of [401, 403]) {
            statusEl.textContent = "before";
            global.fetch = jest.fn(() => Promise.resolve({ status: code, json: () => Promise.reject(new Error("html")) }));
            pollCacheStatus("/status/1");
            await flushPromises();
            jest.advanceTimersByTime(30000);
            await flushPromises();
            expect(global.fetch).toHaveBeenCalledTimes(1);
            expect(statusEl.textContent).toBe("before");
        }
    });

    test("retries after 5 s when another error answers with no JSON", async () => {
        global.fetch = jest.fn(() => Promise.resolve({ status: 500, json: () => Promise.reject(new Error("html")) }));
        pollCacheStatus("/status/1");
        await flushPromises();
        jest.advanceTimersByTime(5000);
        await flushPromises();
        expect(global.fetch).toHaveBeenCalledTimes(2);
    });

    test("uses custom elementId when provided", async () => {
        const customEl = { textContent: "" };
        global.document = {
            getElementById: jest.fn((id) => id === "round-cache-status" ? customEl : null)
        };
        mockFetch({ total: 100, done: 60, errors: 0, running: false, ratePerSec: 0, etaSeconds: 0 });
        pollCacheStatus("/status/1", "round-cache-status");
        await flushPromises();
        expect(customEl.textContent).toBe("60 / 100 downloaded (60%) \u2013 done");
    });
});
