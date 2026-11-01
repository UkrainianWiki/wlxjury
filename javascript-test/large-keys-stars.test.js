/**
 * @jest-environment jsdom
 */
"use strict";

// large-keys.js and large-stars.js on their own: which keys an element's attributes bind, and
// raty's options from a stars element's. large-view-keys.test.js runs both on the rendered
// large views.

const $ = require("jquery");
window.jQuery = $;
require("../public/javascripts/jquery.hotkeys.js");
const keys = require("../public/javascripts/large-keys");
window.wlxLargeRate = require("../public/javascripts/large-rate");
window.wlxLargeView = { vote: jest.fn() };
const stars = require("../public/javascripts/large-stars");

afterEach(() => {
    $(document).off();
    window.wlxLargeView.vote.mockClear();
});

describe("large-keys bind", () => {
    test("binds the moves and votes the element has, nothing else", () => {
        document.body.innerHTML = '<div id="large-keys" hidden data-key-down="/large/2" data-key-esc="/gallery#1" ' +
            'data-vote-key-space="/large/round/1/pageid/1/select/1"></div>';
        expect(keys.bind(document)).toEqual(["down", "esc", "space"]);
    });

    test("a vote key posts its vote", () => {
        document.body.innerHTML = '<div id="large-keys" data-vote-key-3="/large/round/1/pageid/1/select/3"></div>';
        keys.bind(document);
        document.body.dispatchEvent(new KeyboardEvent("keydown", { keyCode: 51, which: 51, bubbles: true, cancelable: true }));
        expect(window.wlxLargeView.vote).toHaveBeenCalledWith("/large/round/1/pageid/1/select/3");
    });

    test("a read-only view binds no vote, so Space and Del are the browser's", () => {
        document.body.innerHTML = '<div id="large-keys" data-key-esc="/gallery"></div>';
        expect(keys.bind(document)).toEqual(["esc"]);
        const space = new KeyboardEvent("keydown", { keyCode: 32, which: 32, bubbles: true, cancelable: true });
        document.body.dispatchEvent(space);
        expect(space.defaultPrevented).toBe(false);
        expect(window.wlxLargeView.vote).not.toHaveBeenCalled();
    });

    test("binds nothing without the element", () => {
        document.body.innerHTML = "";
        expect(keys.bind(document)).toEqual([]);
    });
});

describe("large-stars options", () => {
    const el = attrs => {
        document.body.innerHTML = '<span id="rate-number">3.5</span>' +
            `<div id="rate-image" class="large-stars" ${attrs}></div>`;
        return document.getElementById("rate-image");
    };

    test("a half-star round's stars vote the rate in points, with the prefix and query", () => {
        const o = stars.options(el('data-number="5" data-half="true" data-score="3.5" data-rate-number="rate-number" ' +
            'data-readonly="false" data-vote-prefix="/large/round/1/pageid/7/select/" data-vote-query="?sort=rate"'), document);
        expect(o).toMatchObject({ number: 5, half: true, score: 3.5, cancel: true, precision: false, space: false });
        expect(o.hints).toEqual(["", "", "", "", ""]);
        expect(o.readOnly).toBeUndefined();
        o.click(3.7); // over star 4's right half: 4 stars, 8 points
        expect(window.wlxLargeView.vote).toHaveBeenCalledWith("/large/round/1/pageid/7/select/8?sort=rate");
        o.click(null); // the cancel icon
        expect(window.wlxLargeView.vote).toHaveBeenLastCalledWith("/large/round/1/pageid/7/select/0?sort=rate");
    });

    test("a read-only view's stars vote nothing", () => {
        const o = stars.options(el('data-number="10" data-half="false" data-score="4.0" data-rate-number="rate-number" ' +
            'data-readonly="true"'), document);
        expect(o).toMatchObject({ number: 10, half: false, score: 4, readOnly: true });
        expect(o.click).toBeUndefined();
        expect(o.mouseover).toBeUndefined();
    });

    test("init draws only the elements with data-number (a criterion without a rate has none)", () => {
        require("../public/javascripts/jquery.raty.js");
        document.body.innerHTML = '<span id="rate-number1">–</span>' +
            '<div id="rate-image1" class="large-stars" data-number="5" data-half="false" data-score="0" ' +
            'data-rate-number="rate-number1" data-readonly="true"></div><div id="rate-image2" class="large-stars"></div>';
        expect(stars.init(document)).toBe(1);
        expect(document.querySelectorAll("#rate-image1 i.wlx-star")).toHaveLength(5);
        expect(document.querySelectorAll("#rate-image2 i")).toHaveLength(0);
    });
});
