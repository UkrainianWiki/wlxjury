/**
 * @jest-environment jsdom
 */
"use strict";

// The worst mark (rate -1, the icon bi-x-lg) of a rated round with the option (roadmap queue
// item 6): giving it and clearing a vote stay apart. On the gallery tiles and the juror's by-rate
// list (tile-votes.js): a worst-mark button after the stars sends rate -1 and is pressed for a
// file with it, its stars empty, the list's rate text then the icon; the cancel icon is an eraser
// that clears, shown only once the file has a vote. In the large view: Delete gives it and
// Backspace clears (large-keys.js), each a path; the stars lose raty's cancel icon where the
// worst-mark and clear buttons stand apart (large-stars.js), and the number's icon comes back
// after a hover (large-rate.js). On the round form (round-form.js): the checkbox only for a rated
// rating, and "rejected by" for a rated round with the worst mark.

const $ = require("jquery");
window.jQuery = window.$ = $;
require("../public/javascripts/jquery.raty.js");
require("../public/javascripts/jquery.hotkeys.js");
window.wlxLargeRate = require("../public/javascripts/large-rate.js");
const { voteErrorMessage, voteErrorTexts } = require("../public/javascripts/vote-errors.js");
window.voteErrorMessage = voteErrorMessage;
window.voteErrorTexts = voteErrorTexts;
const { addStars, addListStars } = require("../public/javascripts/tile-votes.js");
const keys = require("../public/javascripts/large-keys.js");
window.wlxLargeView = { vote: jest.fn() };
const largeStars = require("../public/javascripts/large-stars.js");

let requests; // the votes sent: { options, answer (a Deferred) }

beforeEach(() => {
    requests = [];
    jest.spyOn($, "ajax").mockImplementation(options => {
        const answer = $.Deferred();
        requests.push({ options, answer });
        return answer;
    });
    jest.spyOn(window, "alert").mockImplementation(() => {});
});

afterEach(() => {
    jest.restoreAllMocks();
    $(document).off();
    window.wlxLargeView.vote.mockClear();
});

const TEXTS = 'data-csrf-token="token-1" data-vote-closed="Closed." data-vote-worst="Worst mark" ' +
    'data-vote-clear="Clear the vote"';

/** A tile's stars with the worst mark, for a file stored with `rate`. */
function tileStars(rate, worst = true) {
    document.body.innerHTML = `<div ${TEXTS}><div class="ug-tile" data-rate="${rate}"></div></div>`;
    const tile = document.querySelector(".ug-tile");
    const item = { roundId: "2", pageId: "101", rate: String(rate) };
    const el = addStars(tile, item, { number: 5, half: false, worst });
    return { el, item, tile };
}

const drawn = el => Array.from(el.querySelectorAll("i.wlx-star")).map(s =>
    s.classList.contains("bi-star-fill") ? "*" : ".").join("");
const worstButton = el => el.querySelector(".wlx-tile-worst");
const clear = el => el.querySelector("i.raty-cancel");

describe("a tile's worst mark", () => {
    test("is a labelled toggle button after the stars, the cancel icon an eraser, none without the option", () => {
        const { el } = tileStars(3);
        const button = worstButton(el);
        expect(button.getAttribute("role")).toBe("button");
        expect(button.getAttribute("tabindex")).toBe("0");
        expect(button.getAttribute("aria-label")).toBe("Worst mark");
        expect(button.getAttribute("aria-pressed")).toBe("false");
        expect(clear(el).className).toBe("raty-cancel bi bi-eraser");
        expect(clear(el).title).toBe("Clear the vote");
        expect(el.style.getPropertyValue("--wlx-tile-stars")).toBe("7");
        const plain = tileStars(3, false).el;
        expect(worstButton(plain)).toBeNull();
        expect(clear(plain).className).toBe("raty-cancel bi bi-x-circle");
    });

    test("shows a stored worst mark: no stars, the button pressed, the clear there", () => {
        const { el } = tileStars(-1);
        expect(drawn(el)).toBe(".....");
        expect(worstButton(el).getAttribute("aria-pressed")).toBe("true");
        expect(worstButton(el).classList.contains("wlx-tile-worst-on")).toBe(true);
        expect(clear(el).hidden).toBe(false);
    });

    test("hides the clear while the file is unrated", () => {
        expect(clear(tileStars(0).el).hidden).toBe(true);
    });

    test("gives the worst mark by a click, a path with the CSRF header, never clearing it on a second click", () => {
        const { el, item, tile } = tileStars(4);
        worstButton(el).click();
        expect(requests[0].options).toEqual({
            type: "POST", url: "/rate/round/2/pageid/101/select/-1", headers: { "Csrf-Token": "token-1" }
        });
        expect(drawn(el)).toBe(".....");
        requests[0].answer.resolve("success");
        expect(item.rate).toBe(-1);
        expect(tile.getAttribute("data-rate")).toBe("-1");
        worstButton(el).click();
        expect(requests[1].options.url).toBe("/rate/round/2/pageid/101/select/-1");
    });

    test("gives the worst mark by Enter or Space, the key not reaching the tile", () => {
        const { el, tile } = tileStars(0);
        const opened = jest.fn();
        tile.addEventListener("keydown", opened);
        const space = new KeyboardEvent("keydown", { key: " ", bubbles: true, cancelable: true });
        worstButton(el).dispatchEvent(space);
        expect(space.defaultPrevented).toBe(true);
        expect(opened).not.toHaveBeenCalled();
        expect(requests[0].options.url).toBe("/rate/round/2/pageid/101/select/-1");
    });

    test("clears a worst mark with the eraser, which then hides", () => {
        const { el, item } = tileStars(-1);
        $(clear(el)).trigger("click");
        expect(requests[0].options.url).toBe("/rate/round/2/pageid/101/select/0");
        requests[0].answer.resolve("success");
        expect(item.rate).toBe(0);
        expect(worstButton(el).getAttribute("aria-pressed")).toBe("false");
        expect(clear(el).hidden).toBe(true);
    });

    test("a star after a worst mark unpresses its button", () => {
        const { el } = tileStars(-1);
        $(el).find("i.wlx-star").eq(2).trigger("click");
        expect(requests[0].options.url).toBe("/rate/round/2/pageid/101/select/3");
        expect(worstButton(el).getAttribute("aria-pressed")).toBe("false");
        expect(drawn(el)).toBe("***..");
    });

    test("shows the stored vote again if the worst mark fails, and says why", () => {
        const { el, item } = tileStars(2);
        worstButton(el).click();
        requests[0].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        expect(item.rate).toBe("2");
        expect(drawn(el)).toBe("**...");
        expect(worstButton(el).getAttribute("aria-pressed")).toBe("false");
        expect(window.alert).toHaveBeenCalled();
    });

    test("sends no second vote while one is pending", () => {
        const { el } = tileStars(2);
        worstButton(el).click();
        worstButton(el).click();
        expect(requests).toHaveLength(1);
    });
});

describe("the juror's by-rate list with the worst mark", () => {
    function list(rate) {
        document.body.innerHTML =
            `<div class="row" ${TEXTS} data-stars-number="5" data-stars-half="false" data-stars-worst="true" ` +
            'data-changed-title="Rate changed"><ul class="gallery"><li class="gallerybox" id="201">' +
            '<div class="rank-line"><b>1.</b><span class="rate-stars"><span class="wlx-byrate-stars" ' +
            `data-round-id="2" data-page-id="201" data-rate="${rate}"></span>` +
            `<span class="wlx-rate-text">${rate}</span></span></div></li></ul></div>`;
        addListStars(document.querySelector("[data-stars-number]"));
        return document.querySelector("li.gallerybox");
    }

    test("gives the file's stars a worst-mark button, and writes a stored one as its icon", () => {
        const box = list(3);
        const el = box.querySelector(".wlx-tile-stars");
        worstButton(el).click();
        requests[0].answer.resolve("success");
        const text = box.querySelector(".wlx-rate-text");
        expect(text.textContent).toBe("");
        expect(text.querySelector("i").className).toBe("bi bi-x-lg wlx-worst-mark");
        expect(text.querySelector("i").getAttribute("aria-hidden")).toBe("true");
        expect(box.classList.contains("wlx-rate-changed")).toBe(true);
    });
});

describe("the large view's keys with the worst mark", () => {
    test("Delete gives the worst mark and Backspace clears, each by its path", () => {
        document.body.innerHTML = '<div id="large-keys" data-vote-key-0="/large/round/1/pageid/7/select/0" ' +
            'data-vote-key-del="/large/round/1/pageid/7/select/-1" data-vote-key-backspace="/large/round/1/pageid/7/select/0"></div>';
        expect(keys.bind(document)).toEqual(["del", "backspace", "0"]);
        const key = code => {
            const e = new KeyboardEvent("keydown", { keyCode: code, which: code, bubbles: true, cancelable: true });
            document.body.dispatchEvent(e);
            return e;
        };
        expect(key(46).defaultPrevented).toBe(true);
        expect(window.wlxLargeView.vote).toHaveBeenLastCalledWith("/large/round/1/pageid/7/select/-1");
        expect(key(8).defaultPrevented).toBe(true);
        expect(window.wlxLargeView.vote).toHaveBeenLastCalledWith("/large/round/1/pageid/7/select/0");
        window.wlxLargeView.vote.mock.calls.forEach(([url]) => expect(url.startsWith("/")).toBe(true));
    });

    test("a round without it binds no Backspace, which stays the browser's", () => {
        document.body.innerHTML = '<div id="large-keys" data-vote-key-0="/large/round/1/pageid/7/select/0"></div>';
        expect(keys.bind(document)).toEqual(["0"]);
        const e = new KeyboardEvent("keydown", { keyCode: 8, which: 8, bubbles: true, cancelable: true });
        document.body.dispatchEvent(e);
        expect(e.defaultPrevented).toBe(false);
    });
});

describe("the large view's stars with the worst mark", () => {
    const el = attrs => {
        document.body.innerHTML = '<span id="rate-number"><i class="bi bi-x-lg wlx-worst-mark" aria-hidden="true"></i></span>' +
            `<div id="rate-image" class="large-stars" ${attrs}></div>`;
        return document.getElementById("rate-image");
    };

    test("have no cancel icon of their own, a round without it keeping raty's", () => {
        const base = 'data-number="5" data-half="false" data-score="0" data-rate-number="rate-number" ' +
            'data-readonly="false" data-vote-prefix="/large/round/1/pageid/7/select/" data-vote-query=""';
        expect(largeStars.options(el(base + ' data-cancel="false"'), document).cancel).toBe(false);
        expect(largeStars.options(el(base), document).cancel).toBe(true);
    });

    test("show the worst mark's icon again in the number after a hover", () => {
        el("");
        const shown = document.getElementById("rate-number");
        const hover = window.wlxLargeRate.number(shown, false);
        hover.mouseover(4);
        expect(shown.textContent).toBe("4");
        expect(shown.querySelector("i")).toBeNull();
        hover.mouseout();
        expect(shown.textContent).toBe("");
        expect(shown.querySelector("i.bi-x-lg.wlx-worst-mark")).not.toBeNull();
    });
});

describe("the round form's worst mark", () => {
    // `lockedX`: a read-only page's X select (disabled, its value in a hidden input)
    function form(rates, lockedX = false) {
        window.TomSelect = require("tom-select/dist/js/tom-select.complete.js");
        window.wlxMultiSelect = require("../public/javascripts/multi-select").multiSelect;
        const { init } = require("../public/javascripts/round-form");
        document.body.innerHTML = `
          <form id="round-form">
            <select id="previousRound" name="previousRound[]" multiple>
              <option value="1">Round 1 (binary)</option><option value="2">Round 2 (rated)</option>
              <option value="3">Round 3 (rated, criteria)</option>
            </select>
            <div id="prevRoundFilters" style="display: none">
              <div id="selectFilters" class="round1">select</div>
              <div id="rejectFilters" class="round1 round2">
                <select id="rejectedMode" name="rejectedMode"><option value="none">No filter</option><option value="only">Only</option></select>
                <select id="rejectedBy" name="rejectedBy"${lockedX ? " disabled" : ""}><option value="">-</option><option value="2">2</option></select>
                ${lockedX ? '<input type="hidden" name="rejectedBy" value="2">' : ""}
              </div>
              <div id="rateFilters">rate</div>
            </div>
            <select id="rates" name="rates"><option value="1">+/-</option><option value="0">Comments</option>
              <option value="5">1-5</option></select>
            <div id="round-criteria"><select name="criteria[0]"><option value=""></option><option value="7">Aesthetics</option></select></div>
            <div id="round-worst-mark"><input type="hidden" name="worstMarkShown" value="true">
              <input type="checkbox" id="worstMark" name="worstMark" value="true"></div>
            <select id="regions" name="regions[]" multiple></select>
            <select id="jurors" name="jurors[]" multiple></select>
          </form>`;
        document.getElementById("rates").value = rates;
        init(document);
    }
    const shown = id => document.getElementById(id).style.display !== "none";
    const box = () => document.getElementById("round-worst-mark");

    test("is shown and sent only for a rated rating", () => {
        form("5");
        expect(box().hidden).toBe(false);
        expect(Array.from(box().querySelectorAll("input")).map(i => i.disabled)).toEqual([false, false]);
        const rates = document.getElementById("rates");
        rates.value = "1";
        rates.dispatchEvent(new Event("change"));
        expect(box().hidden).toBe(true);
        expect(Array.from(box().querySelectorAll("input")).map(i => i.disabled)).toEqual([true, true]);
    });

    test("is hidden and not sent once a criterion is chosen, shown again without one", () => {
        form("5");
        const criterion = document.querySelector("#round-criteria select");
        criterion.value = "7";
        criterion.dispatchEvent(new Event("change"));
        expect(box().hidden).toBe(true);
        expect(Array.from(box().querySelectorAll("input")).map(i => i.disabled)).toEqual([true, true]);
        criterion.value = "";
        criterion.dispatchEvent(new Event("change"));
        expect(box().hidden).toBe(false);
    });

    test("shows \"rejected by\" for a binary round and a rated one without criteria, not a criteria round", () => {
        form("5");
        const prev = document.getElementById("previousRound").tomselect;
        prev.addItem("2");
        expect([shown("rejectFilters"), shown("rateFilters"), shown("selectFilters")]).toEqual([true, true, false]);
        prev.removeItem("2");
        prev.addItem("3");
        expect([shown("rejectFilters"), shown("rateFilters")]).toEqual([false, true]);
        prev.removeItem("3");
        prev.addItem("1");
        expect([shown("rejectFilters"), shown("selectFilters")]).toEqual([true, true]);
    });

    test("sends \"rejected by\"'s fields only while a chosen previous round may reject", () => {
        form("5");
        const fields = () => ["rejectedMode", "rejectedBy"].map(id => document.getElementById(id).disabled);
        expect(fields()).toEqual([true, true]); // none chosen as the page loads
        const prev = document.getElementById("previousRound").tomselect;
        prev.addItem("3");
        expect(fields()).toEqual([true, true]);
        prev.addItem("2");
        expect(fields()).toEqual([false, false]);
        prev.clear();
        expect(fields()).toEqual([true, true]);
    });

    test("keeps a read-only page's select disabled, its hidden value sent with a rejecting round", () => {
        form("5", true);
        const hidden = () => document.querySelector('#rejectFilters input[type=hidden]');
        expect(hidden().disabled).toBe(true);
        document.getElementById("previousRound").tomselect.addItem("1");
        expect(document.getElementById("rejectedBy").disabled).toBe(true);
        expect(hidden().disabled).toBe(false);
        expect(document.getElementById("rejectedMode").disabled).toBe(false);
    });
});
