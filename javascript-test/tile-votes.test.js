/**
 * @jest-environment jsdom
 */
"use strict";

// The stars on a rated round's gallery tiles (tile-votes.js, raty as the pages load it): drawn
// from the file's stored rate, a click voting by script (the vote URL, the rate in stored
// units, the CSRF header) and showing the new stars at once; a failed vote showing the
// last stored stars again and explained (vote-errors.js); a click while a vote is pending
// sending nothing; a click on them never reaching the tile, which opens the large view. Which galleries get stars at all: views.GalleryTileStarsViewSpec.
// A binary round's tiles: their frames and pressed thumbs from the stored vote at load, and
// after a vote. The juror's own by-rate list: the same stars per file in place of the static
// ones, a vote updating the rate text in place and marking the file changed (its place and
// rank kept until a reload), the hint above the list shown from the first change on.

const $ = require("jquery");
window.jQuery = window.$ = $;
require("../public/javascripts/jquery.raty.js");
require("../public/javascripts/large-rate.js");
const { voteErrorMessage, voteErrorTexts } = require("../public/javascripts/vote-errors.js");
window.voteErrorMessage = voteErrorMessage;
window.voteErrorTexts = voteErrorTexts;
const { addStars, addVote, vote, addListStars, addTally, addVoteTime, addVoteMark } = require("../public/javascripts/tile-votes.js");

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

    test("ignore a second click while a vote is pending, showing the pending stars", () => {
        const { el, item } = stars(1);
        star(el, 3).trigger("click");
        expect(el.getAttribute("aria-busy")).toBe("true");
        star(el, 5).trigger("click");
        expect(requests).toHaveLength(1);
        expect(drawn(el)).toBe("***..");

        requests[0].answer.resolve("success");
        expect(el.hasAttribute("aria-busy")).toBe(false);
        expect(item.rate).toBe(3);
        star(el, 5).trigger("click");
        expect(requests).toHaveLength(2);
        expect(requests[1].options.url).toBe("/rate/round/2/pageid/101/select/5");
    });

    test("show the last stored stars again if a later vote fails, not those at load", () => {
        const { el, item } = stars(1);
        star(el, 3).trigger("click");
        requests[0].answer.resolve("success");
        star(el, 5).trigger("click");
        requests[1].answer.reject({ status: 500 });
        $(el).trigger("mouseleave");
        expect(drawn(el)).toBe("***..");
        expect(item.rate).toBe(3);
        expect(el.hasAttribute("aria-busy")).toBe(false);
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

    test("makes its thumbs focusable", () => {
        binaryTile(0);
        expect(tile().querySelector(".ug-icon-select").getAttribute("tabindex")).toBe("0");
        expect(tile().querySelector(".ug-icon-reject").getAttribute("tabindex")).toBe("0");
    });

    test.each([["Enter", ".ug-icon-select", 0, 1], [" ", ".ug-icon-reject", 0, -1], ["Enter", ".ug-icon-select", 1, 0]])(
        "votes with %j on a thumb, as a click does, without opening the tile", (key, thumb, stored, rate) => {
            const opened = jest.fn();
            $(document.body).on("click keydown", ".ug-tile", opened); // as unitegallery delegates its clicks
            const item = binaryTile(stored);
            const press = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
            tile().querySelector(thumb).dispatchEvent(press);
            expect(press.defaultPrevented).toBe(true);
            expect(requests).toHaveLength(1);
            expect(requests[0].options.url).toBe("/rate/round/1/pageid/101/select/" + rate);
            requests[0].answer.resolve("success");
            expect(item.rate).toBe(rate);
            expect(opened).not.toHaveBeenCalled();
        });

    test("ignores other keys on a thumb", () => {
        binaryTile(0);
        ["Tab", "a", "ArrowDown", "Escape"].forEach(key => {
            const press = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
            tile().querySelector(".ug-icon-select").dispatchEvent(press);
            expect(press.defaultPrevented).toBe(false);
        });
        expect(requests).toHaveLength(0);
    });

    test("sends no second vote while one is pending", () => {
        const item = binaryTile(0);
        expect(vote(tile(), item, 1)).not.toBeNull();
        expect(tile().getAttribute("aria-busy")).toBe("true");
        expect(vote(tile(), item, -1)).toBeNull();
        expect(requests).toHaveLength(1);
        requests[0].answer.resolve("success");
        expect(tile().hasAttribute("aria-busy")).toBe(false);
        expect(shown()).toEqual(["selected", "true", "false"]);
        vote(tile(), item, -1);
        expect(requests).toHaveLength(2);
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

/** The by-rate list as galleryByRate renders it for the juror: files rated `rates`, ranked in
 * that order, each with its static stars, and the hidden hint above the list. In an order by
 * vote time (`times`: each file's vote time, "" for a rated file without one), the list's
 * data-vote-times and a rated file's time line under its rate. */
function byRateList(rates, options = { number: 5, half: false }, times = null) {
    const timeLine = (rate, i) => {
        if (!times || !rate) return "";
        const at = times[i];
        return '<div class="wlx-vote-time-line small">' + (at
            ? '<time class="wlx-vote-time" datetime="' + at + '">' + at + '</time>'
            : '<span class="wlx-vote-time-none text-body-secondary">Time not recorded</span>') + '</div>';
    };
    const files = rates.map((rate, i) =>
        '<li class="gallerybox" id="' + (201 + i) + '"><div class="rank-line"> <b>' + (i + 1) + '.</b>' +
        '<span class="rate-stars"><span class="wlx-byrate-stars" data-round-id="2" data-page-id="' + (201 + i) +
        '" data-rate="' + rate + '"><i class="bi bi-star wlx-star"></i></span>' +
        '<span class="wlx-rate-text">' + rate + '</span></span></div>' + timeLine(rate, i) + '</li>').join("");
    document.body.innerHTML =
        '<p class="wlx-byrate-hint" hidden>Changed rates are re-sorted when the page is reloaded</p>' +
        '<div class="row" data-csrf-token="token-1" data-vote-closed="Closed." data-stars-number="' + options.number +
        '" data-stars-half="' + options.half + '" data-changed-title="Rate changed"' +
        (times ? ' data-vote-times="true"' : '') + '>' +
        '<ul class="gallery">' + files + '</ul></div>';
    addListStars(document.querySelector("[data-stars-number]"));
    return Array.from(document.querySelectorAll("li.gallerybox"));
}

const hint = () => document.querySelector(".wlx-byrate-hint");
const listStars = box => box.querySelector(".wlx-tile-stars");
const rateText = box => box.querySelector(".wlx-rate-text").textContent;
const order = () => Array.from(document.querySelectorAll("li.gallerybox"))
    .map(b => b.id + ":" + b.querySelector(".rank-line b").textContent);

describe("the juror's by-rate list", () => {

    test("puts stars from each file's stored rate in place of the static ones", () => {
        const boxes = byRateList([5, 3, 0]);
        expect(boxes.map(b => drawn(listStars(b)))).toEqual(["*****", "***..", "....."]);
        boxes.forEach(b => expect(b.querySelectorAll(".wlx-byrate-stars > i.wlx-star")).toHaveLength(0));
        expect(hint().hidden).toBe(true);
        boxes.forEach(b => expect(b.classList.contains("wlx-rate-changed")).toBe(false));
    });

    test("shows a stored vote in the rate text and marks the file changed, keeping its place and rank", () => {
        const boxes = byRateList([5, 3, 1]);
        star(listStars(boxes[2]), 4).trigger("click");
        expect(requests[0].options).toEqual({
            type: "POST", url: "/rate/round/2/pageid/203/select/4", headers: { "Csrf-Token": "token-1" }
        });
        // shown at once in the stars, the text and the mark only once stored
        expect(drawn(listStars(boxes[2]))).toBe("****.");
        expect(rateText(boxes[2])).toBe("1");
        expect(hint().hidden).toBe(true);

        requests[0].answer.resolve("success");
        expect(rateText(boxes[2])).toBe("4");
        expect(boxes[2].classList.contains("wlx-rate-changed")).toBe(true);
        expect(boxes[2].getAttribute("title")).toBe("Rate changed");
        expect(hint().hidden).toBe(false);
        expect(order()).toEqual(["201:1.", "202:2.", "203:3."]);
        [boxes[0], boxes[1]].forEach(b => {
            expect(b.classList.contains("wlx-rate-changed")).toBe(false);
            expect(b.hasAttribute("title")).toBe(false);
        });
    });

    test("unrates with the cancel icon: the text 0", () => {
        const boxes = byRateList([3]);
        $(listStars(boxes[0])).find("i.raty-cancel").trigger("click");
        expect(requests[0].options.url).toBe("/rate/round/2/pageid/201/select/0");
        requests[0].answer.resolve("success");
        expect(rateText(boxes[0])).toBe("0");
        expect(boxes[0].classList.contains("wlx-rate-changed")).toBe(true);
    });

    test("shows a half-star round's rate text in stored units: star 4's left half gives 7", () => {
        const boxes = byRateList([10], { number: 5, half: true });
        expect(drawn(listStars(boxes[0]))).toBe("*****");
        clickHalf(listStars(boxes[0]), 4, "left");
        requests[0].answer.resolve("success");
        expect(rateText(boxes[0])).toBe("7");
    });

    test("drops the mark when the file is back at its rate at load, keeping the hint", () => {
        const boxes = byRateList([3]);
        star(listStars(boxes[0]), 5).trigger("click");
        requests[0].answer.resolve("success");
        star(listStars(boxes[0]), 3).trigger("click");
        requests[1].answer.resolve("success");
        expect(rateText(boxes[0])).toBe("3");
        expect(boxes[0].classList.contains("wlx-rate-changed")).toBe(false);
        expect(boxes[0].hasAttribute("title")).toBe(false);
        expect(hint().hidden).toBe(false);
    });

    test("updates the text once per stored vote, a click while one is pending sending nothing", () => {
        const boxes = byRateList([3]);
        const el = listStars(boxes[0]);
        star(el, 5).trigger("click");
        star(el, 1).trigger("click");
        expect(requests).toHaveLength(1);
        requests[0].answer.resolve("success");
        expect(rateText(boxes[0])).toBe("5");
        star(el, 1).trigger("click");
        requests[1].answer.reject({ status: 500 });
        $(el).trigger("mouseleave");
        expect(rateText(boxes[0])).toBe("5");
        expect(drawn(el)).toBe("*****");
        expect(boxes[0].classList.contains("wlx-rate-changed")).toBe(true);
    });

    test("keeps the text, the mark and the stars if a vote fails, and says why", () => {
        const boxes = byRateList([3]);
        star(listStars(boxes[0]), 5).trigger("click");
        requests[0].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        $(listStars(boxes[0])).trigger("mouseleave");
        expect(drawn(listStars(boxes[0]))).toBe("***..");
        expect(rateText(boxes[0])).toBe("3");
        expect(boxes[0].classList.contains("wlx-rate-changed")).toBe(false);
        expect(hint().hidden).toBe(true);
        expect(window.alert).toHaveBeenCalledWith("Closed.");
    });
});

// The juror's by-rate list in an order by vote time (data-vote-times): a stored vote's time follows
// it under the file's rate as on the gallery's tiles, without a reload (listVoteTimeAfter): now for
// a changed vote, added for a file unrated at load, gone when cleared, kept for the same vote and on
// a failed one; nothing in another order.
describe("the juror's by-rate list's vote times", () => {
    afterEach(() => { delete window.wlxVoteTime; });

    const line = box => box.querySelector(".wlx-vote-time-line");
    const timeOf = box => line(box) && line(box).querySelector("time.wlx-vote-time");

    test("show now after a changed vote, formatted, in place of the time at load", () => {
        window.wlxVoteTime = { format: (iso, lang, zone, now, year) => `formatted ${iso}${year ? " year" : ""}` };
        const boxes = byRateList([3, 2], undefined, ["2026-01-02T03:04:05.678Z", "2026-01-03T03:04:05.678Z"]);
        const start = Date.now();
        star(listStars(boxes[0]), 5).trigger("click");
        // not before it is stored
        expect(timeOf(boxes[0]).getAttribute("datetime")).toBe("2026-01-02T03:04:05.678Z");
        requests[0].answer.resolve("success");
        const iso = timeOf(boxes[0]).getAttribute("datetime");
        expect(Date.parse(iso)).toBeGreaterThanOrEqual(start - 1000);
        expect(timeOf(boxes[0]).textContent).toBe("formatted " + iso);
        expect(timeOf(boxes[0]).title).toBe("formatted " + iso + " year");
        expect(boxes[0].querySelectorAll(".wlx-vote-time-line")).toHaveLength(1);
        // the other file's stays
        expect(timeOf(boxes[1]).getAttribute("datetime")).toBe("2026-01-03T03:04:05.678Z");
    });

    test("add the time for a file unrated at load, after its rate, and replace 'not recorded'", () => {
        const boxes = byRateList([0, 4], undefined, ["", ""]);
        expect(line(boxes[0])).toBeNull();
        star(listStars(boxes[0]), 2).trigger("click");
        requests[0].answer.resolve("success");
        expect(timeOf(boxes[0])).not.toBeNull();
        expect(boxes[0].lastElementChild).toBe(line(boxes[0]));
        expect(line(boxes[0]).classList.contains("small")).toBe(true);

        expect(line(boxes[1]).textContent).toBe("Time not recorded");
        star(listStars(boxes[1]), 1).trigger("click");
        requests[1].answer.resolve("success");
        expect(timeOf(boxes[1])).not.toBeNull();
        expect(line(boxes[1]).querySelector(".wlx-vote-time-none")).toBeNull();
    });

    test("go when the vote is cleared, and come back with the next vote", () => {
        const boxes = byRateList([3], undefined, ["2026-01-02T03:04:05.678Z"]);
        $(listStars(boxes[0])).find("i.raty-cancel").trigger("click");
        requests[0].answer.resolve("success");
        expect(line(boxes[0])).toBeNull();
        star(listStars(boxes[0]), 4).trigger("click");
        requests[1].answer.resolve("success");
        expect(timeOf(boxes[0])).not.toBeNull();
    });

    test("stay for the same vote stored again, and on a failed vote", () => {
        const boxes = byRateList([3], undefined, ["2026-01-02T03:04:05.678Z"]);
        star(listStars(boxes[0]), 3).trigger("click");
        requests[0].answer.resolve("success");
        expect(timeOf(boxes[0]).getAttribute("datetime")).toBe("2026-01-02T03:04:05.678Z");
        star(listStars(boxes[0]), 5).trigger("click");
        requests[1].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        expect(timeOf(boxes[0]).getAttribute("datetime")).toBe("2026-01-02T03:04:05.678Z");
    });

    test("are not shown in another order", () => {
        const boxes = byRateList([0, 3]);
        star(listStars(boxes[0]), 2).trigger("click");
        requests[0].answer.resolve("success");
        star(listStars(boxes[1]), 5).trigger("click");
        requests[1].answer.resolve("success");
        boxes.forEach(b => expect(line(b)).toBeNull());
    });
});

// All the jurors' votes on a binary round's tiles in the organisers' gallery (addTally): a check
// and a cross with their numbers, read by screen readers as the page's text, none without it.
describe("a tile's approvals and rejections", () => {
    test("show a check and a cross with their numbers, the text for screen readers", () => {
        const badge = addTally(tile(), { approvals: "3", rejections: "2", tally: "3 approvals, 2 rejections" });
        expect(tile().querySelector(".wlx-tile-tally")).toBe(badge);
        const shown = badge.querySelector("[aria-hidden=true]");
        expect(shown.textContent.trim().split(/\s+/)).toEqual(["3", "2"]);
        expect(shown.querySelector("i.bi-check-lg.wlx-vote-selected")).not.toBeNull();
        expect(shown.querySelector("i.bi-x-lg.wlx-vote-rejected")).not.toBeNull();
        expect(badge.querySelector(".visually-hidden").textContent).toBe("3 approvals, 2 rejections");
        expect(badge.title).toBe("3 approvals, 2 rejections");
    });

    test("add nothing to a tile without them, as a juror's own", () => {
        expect(addTally(tile(), { rate: "1" })).toBeNull();
        expect(tile().querySelector(".wlx-tile-tally")).toBeNull();
    });
});

// The time of the juror's vote on a tile in an order by vote time (addVoteTime): a <time> with the
// UTC value, formatted by vote-time.js where loaded; the text for a vote without a time; nothing
// for an unrated file or a tile without the attribute (any other order). After a stored vote in
// the tile (voteTimeAfter): a changed vote's time is now, a cleared vote's badge goes, the same
// vote again keeps its time.
describe("a tile's vote time", () => {
    afterEach(() => { delete window.wlxVoteTime; });

    const badge = () => tile().querySelector(".wlx-tile-time");

    test("shows the time formatted, the UTC value as its datetime", () => {
        window.wlxVoteTime = { format: (iso, lang, zone, now, year) => `formatted ${iso} ${lang || ""}${year ? " year" : ""}`.trim() };
        document.documentElement.setAttribute("lang", "uk");
        const shown = addVoteTime(tile(), { rate: "1", votedAt: "2026-11-12T14:05:00Z" });
        const time = shown.querySelector("time.wlx-vote-time");
        expect(badge()).toBe(shown);
        expect(time.getAttribute("datetime")).toBe("2026-11-12T14:05:00Z");
        expect(time.textContent).toBe("formatted 2026-11-12T14:05:00Z uk");
        expect(time.title).toBe("formatted 2026-11-12T14:05:00Z uk year");
    });

    test("keeps the UTC value without the formatter", () => {
        const shown = addVoteTime(tile(), { rate: "3", votedAt: "2026-11-12T14:05:00Z" });
        expect(shown.querySelector("time").textContent).toBe("2026-11-12T14:05:00Z");
        expect(shown.querySelector("time").title).toBe("");
    });

    test("says no time was recorded for a vote without one", () => {
        const shown = addVoteTime(tile(), { rate: "-1", votedAt: "", votedNone: "Time not recorded" });
        expect(shown.querySelector("time")).toBeNull();
        expect(shown.classList.contains("wlx-vote-time-none")).toBe(true);
        expect(shown.textContent).toBe("Time not recorded");
    });

    test("adds nothing for an unrated file, or in another order", () => {
        expect(addVoteTime(tile(), { rate: "0", votedAt: "" })).toBeNull();
        expect(addVoteTime(tile(), { rate: "1" })).toBeNull();
        expect(badge()).toBeNull();
    });

    test("follows a binary round's votes in the tile: now for a new or changed vote, gone when cleared", () => {
        const item = binaryTile(0);
        item.votedAt = "";
        addVoteTime(tile(), item);
        expect(badge()).toBeNull();

        const start = Date.now();
        vote(tile(), item, 1);
        expect(badge()).toBeNull(); // not before it is stored
        requests[0].answer.resolve("success");
        const first = badge().querySelector("time").getAttribute("datetime");
        expect(Date.parse(first)).toBeGreaterThanOrEqual(start - 1000);
        expect(item.votedAt).toBe(first);

        vote(tile(), item, 0);
        requests[1].answer.resolve("success");
        expect(badge()).toBeNull();
        expect(item.votedAt).toBe("");
    });

    test("keeps the time when the same vote is stored again, and on a failed vote", () => {
        const item = binaryTile(1);
        item.votedAt = "2026-01-02T03:04:05.678Z";
        addVoteTime(tile(), item);
        vote(tile(), item, 1);
        requests[0].answer.resolve("success");
        expect(badge().querySelector("time").getAttribute("datetime")).toBe("2026-01-02T03:04:05.678Z");
        vote(tile(), item, -1);
        requests[1].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        expect(badge().querySelector("time").getAttribute("datetime")).toBe("2026-01-02T03:04:05.678Z");
    });

    test("replaces 'not recorded' with now after a rated round's star vote", () => {
        const { el, item } = stars(2);
        item.votedAt = "";
        item.votedNone = "Time not recorded";
        addVoteTime(tile(), item);
        expect(badge().textContent).toBe("Time not recorded");
        star(el, 4).trigger("click");
        requests[0].answer.resolve("success");
        expect(badge().querySelector("time")).not.toBeNull();
        expect(badge().classList.contains("wlx-vote-time-none")).toBe(false);
    });

    test("does nothing in another order", () => {
        const item = binaryTile(0);
        vote(tile(), item, 1);
        requests[0].answer.resolve("success");
        expect(badge()).toBeNull();
        expect(item.votedAt).toBeUndefined();
    });
});

// One juror's vote on a binary round's tile as an organiser views it (addVoteMark): read-only,
// no thumbs to click; the frame and a thumb named by the page; nothing for an unrated file.
describe("a tile's read-only vote", () => {
    beforeEach(() => {
        const holder = document.body.firstElementChild;
        holder.setAttribute("data-vote-selected", "Selected");
        holder.setAttribute("data-vote-rejected", "Rejected");
    });

    test.each([[1, "wlx-tile-selected", "bi-hand-thumbs-up-fill", "Selected"],
               [-1, "wlx-tile-rejected", "bi-hand-thumbs-down-fill", "Rejected"]])(
        "a binary round's %i: frame %s, its thumb and name", (rate, frame, icon, name) => {
            const mark = addVoteMark(tile(), { rate: String(rate) }, { binary: true });
            expect(tile().classList.contains(frame)).toBe(true);
            expect(mark.querySelector("i." + icon)).not.toBeNull();
            expect(mark.querySelector(".visually-hidden").textContent).toBe(name);
            expect(mark.title).toBe(name);
            // nothing to vote with
            expect(tile().querySelectorAll("[role=button], .ug-tile-icon, a, button")).toHaveLength(0);
        });

    test("an unrated file in a binary round: no frame, no mark", () => {
        expect(addVoteMark(tile(), { rate: "0" }, { binary: true })).toBeNull();
        expect(tile().className).toBe("ug-tile");
    });

    test("none in a rated round (its by-rate list shows the stars)", () => {
        expect(addVoteMark(tile(), { rate: "4" }, { binary: false })).toBeNull();
        expect(tile().className).toBe("ug-tile");
    });
});
