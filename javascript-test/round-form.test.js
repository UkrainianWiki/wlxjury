/**
 * @jest-environment jsdom
 */
"use strict";

// round-form.js: the round form's behaviour that mainAdmin.scala.html and editRound.scala.html
// held inline: the multiple selects, the previous rounds' filters, the distribution's choices,
// the conflicts of interest's confirmation, the lazy jurors' table and "Count new files".

const $ = require("jquery");
window.jQuery = $;
window.TomSelect = require("tom-select/dist/js/tom-select.complete.js");
window.wlxMultiSelect = require("../public/javascripts/multi-select").multiSelect;
const { init, texts } = require("../public/javascripts/round-form");

const TEXTS = {
    multiSelect: { selectAll: "Select all", none: "None selected", n: "selected", all: "All selected" },
    distribution: { all: "Each file to all jurors", some: "Each file to $X juror(s)" }
};

function page({ coiFiles = 0, texts = TEXTS } = {}) {
    document.body.innerHTML = `
      <script type="application/json" id="round-form-texts">${JSON.stringify(texts)}</script>
      <form id="round-form">
        <select id="previousRound" name="previousRound[]" multiple>
          <option value="1">Round 1 (binary)</option><option value="2">Round 2 (rated)</option>
        </select>
        <div id="prevRoundFilters" style="display: none">
          <div id="selectFilters" class="round1">select</div>
          <div id="rateFilters">rate</div>
        </div>
        <select id="regions" name="regions[]" multiple><option value="07">Volyn</option></select>
        <select id="jurors" name="jurors[]" multiple>
          <option value="11">Ann</option><option value="12">Bob</option><option value="13">Cid</option>
        </select>
        <select id="distribution" name="distribution" disabled><option value="0">Each file to all jurors</option></select>
        <input type="checkbox" id="excludeCoi" name="excludeCoi" data-coi-round-files="${coiFiles}" data-coi-confirm="Take 2 out?">
        <div id="collapseJurors"><div id="round-stat-table" data-src="/roundstat/5/table" data-error="Failed">…</div></div>
        <button type="button" id="count-new-files" data-src="/admin/rounds/5/new-files-count">Count</button>
        <span id="new-files-count"></span>
      </form>`;
    init(document);
}

const shown = id => document.getElementById(id).style.display !== "none";
const options = () => Array.from(document.getElementById("distribution").options, o => [o.value, o.text]);

afterEach(() => {
    jest.restoreAllMocks();
    $(document).off();
});

describe("texts", () => {
    test("are read from the JSON data block, and are empty without one", () => {
        page();
        expect(texts(document)).toEqual(TEXTS);
        document.body.innerHTML = "";
        expect(texts(document)).toEqual({});
    });
});

describe("the round form", () => {
    test("makes its three multiple selects Tom Select dropdowns, with the texts", () => {
        page();
        expect(document.querySelectorAll(".wlx-multi")).toHaveLength(3);
        expect(document.querySelector("#jurors").tomselect).toBeDefined();
        expect(document.querySelector(".wlx-multi-summary").textContent).toBe("None selected");
    });

    test("shows a binary previous round's selection filters, a rated one's rate filters, none without", () => {
        page();
        const prev = document.getElementById("previousRound").tomselect;
        prev.addItem("1");
        expect([shown("prevRoundFilters"), shown("selectFilters"), shown("rateFilters")]).toEqual([true, true, false]);
        prev.removeItem("1");
        prev.addItem("2");
        expect([shown("prevRoundFilters"), shown("selectFilters"), shown("rateFilters")]).toEqual([true, false, true]);
        prev.clear();
        expect(shown("prevRoundFilters")).toBe(false);
    });

    test("offers one distribution per number of jurors a file goes to, and keeps a choice that still fits", () => {
        page();
        const jurors = document.getElementById("jurors").tomselect;
        jurors.addItem("11");
        jurors.addItem("12");
        jurors.addItem("13");
        expect(document.getElementById("distribution").disabled).toBe(false);
        expect(options()).toEqual([["0", "Each file to all jurors"], ["1", "Each file to 1 juror(s)"], ["2", "Each file to 2 juror(s)"]]);
        $("#distribution").val("2");
        jurors.removeItem("13");
        expect(options()).toEqual([["0", "Each file to all jurors"], ["1", "Each file to 1 juror(s)"]]);
        $("#distribution").val("1");
        jurors.removeItem("12");
        expect(options()).toEqual([["0", "Each file to all jurors"]]);
        jurors.clear();
        expect(document.getElementById("distribution").disabled).toBe(true);
        expect(options()).toEqual([["0", "Each file to all jurors"]]);
    });

    test("asks before a save that takes conflicts of interest out of the round, and stops it on Cancel", () => {
        page({ coiFiles: 2 });
        const confirm = jest.spyOn(window, "confirm").mockReturnValue(false);
        const form = document.getElementById("round-form");
        document.getElementById("excludeCoi").checked = true;
        const submit = new Event("submit", { cancelable: true });
        form.dispatchEvent(submit);
        expect(confirm).toHaveBeenCalledWith("Take 2 out?");
        expect(submit.defaultPrevented).toBe(true);

        confirm.mockReturnValue(true);
        const again = new Event("submit", { cancelable: true });
        form.dispatchEvent(again);
        expect(again.defaultPrevented).toBe(false);
    });

    test("doesn't ask with the box unticked, or with no file to take out", () => {
        page({ coiFiles: 0 });
        const confirm = jest.spyOn(window, "confirm").mockReturnValue(false);
        document.getElementById("excludeCoi").checked = true;
        const submit = new Event("submit", { cancelable: true });
        document.getElementById("round-form").dispatchEvent(submit);
        expect(confirm).not.toHaveBeenCalled();
        expect(submit.defaultPrevented).toBe(false);
    });

    test("loads the jurors' table when its panel is first opened, and says so when denied", () => {
        page();
        const load = jest.spyOn($.fn, "load").mockImplementation(function (url, done) {
            done.call(this, "", "error", { status: 403 });
            return this;
        });
        $("#collapseJurors").trigger("show.bs.collapse");
        $("#collapseJurors").trigger("show.bs.collapse");
        expect(load).toHaveBeenCalledTimes(1);
        expect(load.mock.calls[0][0]).toBe("/roundstat/5/table");
        expect($("#round-stat-table").text()).toBe("Failed");
    });

    test("counts new files on request", () => {
        page();
        let result;
        jest.spyOn($, "getJSON").mockImplementation(url => {
            const d = $.Deferred();
            result = d;
            expect(url).toBe("/admin/rounds/5/new-files-count");
            return d.promise();
        });
        $("#count-new-files").trigger("click");
        expect($("#count-new-files").prop("disabled")).toBe(true);
        expect($("#new-files-count").text()).toBe("…");
        result.resolve({ count: 42 });
        expect($("#new-files-count").text()).toBe("42");
        expect($("#count-new-files").prop("disabled")).toBe(false);
    });
});
