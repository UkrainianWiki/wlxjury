/** @jest-environment node */
"use strict";

const { JSDOM } = require("jsdom");
const { init } = require("../public/javascripts/round-form-draft");

const key = "b6a3881e-71e8-36df-8d1b-4af0801b6ae2";
const slot = "wlxjury:round-draft:" + key;
const otherKey = "ab5cd8a1-81b4-3d98-8ae1-69ecaf747089";
const form = (fields, refused = false) => `
  <form id="round-form" data-round-draft-key="${key}">
    <div id="round-draft-restored" hidden></div>
    ${refused ? '<div id="round-form-errors"></div>' : ""}
    <input name="csrfToken" type="hidden" value="csrf-fresh">
    <input name="submitToken" type="hidden" value="submit-fresh">
    ${fields}
    <button id="round-form-reset" type="button" data-reset-url="#reset-target" data-reset-confirm="Discard draft?" hidden>Reset form</button>
    <a id="round-form-cancel" href="#rounds">Cancel</a>
  </form>`;

function boot(html, data = new Map()) {
    const { window: win } = new JSDOM(html, { url: "https://jury.example/admin/rounds/edit" });
    Object.defineProperty(win, "sessionStorage", {
        value: {
            getItem: key => data.get(key) ?? null,
            setItem: (key, value) => data.set(key, value),
            removeItem: key => data.delete(key)
        }
    });
    init(win.document);
    const el = selector => win.document.querySelector(selector);
    const event = (target, type) => target.dispatchEvent(new win.Event(type, { bubbles: true, cancelable: true }));
    const change = (selector, value) => {
        el(selector).value = value;
        event(el(selector), "change");
    };
    return { win, el, event, change, warn: () => !event(win, "beforeunload"), data };
}

test("warns only when the editable values differ and clears the warning on a revert", () => {
    const page = boot(form('<input name="name" value="Original"><input readonly name="trackLabel" value="Main">'));
    expect(page.warn()).toBe(false);
    page.change('[name="trackLabel"]', "Read only");
    expect(page.warn()).toBe(false);
    page.change('[name="name"]', "Draft");
    expect(page.warn()).toBe(true);
    expect(page.data.get(slot)).toContain("Draft");
    expect(page.data.get(slot)).not.toMatch(/csrf-fresh|submit-fresh|Read only/);
    page.change('[name="name"]', "Original");
    expect(page.warn()).toBe(false);
    expect(page.data.has(slot)).toBe(false);
});

test("reset asks before discarding the restored draft and clears only that form", () => {
    const fields = '<input name="name" value="Original">';
    const otherSlot = "wlxjury:round-draft:" + otherKey;
    const data = new Map([[otherSlot, "Other draft"]]);
    const original = boot(form(fields), data);
    expect(original.el("#round-form-reset").disabled).toBe(true);
    original.change('[name="name"]', "Unsaved");
    const page = boot(form(fields), data);
    const reset = page.el("#round-form-reset");
    expect(reset.hidden).toBe(false);
    expect(reset.disabled).toBe(false);
    page.win.confirm = jest.fn().mockReturnValueOnce(false).mockReturnValueOnce(true);
    reset.click();
    expect(page.win.confirm).toHaveBeenCalledWith("Discard draft?");
    expect(page.win.location.hash).toBe("");
    expect(page.warn()).toBe(true);
    expect(data.get(slot)).toContain("Unsaved");
    reset.click();
    expect(page.win.location.hash).toBe("#reset-target");
    expect(page.warn()).toBe(false);
    expect(data.has(slot)).toBe(false);
    expect(data.get(otherSlot)).toBe("Other draft");
    const reopened = boot(form(fields), data);
    expect(reopened.el('[name="name"]').value).toBe("Original");
    expect(reopened.el("#round-form-reset").disabled).toBe(true);
});

test("a refused form offers reset even without a local draft", () => {
    const page = boot(form('<input name="name" value="Refused">', true));
    const reset = page.el("#round-form-reset");
    expect(reset.hidden).toBe(false);
    expect(reset.disabled).toBe(false);
});

test("restores the sources, dynamic thresholds and cut before the preview loads", () => {
    const previous = '<select name="previousRound[]" multiple><option value="11">Earlier</option></select>';
    const initialCut = '<div id="top-n"><div id="top-n-box"></div></div>';
    const liveCut = `<div id="top-n"><div id="top-n-box">
       <select name="topImages"><option value="">All</option><option value="4">Top 4</option></select>
       <input type="radio" name="cutKeep" value="threshold" checked></div></div>`;
    const options = '<select name="rejectionFilter"><option value="none">No filter</option>';
    const data = new Map();
    const first = boot(form(previous + options + '<option value="only:2">Only 2</option></select>' + liveCut), data);
    first.change('[name="previousRound[]"]', "11");
    first.change('[name="rejectionFilter"]', "only:2");
    first.change('[name="topImages"]', "4");
    const reload = boot(form(previous + options + '</select>' + initialCut), data);
    const posted = new reload.win.FormData(reload.el("form"));
    expect(posted.getAll("previousRound[]")).toEqual(["11"]);
    expect(posted.get("rejectionFilter")).toBe("only:2");
    expect(posted.get("topImages")).toBe("4");
    expect(posted.get("cutKeep")).toBe("threshold");
    expect(reload.el("#round-draft-restored").hidden).toBe(false);
    expect(reload.warn()).toBe(true);
});

test("leaves the posted values on a refused save and keeps the draft through reload", () => {
    const stored = '<input name="name" value="Saved name">';
    const page = boot(form(stored));
    page.change('[name="name"]', "Draft with trailing space ");
    page.event(page.el("form"), "submit");
    expect(page.warn()).toBe(false);
    expect(page.data.has(slot)).toBe(true);
    const refused = boot(form('<input name="name" value="Draft with trailing space">', true), page.data);
    expect(refused.el('[name="name"]').value).toBe("Draft with trailing space");
    expect(refused.warn()).toBe(true);
    const reload = boot(form(stored), page.data);
    expect(reload.el('[name="name"]').value).toBe("Draft with trailing space");
});

test("a preview-delayed or cancelled submit still warns", () => {
    const page = boot(form('<input name="name">'));
    page.change('[name="name"]', "Draft");
    page.el("form").addEventListener("submit", e => e.preventDefault());
    page.event(page.el("form"), "submit");
    expect(page.warn()).toBe(true);
});

test("Cancel discards from its own tab, and a save receipt clears just its own key", () => {
    const data = new Map([["wlxjury:round-draft:" + otherKey, "other draft"]]);
    const fields = '<input name="name" value="">';
    const page = boot(form(fields), data);
    page.change('[name="name"]', "Draft");
    page.el("#round-form-cancel").dispatchEvent(new page.win.MouseEvent("click", { bubbles: true, ctrlKey: true }));
    expect(data.has(slot)).toBe(true);
    page.el("#round-form-cancel").click();
    expect(data.has(slot)).toBe(false);
    expect(page.warn()).toBe(false);
    const another = boot(form(fields), data);
    another.change('[name="name"]', "Saved later");
    expect(data.has(slot)).toBe(true);
    boot('<span hidden data-round-draft-saved="' + key + '"></span>', data);
    expect(data.has(slot)).toBe(false);
    expect(data.has("wlxjury:round-draft:" + otherKey)).toBe(true);
});
