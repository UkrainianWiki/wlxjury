/**
 * @jest-environment jsdom
 */
"use strict";

// The round form's multiple selects (multi-select.js) on Tom Select, the build the pages load
// (tom-select.complete): bootstrap-multiselect's summary texts, "select all", and the original
// <select> kept as the form's field, with its change event for the page's own handlers
// (previous rounds -> filters, jurors -> distribution, in round-form.js).

const path = require("path");

const SCRIPT = path.join(__dirname, "..", "public", "javascripts", "multi-select.js");
const TEXTS = { selectAll: "Select all", none: "None selected", n: "Selected", all: "All selected" };

let summaryText, multiSelect;

beforeAll(() => {
    window.TomSelect = require("tom-select/dist/js/tom-select.complete.js");
    ({ summaryText, multiSelect } = require(SCRIPT));
});

function jurors(selected = [], disabled = false) {
    const options = ["Ann", "Bob", "Cid", "Dan", "Eve"].map((name, i) =>
        `<option value="${i + 1}"${selected.includes(i + 1) ? " selected" : ""}>${name}</option>`).join("");
    document.body.innerHTML = `<form><select id="jurors" name="jurors[]" multiple${disabled ? " disabled" : ""}>${options}</select></form>`;
    return document.getElementById("jurors");
}

function summary() {
    return document.querySelector(".wlx-multi-summary").textContent;
}

function selectedValues(select) {
    return Array.from(select.selectedOptions).map(o => o.value).sort();
}

describe("summaryText", () => {
    test("as bootstrap-multiselect's button: none, up to three names, a count, all", () => {
        expect(summaryText([], 5, TEXTS)).toBe("None selected");
        expect(summaryText(["Ann", "Bob", "Cid"], 5, TEXTS)).toBe("Ann, Bob, Cid");
        expect(summaryText(["Ann", "Bob", "Cid", "Dan"], 5, TEXTS)).toBe("4 Selected");
        expect(summaryText(["Ann", "Bob", "Cid", "Dan", "Eve"], 5, TEXTS)).toBe("All selected (5)");
        expect(summaryText(["Ann"], 1, TEXTS)).toBe("Ann");
    });
});

describe("multiSelect", () => {
    test("sums up the selection the page comes with", () => {
        multiSelect(jurors([2, 4]), TEXTS);
        expect(summary()).toBe("Bob, Dan");
    });

    test("selecting changes the original select and fires its change event", () => {
        const select = jurors();
        const changes = [];
        select.addEventListener("change", () => changes.push(selectedValues(select)));
        const ts = multiSelect(select, TEXTS);
        expect(summary()).toBe("None selected");

        ts.addItem("1");
        ts.addItem("3");

        expect(selectedValues(select)).toEqual(["1", "3"]);
        expect(changes[changes.length - 1]).toEqual(["1", "3"]);
        expect(summary()).toBe("Ann, Cid");
    });

    test("jQuery's change handlers, as the page's, see the new value", () => {
        const $ = require("jquery");
        const select = jurors();
        let seen = null;
        $(select).change(function () { seen = $(this).val(); });
        const ts = multiSelect(select, TEXTS);

        ts.addItem("5");

        expect(seen).toEqual(["5"]);
    });

    test("select all selects every option, and again none", () => {
        const select = jurors([1]);
        multiSelect(select, TEXTS);
        const all = document.querySelector(".wlx-select-all input");
        expect(document.querySelector(".wlx-select-all").textContent.trim()).toBe("Select all");

        all.checked = true;
        all.dispatchEvent(new Event("change"));
        expect(selectedValues(select)).toEqual(["1", "2", "3", "4", "5"]);
        expect(summary()).toBe("All selected (5)");

        all.checked = false;
        all.dispatchEvent(new Event("change"));
        expect(selectedValues(select)).toEqual([]);
        expect(summary()).toBe("None selected");
    });

    test("a disabled select stays disabled and submits nothing", () => {
        const select = jurors([2], true);
        const ts = multiSelect(select, TEXTS);
        expect(ts.isDisabled).toBe(true);
        expect(select.disabled).toBe(true);
        expect(summary()).toBe("Bob");
    });

    test("a name is text, never markup", () => {
        document.body.innerHTML = `<select id="r" multiple><option value="1" selected>&lt;b&gt;x&lt;/b&gt;</option><option value="2">y</option></select>`;
        multiSelect(document.getElementById("r"), TEXTS);
        expect(summary()).toBe("<b>x</b>");
        expect(document.querySelector(".wlx-multi-summary b")).toBeNull();
    });

    test("does nothing without Tom Select or without the select", () => {
        expect(multiSelect(null, TEXTS)).toBeNull();
        const ts = window.TomSelect;
        delete window.TomSelect;
        try { expect(multiSelect(jurors(), TEXTS)).toBeNull(); } finally { window.TomSelect = ts; }
    });
});

describe("initAll: the selects with data-multi-select (monuments.scala.html)", () => {
    const { initAll } = require(SCRIPT);

    test("starts each with its attribute's texts, and clears it when its form is reset", () => {
        document.body.innerHTML = `<form id="f"><select id="regions-select" multiple data-multi-select='${JSON.stringify(TEXTS)}'>` +
            `<option value="07" selected>Volyn</option><option value="12">Dnipro</option></select>` +
            `<select id="plain" multiple><option>x</option></select></form>`;
        const started = initAll(document);
        expect(started).toHaveLength(1);
        expect(summary()).toBe("Volyn");
        document.getElementById("f").dispatchEvent(new Event("reset"));
        expect(started[0].items).toEqual([]);
        expect(summary()).toBe("None selected");
        expect(document.getElementById("plain").tomselect).toBeUndefined();
    });

    test("leaves a select with broken texts a plain select", () => {
        document.body.innerHTML = `<select multiple data-multi-select="{broken"><option>x</option></select>`;
        expect(initAll(document)).toHaveLength(0);
    });
});
