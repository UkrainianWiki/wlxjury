/**
 * @jest-environment jsdom
 */
"use strict";

// A vote's time (vote-time.js): day, short month and HH:mm in the viewer's time zone and the
// page's language, the year when it isn't the current one; the server's UTC text kept for a
// value it can't read.

const { format, localize } = require("../public/javascripts/vote-time.js");

const now = new Date("2026-11-12T20:00:00Z");

describe("format", () => {

    test("gives the day, short month and 24-hour time, in the time zone given", () => {
        expect(format("2026-11-12T14:05:00.123Z", "en-GB", "UTC", now)).toBe("12 Nov, 14:05");
        expect(format("2026-11-12T14:05:00Z", "en-GB", "Europe/Kyiv", now)).toBe("12 Nov, 16:05");
        // past midnight in Kyiv: the next day there
        expect(format("2026-11-12T22:30:00Z", "en-GB", "Europe/Kyiv", now)).toBe("13 Nov, 00:30");
    });

    test("adds the year when it isn't the current one", () => {
        expect(format("2025-03-04T05:06:00Z", "en-GB", "UTC", now)).toBe("4 Mar 2025, 05:06");
        // the year as the viewer's time zone has it
        expect(format("2025-12-31T23:30:00Z", "en-GB", "Europe/Kyiv", now)).toBe("1 Jan, 01:30");
    });

    test("uses the page's language", () => {
        const uk = format("2026-11-12T14:05:00Z", "uk", "UTC", now);
        expect(uk).toContain("14:05");
        expect(uk).toMatch(/лист/);
    });

    test("adds the year when asked, for the tooltip", () => {
        expect(format("2026-11-12T14:05:00Z", "en-GB", "UTC", now, true)).toBe("12 Nov 2026, 14:05");
    });

    test("gives null for no valid time", () => {
        expect(format("", "en", "UTC", now)).toBeNull();
        expect(format("not a time", "en", "UTC", now)).toBeNull();
        expect(format("2026-11-12T14:05:00Z", "en", "No/Such_Zone", now)).toBeNull();
    });
});

describe("localize", () => {

    test("formats the page's vote times, keeping the UTC value as the title", () => {
        document.documentElement.setAttribute("lang", "en");
        document.body.innerHTML =
            '<time class="wlx-vote-time" datetime="2026-11-12T14:05:00Z">2026-11-12T14:05:00Z</time>' +
            '<time class="wlx-vote-time" datetime="nonsense">nonsense</time>' +
            '<span class="wlx-vote-time-none">Time not recorded</span>';
        localize(document);
        const [good, bad] = document.querySelectorAll("time");
        expect(good.textContent).toBe(format("2026-11-12T14:05:00Z", "en"));
        expect(good.textContent).not.toBe("2026-11-12T14:05:00Z");
        // the tooltip: local, with the year, not the raw UTC value
        expect(good.title).toBe(format("2026-11-12T14:05:00Z", "en", undefined, undefined, true));
        expect(good.title).toMatch(/2026/);
        expect(bad.textContent).toBe("nonsense");
        expect(document.querySelector(".wlx-vote-time-none").textContent).toBe("Time not recorded");
    });

    test("formats a comment's time in the large view the same way, in the page's language", () => {
        document.documentElement.setAttribute("lang", "uk");
        // as large/commentsView.scala.html renders it: the UTC instant, its text the same
        document.body.innerHTML =
            '<div class="wlx-comment-meta"><strong>Juror</strong> <span aria-hidden="true">·</span> ' +
            '<time class="wlx-vote-time wlx-comment-time" datetime="2016-10-05T10:34:56.464Z">2016-10-05T10:34:56.464Z</time></div>';
        localize(document);
        const time = document.querySelector("time.wlx-comment-time");
        expect(time.textContent).toBe(format("2016-10-05T10:34:56.464Z", "uk"));
        // another year: with the year, in Ukrainian
        expect(time.textContent).toMatch(/2016/);
        expect(time.textContent).toMatch(/жовт/);
        expect(time.title).toBe(format("2016-10-05T10:34:56.464Z", "uk", undefined, undefined, true));
    });
});
