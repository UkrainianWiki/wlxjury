/**
 * @jest-environment jsdom
 */
"use strict";

// The stars on a rated round's gallery tiles (tile-votes.js, raty as the pages load it): drawn
// from the file's stored rate, a click voting by script (the vote URL, the rate in stored
// units, the CSRF header) and showing the new stars at once; a failed vote showing the
// previous stars again and explained (vote-errors.js); a click on them never reaching the tile,
// which opens the large view. Which galleries get stars at all: views.GalleryTileStarsViewSpec.
// A binary round's tiles: their frames and pressed thumbs from the stored vote at load, and
// after a vote.

const $ = require("jquery");
window.jQuery = window.$ = $;
require("../public/javascripts/jquery.raty.js");
require("../public/javascripts/large-rate.js");
const { voteErrorMessage, voteErrorTexts } = require("../public/javascripts/vote-errors.js");
window.voteErrorMessage = voteErrorMessage;
window.voteErrorTexts = voteErrorTexts;
const { addStars, addVote, vote } = require("../public/javascripts/tile-votes.js");

let requests; // the votes sent: { options, answer (a Deferred) }

beforeEach(() => {
    document.body.innerHTML =
        '<div data-csrf-token="token-1" data-vote-closed="Closed."><div class="ug-tile" data-rate="0"></div></div>';
    requests = [];
    jest.spyOn($, "ajax").mockImplementation(options => {
        const answer = $.Deferred();
        requests.push({ options, answer });
        return answer;
    });
    jest.spyOn(window, "alert").mockImplementation(() => {});
});

afterEach(() => jest.restoreAllMocks());

const tile = () => document.querySelector(".ug-tile");

/** The tile's stars for a file stored with `rate`, as the theme adds them. */
function stars(rate, options = { number: 5, half: false }) {
    const item = { roundId: "2", pageId: "101", rate: String(rate) };
    tile().setAttribute("data-rate", String(rate));
    const el = addStars(tile(), item, options);
    return { el, item };
}

/** The stars drawn: "*" full, "/" half, "." empty. */
function drawn(el) {
    return Array.from(el.querySelectorAll("i.wlx-star")).map(s =>
        s.classList.contains("bi-star-fill") ? "*" : s.classList.contains("bi-star-half") ? "/" : ".").join("");
}

const star = (el, n) => $(el).find("i.wlx-star").eq(n - 1);

/** A half-star round's click on star `n`, in its left or right half (raty finds it by the
 * pointer's offset in the 1em-wide star: jsdom lays nothing out, so the font size is set). */
function clickHalf(el, n, half) {
    star(el, 1)[0].style.fontSize = "16px";
    star(el, n).trigger($.Event("mousemove", { pageX: half === "left" ? 1 : 10 }));
    star(el, n).trigger("click");
}

describe("a rated round's tile stars", () => {

    test("show the stored rate, with the large view's icons and no tooltips", () => {
        const { el } = stars(3);
        expect(drawn(el)).toBe("***..");
        expect(el.querySelector("i.raty-cancel").className).toBe("raty-cancel bi bi-x-circle");
        el.querySelectorAll("i").forEach(i => expect(i.title).toBe(""));
        expect(el.style.getPropertyValue("--wlx-tile-stars")).toBe("6");
    });

    test("show a half-star round's stored rate in half stars: 7 as 3.5 of 5", () => {
        expect(drawn(stars(7, { number: 5, half: true }).el)).toBe("***/.");
        expect(drawn(stars(0, { number: 5, half: true }).el)).toBe(".....");
    });

    test("vote the clicked star by script, with the page's CSRF token, and show it at once", () => {
        const { el, item } = stars(2);
        star(el, 4).trigger("click");

        expect(requests).toHaveLength(1);
        expect(requests[0].options).toEqual({
            type: "POST", url: "/rate/round/2/pageid/101/select/4", headers: { "Csrf-Token": "token-1" }
        });
        expect(drawn(el)).toBe("****.");

        requests[0].answer.resolve("success");
        expect(item.rate).toBe(4);
        expect(tile().getAttribute("data-rate")).toBe("4");
        expect(drawn(el)).toBe("****.");
        expect(window.alert).not.toHaveBeenCalled();
    });

    test.each([["left", 7, "***/."], ["right", 8, "****."]])(
        "vote a half star in its stored units: star 4's %s half gives %i", (half, rate, shown) => {
            const { el } = stars(0, { number: 5, half: true });
            clickHalf(el, 4, half);
            expect(requests[0].options.url).toBe("/rate/round/2/pageid/101/select/" + rate);
            $(el).trigger("mouseleave");
            expect(drawn(el)).toBe(shown);
        });

    test("unrate with the cancel icon", () => {
        const { el, item } = stars(3);
        $(el).find("i.raty-cancel").trigger("click");
        expect(requests[0].options.url).toBe("/rate/round/2/pageid/101/select/0");
        requests[0].answer.resolve("success");
        expect(item.rate).toBe(0);
        $(el).trigger("mouseleave");
        expect(drawn(el)).toBe(".....");
    });

    test("show the previous stars again if the vote fails, and say why", () => {
        const { el, item } = stars(3);
        star(el, 5).trigger("click");
        expect(drawn(el)).toBe("*****");

        requests[0].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        expect(drawn(el)).toBe("***..");
        $(el).trigger("mouseleave");
        expect(drawn(el)).toBe("***..");
        expect(item.rate).toBe("3");
        expect(window.alert).toHaveBeenCalledWith("Closed.");
    });

    test("show no stars again if a first vote fails", () => {
        const { el } = stars(0);
        star(el, 2).trigger("click");
        requests[0].answer.reject({ status: 500 });
        $(el).trigger("mouseleave");
        expect(drawn(el)).toBe(".....");
    });

    test("keep their clicks from the tile, which opens the large view", () => {
        const opened = jest.fn();
        $(document.body).on("click", ".ug-tile", opened); // as unitegallery delegates it
        const { el } = stars(1);
        star(el, 3).trigger("click");
        $(el).find("i.raty-cancel").trigger("click");
        $(el).trigger("click");
        expect(opened).not.toHaveBeenCalled();
        $(tile()).trigger("click");
        expect(opened).toHaveBeenCalledTimes(1);
    });
});

// a binary round's tile with its thumbs, as unitegallery draws it for a juror (in its overlay)
function binaryTile(rate) {
    document.body.firstElementChild.setAttribute("data-vote-selected", "Selected");
    document.body.firstElementChild.setAttribute("data-vote-rejected", "Rejected");
    tile().innerHTML = '<div class="ug-thumb-overlay">' +
        '<div class="ug-tile-icon ug-icon-select"></div><div class="ug-tile-icon ug-icon-reject"></div></div>';
    const item = { roundId: "1", pageId: "101", rate: String(rate) };
    addVote(tile(), item);
    return item;
}

/** The tile's frame ("selected", "rejected" or "none") and its thumbs pressed. */
function shown() {
    const t = tile();
    const frame = t.classList.contains("wlx-tile-selected") ? "selected"
        : t.classList.contains("wlx-tile-rejected") ? "rejected" : "none";
    expect(t.classList.contains("wlx-tile-selected") && t.classList.contains("wlx-tile-rejected")).toBe(false);
    return [frame, t.querySelector(".ug-icon-select").getAttribute("aria-pressed"),
        t.querySelector(".ug-icon-reject").getAttribute("aria-pressed")];
}

describe("a binary round's tile", () => {

    test.each([[1, "selected", "true", "false"], [-1, "rejected", "false", "true"], [0, "none", "false", "false"]])(
        "shows a stored %i at load: frame %s", (rate, frame, up, down) => {
            binaryTile(rate);
            expect(shown()).toEqual([frame, up, down]);
        });

    test("makes its thumbs toggle buttons, named by the page", () => {
        binaryTile(0);
        const up = tile().querySelector(".ug-icon-select");
        expect(up.getAttribute("role")).toBe("button");
        expect(up.getAttribute("aria-label")).toBe("Selected");
        expect(tile().querySelector(".ug-icon-reject").getAttribute("aria-label")).toBe("Rejected");
    });

    test.each([[1, "selected", "true", "false"], [-1, "rejected", "false", "true"], [0, "none", "false", "false"]])(
        "shows a vote of %i once stored: frame %s", (rate, frame, up, down) => {
            const item = binaryTile(rate === 0 ? 1 : 0);
            vote(tile(), item, rate);
            expect(requests[0].options.url).toBe("/rate/round/1/pageid/101/select/" + rate);
            expect(requests[0].options.headers).toEqual({ "Csrf-Token": "token-1" });
            requests[0].answer.resolve("success");
            expect(shown()).toEqual([frame, up, down]);
            expect(item.rate).toBe(rate);
        });

    test("keeps the stored vote shown if a vote fails, and says why", () => {
        const item = binaryTile(1);
        vote(tile(), item, 0); // thumbs up on a selected file: un-select
        requests[0].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        expect(shown()).toEqual(["selected", "true", "false"]);
        expect(item.rate).toBe("1");
        expect(window.alert).toHaveBeenCalledWith("Closed.");
    });
});
