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
        <select id="excludeRounds" name="excludeRounds[]" multiple>
          <option value="1">Round 1 (binary)</option><option value="2">Round 2 (rated)</option>
        </select>
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
    test("makes its four multiple selects Tom Select dropdowns, with the texts", () => {
        page();
        expect(document.querySelectorAll(".wlx-multi")).toHaveLength(4);
        expect(document.querySelector("#jurors").tomselect).toBeDefined();
        // the rounds to leave out (V95): a dropdown with "Select all", sending its choice as the select's
        const exclude = document.querySelector("#excludeRounds").tomselect;
        expect(exclude).toBeDefined();
        expect(exclude.dropdown.querySelector(".wlx-select-all .form-check-label").textContent).toBe("Select all");
        exclude.addItem("2");
        expect($("#excludeRounds").val()).toEqual(["2"]);
        // choosing rounds to leave out shows none of the previous rounds' filters
        expect(shown("prevRoundFilters")).toBe(false);
        expect(document.querySelector(".wlx-multi-summary").textContent).toBe("None selected");
    });

    test("leaves a locked multiple select as rendered: no Tom Select, still hidden and disabled", () => {
        document.body.innerHTML = `
          <form id="round-form">
            <select id="previousRound" name="previousRound[]" multiple disabled hidden data-wlx-locked>
              <option value="1" selected>Round 1 (binary)</option><option value="2">Round 2 (rated)</option>
            </select>
            <div id="previousRound_locked" class="form-control wlx-locked wlx-locked-values"><ul class="wlx-locked-list"><li>Round 1 (binary)</li></ul></div>
            <select id="regions" name="regions[]" multiple disabled hidden data-wlx-locked><option value="07">Volyn</option></select>
          </form>`;
        init(document);
        const prev = document.getElementById("previousRound");
        expect(document.querySelectorAll(".wlx-multi")).toHaveLength(0);
        expect(prev.tomselect).toBeUndefined();
        expect([prev.hidden, prev.disabled]).toEqual([true, true]);
        expect($("#previousRound").val()).toEqual(["1"]);
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

describe("a new round's criteria", () => {
    function criteriaPage(ratesDisabled = false) {
        document.body.innerHTML = `
          <form id="round-form">
            <select id="rates" name="rates"${ratesDisabled ? " disabled" : ""}>
              <option value="0">Comments only</option><option value="1">Yes/No</option><option value="10">1-10</option>
            </select>
            <div id="round-criteria">
              <select name="criteria[0]"><option value=""></option><option value="6" selected>Technical quality</option></select>
              <select name="criteria[1]"><option value=""></option><option value="7" selected>Usefulness</option></select>
            </div>
          </form>`;
    }
    const box = () => document.getElementById("round-criteria");
    const disabled = () => Array.from(box().querySelectorAll("select"), s => s.disabled);
    const choose = value => {
        const rates = document.getElementById("rates");
        rates.value = value;
        rates.dispatchEvent(new Event("change"));
    };

    test("are shown and sent for a rated rating only", () => {
        criteriaPage();
        document.getElementById("rates").value = "1";
        init(document);
        expect([box().hidden, disabled()]).toEqual([true, [true, true]]);
        choose("10");
        expect([box().hidden, disabled()]).toEqual([false, [false, false]]);
        expect(new FormData(document.getElementById("round-form")).getAll("criteria[0]")).toEqual(["6"]);
        choose("0");
        expect([box().hidden, disabled()]).toEqual([true, [true, true]]);
        expect(new FormData(document.getElementById("round-form")).has("criteria[0]")).toBe(false);
    });

    test("stay as they are in an existing round, whose rating is fixed", () => {
        criteriaPage(true);
        document.getElementById("rates").value = "1";
        init(document);
        expect([box().hidden, disabled()]).toEqual([false, [false, false]]);
    });
});

describe("the score rule's warning", () => {
    function rulePage(stored = "rated") {
        document.body.innerHTML = `
          <form id="round-form">
            <select id="averageRule" name="averageRule" aria-describedby="averageRule_help averageRule_warning">
              <option value="rated"${stored === "rated" ? " selected" : ""}>Rated</option>
              <option value="assigned"${stored === "assigned" ? " selected" : ""}>Assigned</option>
              <option value="sum">Sum</option>
            </select>
            <div id="averageRule_warning_row" data-stored-rule="${stored}">
              <div id="averageRule_warning">Changing the score rule re-scores this round.</div>
            </div>
          </form>`;
    }
    const row = () => document.getElementById("averageRule_warning_row");
    const choose = value => {
        const select = document.getElementById("averageRule");
        select.value = value;
        select.dispatchEvent(new Event("change"));
    };

    test("is hidden while the select shows the saved rule, and shown once it differs", () => {
        rulePage("assigned");
        init(document);
        expect(row().hidden).toBe(true);
        choose("sum");
        expect(row().hidden).toBe(false);
        choose("rated");
        expect(row().hidden).toBe(false);
        choose("assigned");
        expect(row().hidden).toBe(true);
    });

    test("leaves a page without the warning alone", () => {
        document.body.innerHTML = `<select id="averageRule"><option value="rated">Rated</option></select>`;
        expect(() => init(document)).not.toThrow();
    });
});

// The criteria selects start small (the maintainer's decisions of 2026-11-19): the server renders
// them all (a page without scripts), the script shows only the chosen ones, each with a remove
// button, and "Add criteria" / "Add another criterion" up to the selects' number.
describe("the criteria selects", () => {
    const OPTIONS = ["", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"];

    /** The form's criteria section: `values` by position, `count` selects (10, or a legacy round's
      * more). */
    function slotsPage(values = [], { count = 10 } = {}) {
        const selects = Array.from({ length: count }, (_, i) => `
              <div class="row mb-3" id="criteria_${i}_field">
                <label class="col-md-4 col-form-label" for="criteria_${i}">Criterion ${i + 1}</label>
                <div class="col-md-8">
                  <select id="criteria_${i}" name="criteria[${i}]" class="form-select">
                    ${OPTIONS.map(v => `<option value="${v}"${(values[i] || "") === v ? " selected" : ""}>${v}</option>`).join("")}
                  </select>
                </div>
              </div>`).join("");
        document.body.innerHTML = `
          <form id="round-form">
            <select id="rates" name="rates"><option value="1">Yes/No</option><option value="10" selected>1-10</option></select>
            <div id="round-worst-mark"><input type="checkbox" id="worstMark" name="worstMark" value="true"></div>
            <div id="round-criteria">
              <div id="round-criteria-selects" data-remove-label="Remove criterion $X">${selects}</div>
              <div class="row mb-3" id="criteria-add-row" hidden>
                <div class="offset-md-4 col-md-8">
                  <button type="button" class="btn btn-outline-secondary btn-sm" id="criteria-add"
                    data-text-first="Add criteria" data-text-more="Add another criterion">Add criteria</button>
                </div>
              </div>
            </div>
          </form>`;
        init(document);
    }

    const add = () => document.getElementById("criteria-add");
    const addShown = () => !document.getElementById("criteria-add-row").hidden;
    const selects = () => Array.from(document.querySelectorAll("#round-criteria-selects select"));
    const shownCount = () => selects().filter(s => !document.getElementById(`${s.id}_field`).hidden).length;
    const values = () => selects().map(s => s.value);
    const removeButton = i => document.querySelector(`#criteria_${i}_field .wlx-criterion-remove`);
    const sent = () => new FormData(document.getElementById("round-form"));

    test("with none chosen show only \"Add criteria\", which shows one select and focuses it", () => {
        slotsPage();
        expect(shownCount()).toBe(0);
        expect([addShown(), add().textContent]).toEqual([true, "Add criteria"]);
        add().click();
        expect(shownCount()).toBe(1);
        expect(document.activeElement).toBe(selects()[0]);
        expect(add().textContent).toBe("Add another criterion");
        // the worst mark stays for a round without criteria until one is chosen
        expect(document.getElementById("round-worst-mark").hidden).toBe(false);
    });

    test("with some chosen show those, each with a labelled remove button, and \"Add another criterion\"", () => {
        slotsPage(["6", "7"]);
        expect(shownCount()).toBe(2);
        expect([addShown(), add().textContent]).toEqual([true, "Add another criterion"]);
        const button = removeButton(1);
        expect(button.tagName).toBe("BUTTON");
        expect(button.type).toBe("button");
        expect(button.getAttribute("aria-label")).toBe("Remove criterion 2");
        expect(button.querySelector("[aria-hidden=true]").textContent).toBe("\u00d7");
        // the remove button beside its select
        expect(button.parentNode).toBe(selects()[1].parentNode);
        expect(document.querySelectorAll(".wlx-criterion-remove")).toHaveLength(10);
        expect(document.getElementById("round-worst-mark").hidden).toBe(true);
        // all ten still sent (enabled), the hidden ones empty (jsdom's FormData lists every option
        // of a select whose selected option is empty, so the values are read)
        expect(selects().every(s => !s.disabled)).toBe(true);
        expect(values().slice(0, 3)).toEqual(["6", "7", ""]);
    });

    test("add selects up to the ten, then hide the add button", () => {
        slotsPage(["1"]);
        for (let i = 2; i <= 10; i++) {
            add().click();
            expect(shownCount()).toBe(i);
            expect(document.activeElement).toBe(selects()[i - 1]);
        }
        expect(addShown()).toBe(false);
        add().click(); // a stray click past the last does nothing
        expect(shownCount()).toBe(10);
    });

    test("a legacy round's eleven criteria are all shown, nothing to add", () => {
        slotsPage(OPTIONS.slice(1), { count: 11 });
        expect(shownCount()).toBe(11);
        expect(addShown()).toBe(false);
    });

    test("a removal moves the next criteria up, focusing the select now in its place, else the one before", () => {
        slotsPage(["6", "7", "8"]);
        removeButton(1).click();
        expect(values().slice(0, 4)).toEqual(["6", "8", "", ""]);
        expect(shownCount()).toBe(2);
        expect(document.activeElement).toBe(selects()[1]);
        expect(addShown()).toBe(true);
        // the last shown: the one before gets the focus
        removeButton(1).click();
        expect(values().slice(0, 2)).toEqual(["6", ""]);
        expect(shownCount()).toBe(1);
        expect(document.activeElement).toBe(selects()[0]);
    });

    test("removing the last one returns to \"Add criteria\", focused, and the worst mark comes back", () => {
        slotsPage(["6"]);
        expect(document.getElementById("round-worst-mark").hidden).toBe(true);
        removeButton(0).click();
        expect(shownCount()).toBe(0);
        expect([addShown(), add().textContent]).toEqual([true, "Add criteria"]);
        expect(document.activeElement).toBe(add());
        expect(document.getElementById("round-worst-mark").hidden).toBe(false);
        expect(values()[0]).toBe("");
    });

    test("a page shown again shows the posted selects up to the last chosen one, gaps kept", () => {
        slotsPage(["6", "", "8"]);
        expect(shownCount()).toBe(3);
        expect(values().slice(0, 3)).toEqual(["6", "", "8"]);
    });

    test("a yes/no rating still hides and disables the whole section", () => {
        slotsPage(["6"]);
        const rates = document.getElementById("rates");
        rates.value = "1";
        rates.dispatchEvent(new Event("change"));
        expect(document.getElementById("round-criteria").hidden).toBe(true);
        expect(sent().has("criteria[0]")).toBe(false);
    });
});
