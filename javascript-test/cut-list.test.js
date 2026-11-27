/**
 * @jest-environment jsdom
 */
"use strict";

// cut-list.js: the round form's cut list, loaded in place: when it is posted (on open with a previous
// round, after a ranking field's change, debounced, never for the cut's own control), what it sends
// (the form's fields, the Csrf-Token header, X-Requested-With), how a newer request wins over an
// older one, what the box and the live region show, and the cut's link.

const { create, ranks, baseName, hasPrevious, RANKING_FIELDS } = require("../public/javascripts/cut-list");

const BOX = (count, extra = "") => `<div data-cut-announce="Cuts available: ${count}" data-cut-count="${count}">
  <select id="topImages" name="topImages">
    <option value="">No top N</option>
    <option value="4" data-cut-href="/byrate/round/2/user/0/page/1?sort=rate&amp;cut=4#cut">top 4 · score ≥ 8</option>
    <option value="9">top 9 · score ≥ 7</option>
  </select>
  <a id="top-n-cut-link" href="#" hidden>Show this cut in the ratings</a>${extra}
</div>`;

const KEEP = `<input type="radio" name="cutKeep" id="cutKeep_threshold" value="threshold" checked>
  <input type="radio" name="cutKeep" id="cutKeep_top" value="top">`;

function page({ prev = "", loaded = false, box = '<div data-cut-announce=""><p id="topImages">No top N</p></div>',
    disabledButton = false } = {}) {
    document.body.innerHTML = `
      <form id="round-form" data-csrf-token="tok-1">
        <input type="hidden" name="csrfToken" value="tok-field">
        <input type="hidden" name="contest" value="7">
        <input type="text" id="name" name="name" value="">
        <select id="previousRound" name="previousRound[]" multiple>
          <option value="1"${prev === "1" ? " selected" : ""}>Round 1</option>
          <option value="2"${prev === "2" ? " selected" : ""}>Round 2</option>
        </select>
        <input type="text" id="source" name="source" value="">
        <input type="checkbox" id="excludeCoi" name="excludeCoi" value="true" checked>
        <select id="jurors" name="jurors[]" multiple><option value="11">Ann</option></select>
        <button type="submit" id="save">Save</button>
        <button type="submit" id="other"${disabledButton ? " disabled" : ""}>Other</button>
        <div id="top-n" data-cut-url="/admin/rounds/cut-list"${loaded ? " data-cut-loaded" : ""}
             data-cut-updating="Updating the cuts…" data-cut-saving="Saving once the cuts are updated…" data-cut-failed="The cuts could not be loaded."
             data-cut-signed-out="You have been signed out." data-cut-csrf="This page has expired.">
          <div id="top-n-box">${box}</div>
          <p id="top-n-status" role="status" aria-live="polite"></p>
        </div>
      </form>`;
}

/** A fetch whose answers the test gives, one per call, in any order. */
function deferredFetch() {
    const calls = [];
    const fetch = jest.fn((url, init) => new Promise((resolve, reject) => {
        calls.push({ url, init, resolve, reject });
    }));
    return { fetch, calls };
}

function html(text, status = 200) {
    return { ok: status >= 200 && status < 300, status, headers: new Map([["Content-Type", "text/html; charset=utf-8"]]),
        text: () => Promise.resolve(text), json: () => Promise.resolve(JSON.parse(text)) };
}

function json(obj, status) {
    return { ok: false, status, text: () => Promise.resolve(JSON.stringify(obj)), json: () => Promise.resolve(obj) };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

/** A submit by `button` (the event's submitter), cancelable as a browser's. */
function submitBy(button) {
    const e = new Event("submit", { cancelable: true });
    Object.defineProperty(e, "submitter", { value: button });
    document.getElementById("round-form").dispatchEvent(e);
    return e;
}
const status = () => document.getElementById("top-n-status").textContent;
const select = (id, value) => {
    const el = document.getElementById(id);
    for (const o of el.options) o.selected = o.value === value;
    el.dispatchEvent(new Event("change", { bubbles: true }));
};

afterEach(() => jest.useRealTimers());

describe("cut-list", () => {
    test("names the ranking fields, a multiple select's [] ignored, and not the cut's own control", () => {
        expect(baseName("previousRound[]")).toBe("previousRound");
        expect(baseName("regions[0]")).toBe("regions");
        for (const name of ["previousRound[]", "regions[]", "source", "excludeCategory", "categoryCombine", "mediaType",
            "minSize", "minMpx", "excludeRounds[]", "excludeCoi", "minJurors", "rejectionFilter", "rejectedBy", "rejectedMode",
            "topBinaryOrder", "monumentIds", "specialNomination"]) expect(ranks({ name })).toBe(true);
        for (const name of ["topImages", "cutKeep", "name", "jurors[]", "distribution", "rates", "trackLabel"])
            expect(ranks({ name })).toBe(false);
        expect(RANKING_FIELDS).not.toContain("topImages");
        expect(hasPrevious("previousRound%5B%5D=3&a=1")).toBe(true);
        expect(hasPrevious("a=1&previousRound%5B%5D=")).toBe(false);
    });

    test("loads as the form opens with a previous round: the form's fields, the CSRF header, a script request", async () => {
        page({ prev: "2" });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        expect(fetch).toHaveBeenCalledTimes(1);
        const { url, init } = calls[0];
        expect(url).toBe("/admin/rounds/cut-list");
        expect(init.method).toBe("POST");
        expect(init.headers["Csrf-Token"]).toBe("tok-1");
        expect(init.headers["X-Requested-With"]).toBe("XMLHttpRequest");
        const sent = new URLSearchParams(init.body);
        expect(sent.getAll("previousRound[]")).toEqual(["2"]);
        expect(sent.get("contest")).toBe("7");
        expect(sent.get("excludeCoi")).toBe("true");
        expect(sent.get("csrfToken")).toBe("tok-field");
        // updating, said politely, and the box busy
        expect(status()).toBe("Updating the cuts…");
        expect(document.getElementById("top-n-box").getAttribute("aria-busy")).toBe("true");

        calls[0].resolve(html(BOX(2)));
        await flush();
        await flush();
        expect(document.querySelector("#top-n-box select#topImages")).not.toBeNull();
        expect(document.getElementById("top-n-box").hasAttribute("aria-busy")).toBe(false);
        expect(status()).toBe("Cuts available: 2");
    });

    test("doesn't load on open without a previous round, nor when the page came with the control", () => {
        page();
        const a = deferredFetch();
        create(document, { fetch: a.fetch });
        expect(a.fetch).not.toHaveBeenCalled();

        page({ prev: "1", loaded: true, box: BOX(2) });
        const b = deferredFetch();
        create(document, { fetch: b.fetch });
        expect(b.fetch).not.toHaveBeenCalled();
    });

    test("a previous round chosen loads after a short pause; changes in a row load once", () => {
        jest.useFakeTimers();
        page();
        const { fetch } = deferredFetch();
        create(document, { fetch });
        select("previousRound", "1");
        select("previousRound", "2");
        expect(fetch).not.toHaveBeenCalled();
        jest.advanceTimersByTime(150);
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(new URLSearchParams(fetch.mock.calls[0][1].body).getAll("previousRound[]")).toEqual(["2"]);
    });

    test("typing in a filter loads once the typing stops; its change on leaving the box doesn't load again", () => {
        jest.useFakeTimers();
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        const source = document.getElementById("source");
        source.value = "Cat";
        source.dispatchEvent(new Event("input", { bubbles: true }));
        jest.advanceTimersByTime(300);
        source.value = "Cats";
        source.dispatchEvent(new Event("input", { bubbles: true }));
        jest.advanceTimersByTime(499);
        expect(fetch).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1);
        expect(fetch).toHaveBeenCalledTimes(1);
        source.dispatchEvent(new Event("change", { bubbles: true }));
        jest.advanceTimersByTime(500);
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    test("the cut's own control, and fields that don't rank, never load", () => {
        jest.useFakeTimers();
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        select("topImages", "4");
        select("jurors", "11");
        const name = document.getElementById("name");
        name.value = "Final";
        name.dispatchEvent(new Event("input", { bubbles: true }));
        jest.advanceTimersByTime(1000);
        expect(fetch).not.toHaveBeenCalled();
    });

    test("a box ticked loads; without a previous round and no list shown, nothing is asked", () => {
        jest.useFakeTimers();
        page();
        const { fetch } = deferredFetch();
        create(document, { fetch });
        const coi = document.getElementById("excludeCoi");
        coi.checked = false;
        coi.dispatchEvent(new Event("change", { bubbles: true }));
        jest.advanceTimersByTime(200);
        expect(fetch).not.toHaveBeenCalled();

        page({ prev: "1", loaded: true, box: BOX(2) });
        const b = deferredFetch();
        create(document, { fetch: b.fetch });
        const coi2 = document.getElementById("excludeCoi");
        coi2.checked = false;
        coi2.dispatchEvent(new Event("change", { bubbles: true }));
        jest.advanceTimersByTime(150);
        expect(b.fetch).toHaveBeenCalledTimes(1);
        expect(new URLSearchParams(b.fetch.mock.calls[0][1].body).has("excludeCoi")).toBe(false);
    });

    test("the previous rounds cleared after a list: one more load clears the box", () => {
        jest.useFakeTimers();
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        select("previousRound", "none");
        jest.advanceTimersByTime(150);
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    test("a response during the debounce still refreshes the newly selected source round", async () => {
        jest.useFakeTimers();
        page({ prev: "1" });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });

        select("previousRound", "2");
        calls[0].resolve(html(BOX(2)));
        await jest.advanceTimersByTimeAsync(0);
        expect(status()).toBe("Cuts available: 2");

        await jest.advanceTimersByTimeAsync(150);
        expect(calls).toHaveLength(2);
        expect(new URLSearchParams(calls[1].init.body).getAll("previousRound[]")).toEqual(["2"]);
    });

    test("a slower older answer never overwrites a newer one; the older request is aborted", async () => {
        page({ prev: "1" });
        const { fetch, calls } = deferredFetch();
        const list = create(document, { fetch });
        select("previousRound", "2");
        list.load();
        expect(calls).toHaveLength(2);
        expect(calls[0].init.signal.aborted).toBe(true);
        calls[1].resolve(html(BOX(5)));
        await flush();
        await flush();
        expect(status()).toBe("Cuts available: 5");
        calls[0].resolve(html(BOX(1, '<span id="old"></span>')));
        await flush();
        await flush();
        expect(document.getElementById("old")).toBeNull();
        expect(status()).toBe("Cuts available: 5");
        // an aborted request's rejection says nothing either
        const abort = new Error("aborted");
        abort.name = "AbortError";
        calls[0].reject(abort);
        await flush();
        expect(status()).toBe("Cuts available: 5");
    });

    test("a failure says so with the page's texts, never the response's text", async () => {
        const cases = [
            // a 2xx that isn't the box (a proxy's page, JSON): not put in the page
            [{ ok: true, status: 200, headers: new Map([["Content-Type", "application/json"]]), json: () => Promise.resolve({}),
                text: () => Promise.resolve("<b>x</b>") }, "The cuts could not be loaded."],
            [json({ error: "unauthenticated" }, 401), "You have been signed out."],
            [json({ error: "csrf", reference: "abc" }, 403), "This page has expired."],
            [json({ error: "forbidden", reference: "abc" }, 403), "The cuts could not be loaded."],
            [json({ error: "not_editable", message: "Round 3's files are chosen: its cut can't change." }, 409),
                "Round 3's files are chosen: its cut can't change."],
            [{ ok: false, status: 500, json: () => Promise.reject(new Error("not json")), text: () => Promise.resolve("<pre>stack</pre>") },
                "The cuts could not be loaded."],
        ];
        for (const [response, expected] of cases) {
            page({ prev: "1" });
            const { fetch, calls } = deferredFetch();
            create(document, { fetch });
            calls[0].resolve(response);
            await flush();
            await flush();
            await flush();
            expect(status()).toBe(expected);
            expect(document.getElementById("top-n-status").classList.contains("text-danger")).toBe(true);
            expect(document.getElementById("top-n-box").hasAttribute("aria-busy")).toBe(false);
        }
        page({ prev: "1" });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        calls[0].reject(new TypeError("Failed to fetch"));
        await flush();
        await flush();
        expect(status()).toBe("The cuts could not be loaded.");
    });

    test("an error in the box is announced; focus isn't moved, except back to the control it was on", async () => {
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch, calls } = deferredFetch();
        const list = create(document, { fetch });
        const name = document.getElementById("name");
        name.focus();
        list.load();
        calls[0].resolve(html('<div data-cut-announce="Commons could not be reached."><p id="topImages" class="is-invalid">No top N</p></div>'));
        await flush();
        await flush();
        expect(status()).toBe("Commons could not be reached.");
        expect(document.getElementById("top-n-status").classList.contains("text-danger")).toBe(false);
        expect(document.activeElement).toBe(name);

        page({ prev: "1", loaded: true, box: BOX(2) });
        const b = deferredFetch();
        const again = create(document, { fetch: b.fetch });
        document.getElementById("topImages").focus();
        again.load();
        b.calls[0].resolve(html(BOX(3)));
        await flush();
        await flush();
        expect(document.activeElement.id).toBe("topImages");
        expect(document.getElementById("top-n-box").contains(document.activeElement)).toBe(true);
    });

    test("the cut's link follows the chosen cut, also in a box loaded later; hidden without one", async () => {
        page({ prev: "1" });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        calls[0].resolve(html(BOX(2)));
        await flush();
        await flush();
        const link = document.getElementById("top-n-cut-link");
        expect(link.hidden).toBe(true);
        select("topImages", "4");
        expect([link.hidden, link.getAttribute("href")]).toEqual([false, "/byrate/round/2/user/0/page/1?sort=rate&cut=4#cut"]);
        select("topImages", "9");
        expect([link.hidden, link.getAttribute("href")]).toEqual([true, "#"]);
    });

    test("a cut picked while a list loads is put back into the new list that has it", async () => {
        page({ prev: "1", loaded: true, box: BOX(2, KEEP) });
        const { fetch, calls } = deferredFetch();
        const list = create(document, { fetch });
        list.load();
        select("topImages", "9");
        const top = document.getElementById("cutKeep_top");
        top.checked = true;
        top.dispatchEvent(new Event("change", { bubbles: true }));
        // the answer was built from the form before the pick: it chooses "No top N" and the threshold
        calls[0].resolve(html(BOX(3, KEEP)));
        await flush();
        await flush();
        expect(document.getElementById("topImages").value).toBe("9");
        expect(document.getElementById("cutKeep_top").checked).toBe(true);
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(status()).toBe("Cuts available: 3");
    });

    test("a cut picked while a list loads that the new list lacks is asked for again, then left to the server", async () => {
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch, calls } = deferredFetch();
        const list = create(document, { fetch });
        list.load();
        select("topImages", "9");
        const without9 = `<div data-cut-announce="Cuts available: 1"><select id="topImages" name="topImages">
          <option value="">No top N</option><option value="4">top 4</option></select></div>`;
        calls[0].resolve(html(without9));
        await flush();
        await flush();
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(new URLSearchParams(calls[1].init.body).getAll("topImages")).toEqual(["9"]);
        expect(document.getElementById("top-n-box").getAttribute("aria-busy")).toBe("true");
        const explained = `<div data-cut-announce="Cuts available: 1"><div id="top-n-held">N = 9 is inside a tie</div>
          <select id="topImages" name="topImages"><option value="9" selected>Choose N from the list</option>
          <option value="">No top N</option><option value="4">top 4</option></select></div>`;
        calls[1].resolve(html(explained));
        await flush();
        await flush();
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(document.getElementById("top-n-held")).not.toBeNull();
        expect(document.getElementById("topImages").value).toBe("9");
    });

    test("while the box says the form's errors keep the list from loading, any field's change reloads", () => {
        jest.useFakeTimers();
        const invalid = '<div data-cut-announce="The cuts are listed once the form’s errors are corrected.">' +
            '<p id="topImages">No top N</p><div id="top-n-form-invalid">x</div></div>';
        page({ prev: "1", loaded: true, box: invalid });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        const name = document.getElementById("name");
        name.value = "Final";
        name.dispatchEvent(new Event("input", { bubbles: true }));
        jest.advanceTimersByTime(500);
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    test("the previous rounds cleared while the box shows an error: the box is asked for again, not left stale", () => {
        jest.useFakeTimers();
        const error = '<div data-cut-announce="Commons could not be reached."><p id="topImages" class="is-invalid">No top N</p></div>';
        page({ prev: "1", loaded: true, box: error });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        select("previousRound", "none");
        jest.advanceTimersByTime(150);
        expect(fetch).toHaveBeenCalledTimes(1);
    });

    test("Save is disabled while a list loads; a save then is held and sent with its button once the list is in", async () => {
        jest.useFakeTimers();
        page({ prev: "1", loaded: true, box: BOX(2), disabledButton: true });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        const form = document.getElementById("round-form");
        form.requestSubmit = jest.fn();
        const save = document.getElementById("save");
        const other = document.getElementById("other");
        select("previousRound", "2");
        // the pause: Save stays enabled (a field left by a click on Save changes as the click starts)
        expect(save.disabled).toBe(false);
        jest.advanceTimersByTime(150);
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(save.disabled).toBe(true); // loading
        // Enter in a field, or a click as the load starts: held, said
        const held = submitBy(save);
        expect(held.defaultPrevented).toBe(true);
        expect(status()).toBe("Saving once the cuts are updated…");
        expect(form.requestSubmit).not.toHaveBeenCalled();
        jest.useRealTimers();
        calls[0].resolve(html(BOX(2)));
        await flush();
        await flush();
        expect(save.disabled).toBe(false);
        expect(other.disabled).toBe(true); // disabled by the page: left so
        expect(form.requestSubmit).toHaveBeenCalledTimes(1);
        expect(form.requestSubmit).toHaveBeenCalledWith(save);
        // the save sent again goes through
        expect(submitBy(save).defaultPrevented).toBe(false);
    });

    test("a save held while a list loads is sent after a failure too, without a button if it had none", async () => {
        page({ prev: "1" });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        const form = document.getElementById("round-form");
        form.requestSubmit = jest.fn();
        expect(submitBy(undefined).defaultPrevented).toBe(true);
        calls[0].resolve(json({ error: "forbidden" }, 403));
        await flush();
        await flush();
        await flush();
        expect(status()).toBe("The cuts could not be loaded.");
        expect(form.requestSubmit).toHaveBeenCalledTimes(1);
        expect(form.requestSubmit.mock.calls[0]).toEqual([]);
        expect(document.getElementById("save").disabled).toBe(false);
    });

    test("a save held while a list loads waits for the list asked again for a cut picked meanwhile", async () => {
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch, calls } = deferredFetch();
        const list = create(document, { fetch });
        const form = document.getElementById("round-form");
        form.requestSubmit = jest.fn();
        list.load();
        select("topImages", "9");
        submitBy(document.getElementById("save"));
        calls[0].resolve(html(`<div data-cut-announce="Cuts available: 1"><select id="topImages" name="topImages">
          <option value="">No top N</option><option value="4">top 4</option></select></div>`));
        await flush();
        await flush();
        expect(fetch).toHaveBeenCalledTimes(2);
        expect(form.requestSubmit).not.toHaveBeenCalled();
        calls[1].resolve(html(`<div data-cut-announce="Cuts available: 1"><select id="topImages" name="topImages">
          <option value="9" selected>Choose N from the list</option><option value="">No top N</option></select></div>`));
        await flush();
        await flush();
        expect(form.requestSubmit).toHaveBeenCalledWith(document.getElementById("save"));
    });

    test("a submit during the pause asks for the list at once and is held; with nothing to ask it goes", () => {
        jest.useFakeTimers();
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        const form = document.getElementById("round-form");
        // a change and its undo: the form as last asked for, so the save goes
        const coi = document.getElementById("excludeCoi");
        coi.checked = false;
        coi.dispatchEvent(new Event("change", { bubbles: true }));
        coi.checked = true;
        coi.dispatchEvent(new Event("change", { bubbles: true }));
        const plain = new Event("submit", { cancelable: true });
        form.dispatchEvent(plain);
        expect(plain.defaultPrevented).toBe(false);
        expect(fetch).not.toHaveBeenCalled();
        jest.advanceTimersByTime(500);
        expect(fetch).not.toHaveBeenCalled(); // the pause ended with the submit
        // a filter typed, then Save: the list asked for now, the save held
        const source = document.getElementById("source");
        source.value = "Cats";
        source.dispatchEvent(new Event("input", { bubbles: true }));
        form.requestSubmit = jest.fn();
        const held = submitBy(document.getElementById("save"));
        expect(held.defaultPrevented).toBe(true);
        expect(status()).toBe("Saving once the cuts are updated…");
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(new URLSearchParams(fetch.mock.calls[0][1].body).get("source")).toBe("Cats");
        expect(document.getElementById("save").disabled).toBe(true);
    });

    test("a new round without a previous round saves at once after typing a filter", () => {
        jest.useFakeTimers();
        page();
        const { fetch } = deferredFetch();
        create(document, { fetch });
        const source = document.getElementById("source");
        source.value = "Cats";
        source.dispatchEvent(new Event("input", { bubbles: true }));
        source.dispatchEvent(new Event("change", { bubbles: true }));
        expect(document.getElementById("save").disabled).toBe(false);
        const submit = new Event("submit", { cancelable: true });
        document.getElementById("round-form").dispatchEvent(submit);
        expect(submit.defaultPrevented).toBe(false);
        expect(fetch).not.toHaveBeenCalled();
    });

    test("Save is enabled again after a failure", async () => {
        jest.useFakeTimers();
        page({ prev: "1", loaded: true, box: BOX(2) });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        const save = document.getElementById("save");
        select("previousRound", "2");
        jest.advanceTimersByTime(150);
        expect(save.disabled).toBe(true);
        jest.useRealTimers();
        calls[0].resolve(json({ error: "forbidden" }, 403));
        await flush();
        await flush();
        await flush();
        expect(save.disabled).toBe(false);
    });

    test("until a cut is picked, a refresh posts the held threshold again, not the count it took", async () => {
        page({ prev: "1", box: '<div data-cut-announce=""><input type="hidden" name="minAvgRate" value="7.5"><p id="topImages">files scoring at least 7.5</p></div>' });
        const { fetch, calls } = deferredFetch();
        create(document, { fetch });
        expect(new URLSearchParams(calls[0].init.body).getAll("minAvgRate")).toEqual(["7.5"]);
        // the list: the cut the threshold keeps (4) preselected, kept as the threshold
        calls[0].resolve(html(BOX(2, KEEP).replace('<option value="4"', '<option value="4" selected')));
        await flush();
        await flush();
        expect(document.getElementById("topImages").value).toBe("4");
        jest.useFakeTimers();
        const coi = document.getElementById("excludeCoi");
        coi.checked = false;
        coi.dispatchEvent(new Event("change", { bubbles: true }));
        jest.advanceTimersByTime(150);
        const refresh = new URLSearchParams(calls[1].init.body);
        expect(refresh.getAll("minAvgRate")).toEqual(["7.5"]);
        expect(refresh.getAll("topImages")).toEqual([]);
        expect(refresh.getAll("cutKeep")).toEqual([]);
        // picked: the pick is posted, the held threshold no more
        select("topImages", "9");
        coi.checked = true;
        coi.dispatchEvent(new Event("change", { bubbles: true }));
        jest.advanceTimersByTime(150);
        const picked = new URLSearchParams(calls[2].init.body);
        expect(picked.getAll("topImages")).toEqual(["9"]);
        expect(picked.getAll("minAvgRate")).toEqual([]);
        expect(picked.getAll("cutKeep")).toEqual(["threshold"]);
    });

    test("a page restored from the back/forward cache never keeps Save disabled", () => {
        page({ prev: "1" });
        const { fetch } = deferredFetch();
        create(document, { fetch });
        const save = document.getElementById("save");
        expect(save.disabled).toBe(true); // loading as it opened
        const notRestored = new Event("pageshow");
        notRestored.persisted = false;
        window.dispatchEvent(notRestored);
        expect(save.disabled).toBe(true);
        const restored = new Event("pageshow");
        restored.persisted = true;
        window.dispatchEvent(restored);
        expect(save.disabled).toBe(false);
        expect(document.getElementById("top-n-box").hasAttribute("aria-busy")).toBe(false);
        const submit = new Event("submit", { cancelable: true });
        document.getElementById("round-form").dispatchEvent(submit);
        expect(submit.defaultPrevented).toBe(false);
    });

    test("a page without a cut box, or without its URL, is left alone", () => {
        document.body.innerHTML = '<form><p id="topImages">No top N</p></form>';
        expect(create(document, { fetch: jest.fn() })).toBeNull();
        page({ prev: "1" });
        document.getElementById("top-n").removeAttribute("data-cut-url");
        const fetch = jest.fn();
        expect(create(document, { fetch })).toBeNull();
        expect(fetch).not.toHaveBeenCalled();
    });
});
