/**
 * @jest-environment jsdom
 * @jest-environment-options {"url": "https://jury.example/large/round/1/user/1/pageid/107"}
 */
"use strict";

// The large views' collapsible side sections (large-sections.js): the file's details, open by
// default, and the files after it, closed; each one's state kept per viewer in localStorage
// and applied on the next view, as the page renders them when storage fails. The column is the
// one the app renders (javascript-test/fixtures/large, kept in step by
// views.LargeViewFixturesSpec); Bootstrap's own collapse opens and closes the sections.

const fs = require("fs");
const path = require("path");

const bootstrap = require("bootstrap/dist/js/bootstrap.bundle.js");
const { PREFIX, restore } = require("../public/javascripts/large-sections.js");

/** The side column of a rendered large view, without its scripts. */
function column(name) {
    const html = fs.readFileSync(path.join(__dirname, "fixtures", "large", name), "utf8");
    const doc = new DOMParser().parseFromString(html, "text/html");
    const side = doc.querySelector(".large-side");
    side.querySelectorAll("script").forEach(s => s.remove());
    return side.outerHTML;
}

const toggle = id => document.querySelector(`[aria-controls=${id}]`);
const panel = id => document.getElementById(id);

/** Whether section `id` is open, checking that its toggle says the same. */
function isOpen(id) {
    const open = panel(id).classList.contains("show");
    expect(toggle(id).getAttribute("aria-expanded")).toBe(String(open));
    expect(toggle(id).classList.contains("collapsed")).toBe(!open);
    return open;
}

function load(name = "rated-own.html") {
    document.body.innerHTML = column(name);
    restore(document);
}

beforeEach(() => {
    localStorage.clear();
});

afterEach(() => {
    jest.restoreAllMocks();
});

describe.each(["rated-own.html", "contest-file.html"])("the side sections of %s", name => {

    test("are the details, open, then the following files, closed, with nothing stored", () => {
        load(name);
        expect(isOpen("large-info")).toBe(true);
        expect(isOpen("large-next")).toBe(false);
        expect(toggle("large-info").tagName).toBe("BUTTON");
        expect(localStorage.length).toBe(0);
    });

    test("show as stored by an earlier view", () => {
        localStorage.setItem(PREFIX + "large-info", "closed");
        localStorage.setItem(PREFIX + "large-next", "open");
        load(name);
        expect(isOpen("large-info")).toBe(false);
        expect(isOpen("large-next")).toBe(true);
    });
});

describe("a section's state", () => {

    test("is kept when Bootstrap opens or closes it, from the toggle", () => {
        load();
        toggle("large-next").click();
        expect(localStorage.getItem(PREFIX + "large-next")).toBe("open");
        toggle("large-info").click();
        expect(localStorage.getItem(PREFIX + "large-info")).toBe("closed");

        load(); // the next view
        expect(isOpen("large-next")).toBe(true);
        expect(isOpen("large-info")).toBe(false);
    });

    test("is the default for a stored value it doesn't know", () => {
        localStorage.setItem(PREFIX + "large-info", "yes");
        localStorage.setItem(PREFIX + "large-next", "");
        load();
        expect(isOpen("large-info")).toBe(true);
        expect(isOpen("large-next")).toBe(false);
    });

    test("is the default when storage can't be read, and changes still work without it", () => {
        jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("SecurityError"); });
        jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("QuotaExceededError"); });
        expect(() => load()).not.toThrow();
        expect(isOpen("large-info")).toBe(true);
        expect(isOpen("large-next")).toBe(false);
        expect(() => bootstrap.Collapse.getOrCreateInstance(panel("large-next")).show()).not.toThrow();
        expect(panel("large-next").classList.contains("show") || panel("large-next").classList.contains("collapsing")).toBe(true);
    });

    test("is the default when localStorage itself throws", () => {
        jest.spyOn(window, "localStorage", "get").mockImplementation(() => { throw new Error("SecurityError"); });
        expect(() => load()).not.toThrow();
        expect(isOpen("large-info")).toBe(true);
        expect(isOpen("large-next")).toBe(false);
    });

    test("isn't applied twice to a column already restored", () => {
        load();
        localStorage.setItem(PREFIX + "large-info", "closed");
        restore(document);
        expect(isOpen("large-info")).toBe(true);
    });
});

describe("a section's toggle", () => {

    test("keeps Space and Enter from the page's keys, not digits and arrows", () => {
        load();
        const reached = [];
        document.addEventListener("keydown", e => reached.push(e.keyCode));
        for (const keyCode of [32, 13, 49, 40]) {
            toggle("large-info").dispatchEvent(new KeyboardEvent("keydown", { keyCode, bubbles: true, cancelable: true }));
        }
        expect(reached).toEqual([49, 40]);
    });

    test("gives up its focus when clicked with the mouse, not from the keyboard", () => {
        load();
        const button = toggle("large-info");
        button.focus();
        button.dispatchEvent(new MouseEvent("click", { bubbles: true, detail: 0 }));
        expect(document.activeElement).toBe(button);
        button.dispatchEvent(new MouseEvent("click", { bubbles: true, detail: 1 }));
        expect(document.activeElement).not.toBe(button);
    });
});
