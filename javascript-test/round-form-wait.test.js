/**
 * @jest-environment jsdom
 */
"use strict";

// round-form-wait.js: a round form refused to wait for another round's job (UI-path plan Q7).
const { init, INTERVAL } = require("../public/javascripts/round-form-wait");

function page(status) {
    document.body.innerHTML = `
      <div class="alert alert-warning" id="round-form-wait" data-src="/admin/jobs/7" data-status="${status}"
           data-progress-text="{0} of {1} {2}">
        <p id="round-form-wait-running">Round 3 is being distributed: <span id="round-form-wait-progress"></span></p>
        <p id="round-form-wait-done" hidden>Round 3 has finished. You can save now.</p>
        <p id="round-form-wait-ended" hidden>Round 3's job stopped before it finished.</p>
      </div>
      <form id="round-form">
        <input name="name" value="Kept as entered">
        <button type="submit" name="next" value="review">Review images first</button>
        <button type="submit" name="next" value="jurors">Choose jurors next</button>
      </form>`;
}

const buttons = () => Array.from(document.querySelectorAll("#round-form button"));
const shown = (id) => !document.getElementById(id).hidden;

function respond(...bodies) {
    global.fetch = jest.fn();
    bodies.forEach((b) => global.fetch.mockResolvedValueOnce(
        b.httpStatus ? { ok: false, status: b.httpStatus } : { ok: true, status: 200, json: () => Promise.resolve(b) }));
}

/** Runs the poll that is due, and lets its promises settle. */
async function tick() {
    jest.advanceTimersByTime(INTERVAL);
    for (let i = 0; i < 5; i++) await Promise.resolve();
}

beforeEach(() => jest.useFakeTimers());
afterEach(() => { jest.useRealTimers(); delete global.fetch; });

describe("round-form-wait", () => {

    test("disables Save while the job runs, shows its progress, then says it has finished and enables Save", async () => {
        page("running");
        respond({ status: "running", progressDone: 1200, progressTotal: 1800, progressUnit: "files" },
            { status: "done", progressDone: 1800, progressTotal: 1800, progressUnit: "files" });
        init();
        expect(buttons().every((b) => b.disabled)).toBe(true);
        expect(shown("round-form-wait-running")).toBe(true);

        await tick();
        expect(global.fetch).toHaveBeenCalledWith("/admin/jobs/7",
            { credentials: "same-origin", headers: { "X-Requested-With": "XMLHttpRequest" } });
        expect(document.getElementById("round-form-wait-progress").textContent).toBe("1,200 of 1,800 files");
        expect(buttons().every((b) => b.disabled)).toBe(true);

        await tick();
        expect(shown("round-form-wait-done")).toBe(true);
        expect(shown("round-form-wait-running")).toBe(false);
        expect(buttons().every((b) => !b.disabled)).toBe(true);
        expect(document.getElementById("round-form-wait").classList.contains("alert-success")).toBe(true);
        // the form as entered: nothing reloaded
        expect(document.querySelector("input[name=name]").value).toBe("Kept as entered");
        await tick();
        expect(global.fetch).toHaveBeenCalledTimes(2);
    });

    test("says a failed job stopped, Save enabled, and stops polling", async () => {
        page("queued");
        respond({ status: "failed" });
        init();
        await tick();
        expect(shown("round-form-wait-ended")).toBe(true);
        expect(shown("round-form-wait-done")).toBe(false);
        expect(buttons().every((b) => !b.disabled)).toBe(true);
        await tick();
        expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    test("enables Save and stops when the job can't be read (signed out, no access)", async () => {
        page("running");
        respond({ httpStatus: 404 });
        init();
        await tick();
        expect(buttons().every((b) => !b.disabled)).toBe(true);
        await tick();
        expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    test("does nothing on a page without the box", () => {
        document.body.innerHTML = '<form id="round-form"><button type="submit">Save</button></form>';
        global.fetch = jest.fn();
        init();
        expect(document.querySelector("button").disabled).toBe(false);
        expect(global.fetch).not.toHaveBeenCalled();
    });
});
