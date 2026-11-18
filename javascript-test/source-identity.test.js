/**
 * @jest-environment jsdom
 */
"use strict";

// The import form's identity warning (source-identity.js, support diagnostics plan DG10): an edit
// of the source (the field, or Tom Select's own input while typing) asks the server's check once
// it settles and shows or hides the warning; an older answer is dropped; a failed check clears the
// box and is asked again; a send of a source not checked yet checks it at once, then goes on, also
// when the check fails or is slow (never blocked).

const { initSourceIdentity } = require("../public/javascripts/source-identity");

const wait = (ms) => new Promise(r => setTimeout(r, ms));
const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); await wait(0); };

function page(warning) {
    document.body.innerHTML = `
        <form id="import-images">
          <input type="text" id="source" name="source" value="Category:Images from Wiki Loves Monuments 2026 in Nepal">
          <input type="text" id="source-ts-control">
          <div id="source-identity-warning" role="status" data-src="/contest/7/importimages/source-identity"
               ${warning ? "" : "hidden"}>${warning || ""}</div>
          <button type="submit" id="import-btn" name="action" value="import.files">Import</button>
        </form>`;
    const form = document.getElementById("import-images");
    form.requestSubmit = jest.fn();
    return {
        form, input: document.getElementById("source"), typed: document.getElementById("source-ts-control"),
        box: document.getElementById("source-identity-warning"), button: document.getElementById("import-btn")
    };
}

/** fetch answering in turn: an object (its `warning`), `{ok: false}`, an Error, or a promise. */
function answering(answers) {
    const urls = [];
    global.fetch = jest.fn(url => {
        urls.push(decodeURIComponent(url.split("source=")[1]));
        const answer = answers.shift();
        if (answer instanceof Error) return Promise.reject(answer);
        if (answer && typeof answer.then === "function") return answer;
        return Promise.resolve({ ok: answer.ok !== false, json: () => Promise.resolve(answer) });
    });
    return urls;
}

function edit(input, value, type = "change") {
    input.value = value;
    input.dispatchEvent(new Event(type, { bubbles: true }));
}

function submit(form, button) {
    const event = new Event("submit", { bubbles: true, cancelable: true });
    event.submitter = button;
    form.dispatchEvent(event);
    return event;
}

afterEach(() => { delete global.fetch; });

test("asks once after several edits within the delay, and shows the server's text as text", async () => {
    const { input, box } = page(null);
    const urls = answering([{ warning: "This contest is Wiki Loves Earth 2020, but <b>x</b>" }]);
    initSourceIdentity(document, 40);
    edit(input, "W", "input");
    await wait(10);
    edit(input, "WL", "input");
    await wait(10);
    edit(input, "WLM 2026 in Nepal", "input");
    await wait(20);
    expect(global.fetch).not.toHaveBeenCalled();
    await wait(40);
    await settle();
    expect(urls).toEqual(["WLM 2026 in Nepal"]);
    expect(global.fetch.mock.calls[0][1].headers["X-Requested-With"]).toBe("XMLHttpRequest");
    expect(box.hidden).toBe(false);
    expect(box.textContent).toBe("This contest is Wiki Loves Earth 2020, but <b>x</b>");
    expect(box.querySelector("b")).toBeNull();
});

test("checks the text typed into Tom Select's own input, and hides the warning for a matching one", async () => {
    const { typed, box } = page("A warning");
    const urls = answering([{ warning: null }]);
    initSourceIdentity(document, 0);
    edit(typed, "Category:Images from Wiki Loves Earth 2020 in Armenia", "input");
    await wait(5);
    await settle();
    expect(urls).toEqual(["Category:Images from Wiki Loves Earth 2020 in Armenia"]);
    expect(box.hidden).toBe(true);
});

test("asks nothing for an unchanged source; a failed check clears the stale warning and is asked again", async () => {
    const { input, box } = page("A warning for the old source");
    const urls = answering([{ ok: false }, new Error("offline"), { warning: "The new warning" }]);
    initSourceIdentity(document, 0);

    edit(input, input.value);
    await wait(5);
    expect(global.fetch).not.toHaveBeenCalled();

    edit(input, "Category:Other");
    await wait(5);
    await settle();
    expect(box.hidden).toBe(true);
    expect(box.textContent).toBe("");
    // the same text again: not checked yet, so asked again (a network error this time), then answered
    edit(input, "Category:Other");
    await wait(5);
    await settle();
    edit(input, "Category:Other");
    await wait(5);
    await settle();
    expect(urls).toEqual(["Category:Other", "Category:Other", "Category:Other"]);
    expect(box.textContent).toBe("The new warning");

    edit(input, "  ");
    await wait(5);
    expect(box.hidden).toBe(true);
    expect(global.fetch).toHaveBeenCalledTimes(3);
});

test("drops an older answer that comes after a newer one", async () => {
    const { input, box } = page(null);
    let release;
    answering([
        new Promise(r => { release = () => r({ ok: true, json: () => Promise.resolve({ warning: "old" }) }); }),
        { warning: null }
    ]);
    initSourceIdentity(document, 0);
    edit(input, "first");
    await wait(5);
    edit(input, "second");
    await wait(5);
    await settle();
    release();
    await settle();
    expect(box.hidden).toBe(true);
    expect(box.textContent).toBe("");
});

test("a send of an unchecked source checks it at once, then goes on with its button", async () => {
    const { form, input, box, button } = page(null);
    const urls = answering([{ warning: "This contest is Wiki Loves Earth 2020, but …" }]);
    initSourceIdentity(document, 10000, 2000);
    input.value = "WLM 2026 in Nepal"; // Tom Select set it on leaving the field: no event awaited
    const event = submit(form, button);
    expect(event.defaultPrevented).toBe(true);
    await settle();
    expect(urls).toEqual(["WLM 2026 in Nepal"]);
    expect(box.hidden).toBe(false);
    expect(form.requestSubmit).toHaveBeenCalledTimes(1);
    expect(form.requestSubmit).toHaveBeenCalledWith(button);
    // its own send goes through
    expect(submit(form, button).defaultPrevented).toBe(false);
});

test("a send goes on when the check fails, or after the wait when it is slow; a checked source isn't held", async () => {
    const r1 = page(null);
    answering([new Error("offline")]);
    initSourceIdentity(document, 10000, 50);
    r1.input.value = "Category:Other";
    expect(submit(r1.form, r1.button).defaultPrevented).toBe(true);
    await settle();
    expect(r1.form.requestSubmit).toHaveBeenCalledTimes(1);

    const r2 = page(null);
    answering([new Promise(() => {})]); // never answers
    initSourceIdentity(document, 10000, 30);
    r2.input.value = "Category:Slow";
    submit(r2.form, r2.button);
    await wait(10);
    expect(r2.form.requestSubmit).not.toHaveBeenCalled();
    await wait(40);
    expect(r2.form.requestSubmit).toHaveBeenCalledTimes(1);

    const r3 = page("Rendered warning");
    global.fetch = jest.fn();
    initSourceIdentity(document, 10000, 30);
    expect(submit(r3.form, r3.button).defaultPrevented).toBe(false);
    expect(global.fetch).not.toHaveBeenCalled();
});

test("a second send while the check is waited for is held: one send goes on", async () => {
    const { form, input, button } = page(null);
    let release;
    answering([new Promise(r => { release = () => r({ ok: true, json: () => Promise.resolve({ warning: null }) }); })]);
    initSourceIdentity(document, 10000, 2000);
    input.value = "Category:Twice";
    expect(submit(form, button).defaultPrevented).toBe(true);
    expect(submit(form, button).defaultPrevented).toBe(true); // a second click, or Enter
    expect(global.fetch).toHaveBeenCalledTimes(1);
    release();
    await settle();
    expect(form.requestSubmit).toHaveBeenCalledTimes(1);
});

test("a page brought back from the back/forward cache checks and holds a send again", async () => {
    const { form, input, button } = page(null);
    answering([{ warning: null }, { warning: null }]);
    initSourceIdentity(document, 10000, 2000);
    input.value = "Category:First";
    submit(form, button);
    await settle();
    expect(form.requestSubmit).toHaveBeenCalledTimes(1);
    // Back after the send: the page as it was, its send let through until restored
    const shown = new Event("pageshow");
    shown.persisted = true;
    window.dispatchEvent(shown);
    input.value = "Category:Second";
    expect(submit(form, button).defaultPrevented).toBe(true);
    await settle();
    expect(global.fetch).toHaveBeenCalledTimes(2);
    expect(form.requestSubmit).toHaveBeenCalledTimes(2);
});

test("does nothing on a page without the field", () => {
    document.body.innerHTML = "<div></div>";
    global.fetch = jest.fn();
    expect(initSourceIdentity(document, 0)).toBe(false);
});
