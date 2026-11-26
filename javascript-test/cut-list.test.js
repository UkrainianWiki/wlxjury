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

function page({ prev = "", loaded = false, box = '<div data-cut-announce=""><p id="topImages">No top N</p></div>' } = {}) {
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
        <div id="top-n" data-cut-url="/admin/rounds/cut-list"${loaded ? " data-cut-loaded" : ""}
             data-cut-updating="Updating the cuts…" data-cut-failed="The cuts could not be loaded."
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
            "minSize", "minMpx", "excludeRounds[]", "excludeCoi", "minJurors", "rejectedBy", "rejectedMode",
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
