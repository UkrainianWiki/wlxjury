"use strict";

const { formatJobStatus, formatJobProgress, pollJobStatus } = require("../public/javascripts/job-status");

// ---------------------------------------------------------------------------
// formatJobStatus – pure logic, no DOM needed
// ---------------------------------------------------------------------------

describe("formatJobStatus", () => {

    const running = {
        status: "running", title: "Distributing images to round 7",
        progressDone: 40000, progressTotal: 115101, progressUnit: "images",
        details: { imagesAdded: 12345 }
    };

    test("shows a running job's progress, percentage and images added", () => {
        expect(formatJobStatus(running)).toBe(
            "Distributing images to round 7: 40,000 / 115,101 images (34%), 12,345 added…");
    });

    test("shows 'starting' before the first step reports progress", () => {
        expect(formatJobStatus({ status: "running", title: "Distributing", details: {} }))
            .toBe("Distributing: starting…");
    });

    test("shows the queue position of a queued job", () => {
        expect(formatJobStatus({ status: "queued", queuePosition: 2 })).toBe("Queued (2 ahead)");
        expect(formatJobStatus({ status: "queued", queuePosition: 0 })).toBe("Queued");
    });

    test("shows where a failed job stopped and why", () => {
        expect(formatJobStatus(Object.assign({}, running, { status: "failed", error: "Deadlock" })))
            .toBe("Failed at 40,000 / 115,101 images (34%), 12,345 added: Deadlock");
    });

    test("shows a cancelled job's progress", () => {
        expect(formatJobStatus(Object.assign({}, running, { status: "cancelled" })))
            .toBe("Cancelled at 40,000 / 115,101 images (34%), 12,345 added");
    });

    test("shows a finished job's result", () => {
        expect(formatJobStatus({ status: "done", details: { imagesAdded: 5 } })).toBe("Done, 5 added");
    });

    test("works for a kind without details or a total", () => {
        expect(formatJobStatus({ status: "running", title: "Importing", progressDone: 3, progressUnit: "pages" }))
            .toBe("Importing: 3 pages…");
    });

    test("shows an import's counts, leaving out the zero ones", () => {
        expect(formatJobStatus({
            status: "running", title: "Importing files from Category:X",
            progressDone: 100, progressTotal: 400, progressUnit: "files",
            details: { inserted: 1200, linked: 0, updated: 3, monumentIdsChanged: 0, missingTitles: [] }
        })).toBe("Importing files from Category:X: 100 / 400 files (25%), 1,200 new, 3 updated…");
        expect(formatJobStatus({ status: "done", details: { inserted: 0, missing: 300, missingTitles: ["File:A.jpg", "File:B.jpg"] } }))
            .toBe("Done, 300 not found");
        expect(formatJobStatus({ status: "done", details: { monuments: 51234 } })).toBe("Done, 51,234 monuments");
        expect(formatJobStatus({ status: "done", details: { inserted: 0, updated: 0 } })).toBe("Done");
    });
});

describe("formatJobProgress", () => {
    test("caps the percentage at 100 and leaves out an empty total", () => {
        expect(formatJobProgress({ progressDone: 12, progressTotal: 10 })).toBe("12 / 10 (100%)");
        expect(formatJobProgress({ progressDone: 0, progressTotal: 0 })).toBe("");
    });
});

// ---------------------------------------------------------------------------
// pollJobStatus – with a fake element and fetch
// ---------------------------------------------------------------------------

function fakeElement(status) {
    const attrs = { "data-src": "/admin/jobs/1", "data-status": status, "data-reload-on-done": "true",
        "data-denied-text": "No access" };
    const parts = {
        ".job-status-text": { textContent: "" },
        ".job-retry": { style: { display: "none" } },
        ".job-cancel": { style: { display: "inline" } }
    };
    return {
        getAttribute: (k) => attrs[k],
        setAttribute: (k, v) => { attrs[k] = v; },
        querySelector: (sel) => parts[sel],
        parts: parts
    };
}

function respond(...bodies) {
    let i = 0;
    global.fetch = jest.fn(() => Promise.resolve({ json: () => Promise.resolve(bodies[Math.min(i++, bodies.length - 1)]) }));
}

describe("pollJobStatus", () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => { jest.useRealTimers(); delete global.fetch; });

    test("shows Retry and Cancel for a failed job and stops polling", async () => {
        respond({ status: "failed", error: "boom" });
        const el = fakeElement("running");
        await pollJobStatus(el, { reload: jest.fn() });
        expect(el.parts[".job-status-text"].textContent).toBe("Failed: boom");
        expect(el.parts[".job-retry"].style.display).toBe("inline");
        expect(el.parts[".job-cancel"].style.display).toBe("inline");
        expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    test("polls while the job runs and reloads the page when it is done", async () => {
        respond({ status: "running", title: "Distributing", progressDone: 1, progressTotal: 2 }, { status: "done" });
        const reload = jest.fn();
        const el = fakeElement("queued");
        await pollJobStatus(el, { reload: reload, interval: 1000 });
        expect(el.parts[".job-retry"].style.display).toBe("none");
        expect(reload).not.toHaveBeenCalled();
        await jest.advanceTimersByTimeAsync(1000);
        for (let i = 0; i < 10; i++) await Promise.resolve();
        expect(global.fetch).toHaveBeenCalledTimes(2);
        expect(reload).toHaveBeenCalledTimes(1);
        expect(el.parts[".job-cancel"].style.display).toBe("none");
    });

    test("doesn't reload a page that showed an already finished job", async () => {
        respond({ status: "done" });
        const reload = jest.fn();
        await pollJobStatus(fakeElement("done"), { reload: reload });
        expect(reload).not.toHaveBeenCalled();
    });

    test("stops polling and says so when access to the job is denied", async () => {
        for (const code of [401, 403]) {
            global.fetch = jest.fn(() => Promise.resolve({ status: code, json: () => Promise.reject(new Error("html")) }));
            const el = fakeElement("running");
            await pollJobStatus(el, { reload: jest.fn(), interval: 1000 });
            await jest.advanceTimersByTimeAsync(10000);
            expect(global.fetch).toHaveBeenCalledTimes(1);
            expect(el.parts[".job-status-text"].textContent).toBe("No access");
            expect(el.parts[".job-cancel"].style.display).toBe("none");
        }
    });

    test("keeps retrying, slower, after another failure", async () => {
        global.fetch = jest.fn(() => Promise.resolve({ status: 500, json: () => Promise.reject(new Error("html")) }));
        await pollJobStatus(fakeElement("running"), { reload: jest.fn(), interval: 1000 });
        await jest.advanceTimersByTimeAsync(2000);
        expect(global.fetch).toHaveBeenCalledTimes(2);
    });
});
