/**
 * @jest-environment jsdom
 */
"use strict";

// The stars on a rated round's gallery tiles (tile-votes.js, raty as the pages load it): drawn
// from the file's stored rate, a click voting by script (the vote URL, the rate in stored
// units, the CSRF header) and showing the new stars at once; a failed vote showing the
// last stored stars again and explained (vote-errors.js); a click while a vote is pending
// sending nothing; a click on them never reaching the tile, which opens the large view. Which galleries get stars at all: views.GalleryTileStarsViewSpec.
// A binary round's tiles: their vote badge (✓ or ✗ at the lower left, no frame) and pressed
// buttons from the stored vote at load, and after a vote. The juror's own by-rate list: the same stars per file in place of the static
// ones, a vote updating the rate text in place and marking the file changed (its place and
// rank kept until a reload), the hint above the list shown from the first change on.

const $ = require("jquery");
window.jQuery = window.$ = $;
require("../public/javascripts/jquery.raty.js");
require("../public/javascripts/large-rate.js");
const { voteErrorMessage, voteErrorTexts } = require("../public/javascripts/vote-errors.js");
window.voteErrorMessage = voteErrorMessage;
window.voteErrorTexts = voteErrorTexts;
const { addStars, addVote, vote, addListStars, addTally, addVoteTime, addVoteMark, addComments } = require("../public/javascripts/tile-votes.js");

let requests; // the votes sent: { options, answer (a Deferred) }

// the gallery page's chip templates (snippets.chipTemplates), which the tiles' chips are cloned
// from, as the server renders them (views.ChipFixturesSpec writes the fixture)
const TEMPLATES = (require("fs").readFileSync(require("path").join(__dirname, "fixtures", "chips.html"), "utf8")
    .match(/<template[\s\S]*?<\/template>/g) || []).join("");

beforeEach(() => {
    document.body.innerHTML =
        '<div data-csrf-token="token-1" data-vote-closed="Closed.">' + TEMPLATES +
        '<div class="ug-tile" data-rate="0"></div></div>';
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

// a binary round's tile with its ✓ and ✗ buttons, as unitegallery draws it for a juror (in its overlay)
function binaryTile(rate) {
    document.body.firstElementChild.setAttribute("data-vote-selected", "Selected");
    document.body.firstElementChild.setAttribute("data-vote-rejected", "Rejected");
    tile().innerHTML = '<div class="ug-thumb-overlay">' +
        '<div class="ug-tile-icon ug-icon-select"></div><div class="ug-tile-icon ug-icon-reject"></div></div>';
    const item = { roundId: "1", pageId: "101", rate: String(rate) };
    addVote(tile(), item);
    return item;
}

/** The tile's state ("selected", "rejected" or "none": its state class, and its vote chip's icon
  * and name, one chip at most, first in the tile's chips) and its buttons pressed. */
function shown() {
    const t = tile();
    const state = t.classList.contains("wlx-tile-selected") ? "selected"
        : t.classList.contains("wlx-tile-rejected") ? "rejected" : "none";
    expect(t.classList.contains("wlx-tile-selected") && t.classList.contains("wlx-tile-rejected")).toBe(false);
    const marks = t.querySelectorAll(".wlx-chip-vote");
    expect(marks.length).toBeLessThanOrEqual(1);
    const shownIn = (m, icon) => m.querySelector("[aria-hidden=true] > i." + icon);
    const mark = marks.length === 0 ? "none"
        : shownIn(marks[0], "bi-check-lg.wlx-vote-selected") && marks[0].title === "Selected" ? "selected"
        : shownIn(marks[0], "bi-x-lg.wlx-vote-rejected") && marks[0].title === "Rejected" ? "rejected" : "?";
    expect(mark).toBe(state);
    if (marks.length) {
        expect(marks[0].parentNode.className).toBe("wlx-tile-chips");
        expect(marks[0].parentNode.firstElementChild).toBe(marks[0]);
        expect(marks[0].querySelector(".visually-hidden").textContent).toBe(marks[0].title);
    }
    return [state, t.querySelector(".ug-icon-select").getAttribute("aria-pressed"),
        t.querySelector(".ug-icon-reject").getAttribute("aria-pressed")];
}

describe("a binary round's tile", () => {

    test.each([[1, "selected", "true", "false"], [-1, "rejected", "false", "true"], [0, "none", "false", "false"]])(
        "shows a stored %i at load: %s", (rate, frame, up, down) => {
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
        "shows a vote of %i once stored: %s", (rate, frame, up, down) => {
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
        vote(tile(), item, 0); // ✓ on a selected file: un-select
        requests[0].answer.reject({ status: 409, responseJSON: { error: "round_closed" } });
        expect(shown()).toEqual(["selected", "true", "false"]);
        expect(item.rate).toBe("1");
        expect(window.alert).toHaveBeenCalledWith("Closed.");
    });
});

/** The by-rate list as galleryByRate renders it for the juror: files rated `rates`, ranked in
 * that order, each with its thumbnail (a link to its large view), its static stars, and the
 * hidden hint above the list. In an order by vote time (`times`: each file's vote time, "" for a
 * rated file without one), the list's data-vote-times and a rated file's vote chip on its
 * thumbnail (thumbChips: snippets.voteChip, its time alone), from the page's chip templates;
 * `comments` (index: count) a file's comment chip after it, a link to its large view's comments. */
function byRateList(rates, options = { number: 5, half: false }, times = null, comments = {}) {
    const voteChip = (rate, i) => {
        if (!times || !rate) return "";
        const at = times[i];
        const words = at || "Time not recorded";
        return '<span class="badge wlx-chip wlx-chip-vote" title="' + words + '"><span class="wlx-chip-shown" aria-hidden="true">' +
            (at ? '<span class="wlx-chip-time"><time class="wlx-vote-time" datetime="' + at + '">' + at + '</time></span>'
                : '<span class="wlx-chip-time wlx-vote-time-none">Time not recorded</span>') +
            '</span><span class="visually-hidden">' + words + '</span></span>';
    };
    const commentChip = i => !comments[i] ? "" :
        '<a class="badge wlx-chip wlx-chip-comments" href="/large/round/2/user/1/pageid/' + (201 + i) + '?sort=latest-vote#comments"' +
        ' title="' + comments[i] + ' comments: open the comments"><i class="bi bi-chat-left-text-fill" aria-hidden="true"></i>' +
        '<span class="wlx-chip-shown" aria-hidden="true">' + comments[i] + '</span><span class="visually-hidden">' +
        comments[i] + ' comments: open the comments</span></a>';
    const chips = (rate, i) => {
        const inner = voteChip(rate, i) + commentChip(i);
        // whitespace between the chips, as Twirl leaves it
        return inner ? '<div class="wlx-tile-foot"><div class="wlx-tile-chips">\n' + voteChip(rate, i) + '\n' + commentChip(i) +
            '\n</div></div>' : "";
    };
    const files = rates.map((rate, i) =>
        '<li class="gallerybox" id="' + (201 + i) + '"><div class="thumb"><a href="/large/round/2/user/1/pageid/' + (201 + i) +
        '"><img class="cropped" alt="File:Image ' + (201 + i) + '.jpg"></a></div>' +
        '<div class="rank-line wlx-byrate-line"><b>' + (i + 1) + '.</b>' +
        '<span class="rate-stars"><span class="wlx-byrate-stars" data-round-id="2" data-page-id="' + (201 + i) +
        '" data-rate="' + rate + '"><i class="bi bi-star wlx-star"></i></span>' +
        '<span class="wlx-rate-text">' + rate + '</span></span>' + chips(rate, i) + '</div></li>').join("");
    document.body.innerHTML =
        (times ? TEMPLATES : '') +
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
// it in the vote chip in the file's rank line under its thumbnail (galleryByRate rankChips), as on the gallery's tiles, without a reload
// (listVoteTimeAfter): now for a changed vote, a chip added for a file unrated at load, first in
// its chips, gone when cleared, kept for the same vote and on a failed one; nothing in another
// order. The comment chip stays after it.
describe("the juror's by-rate list's vote times", () => {
    afterEach(() => { delete window.wlxVoteTime; });

    const row = box => box.querySelector(".rank-line > .wlx-tile-foot > .wlx-tile-chips");
    const chip = box => box.querySelector(".rank-line .wlx-chip-vote");
    const timeOf = box => chip(box) && chip(box).querySelector(".wlx-chip-time > time.wlx-vote-time");

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
        // the chip's words: the time, and with its year as its title (a rated round: no mark)
        expect(chip(boxes[0]).querySelector(".visually-hidden").textContent).toBe("formatted " + iso);
        expect(chip(boxes[0]).title).toBe("formatted " + iso + " year");
        expect(chip(boxes[0]).querySelector(".wlx-chip-mark")).toBeNull();
        expect(boxes[0].querySelectorAll(".wlx-chip-vote")).toHaveLength(1);
        expect(boxes[0].querySelectorAll(".wlx-tile-foot")).toHaveLength(1);
        // the other file's stays
        expect(timeOf(boxes[1]).getAttribute("datetime")).toBe("2026-01-03T03:04:05.678Z");
    });

    test("add the chip for a file unrated at load, before its comments, and replace 'not recorded'", () => {
        const boxes = byRateList([0, 4], undefined, ["", ""], { 0: 2 });
        expect(chip(boxes[0])).toBeNull();
        star(listStars(boxes[0]), 2).trigger("click");
        requests[0].answer.resolve("success");
        expect(timeOf(boxes[0])).not.toBeNull();
        expect(row(boxes[0]).firstElementChild).toBe(chip(boxes[0]));
        expect(row(boxes[0]).lastElementChild.classList.contains("wlx-chip-comments")).toBe(true);
        expect(row(boxes[0]).lastElementChild.getAttribute("href")).toBe("/large/round/2/user/1/pageid/201?sort=latest-vote#comments");
        // not in the thumbnail's link, nor on the thumbnail: after the stars in the rank line
        expect(chip(boxes[0]).closest("a")).toBeNull();
        expect(boxes[0].querySelector(".thumb .wlx-chip")).toBeNull();
        expect(row(boxes[0]).parentNode.previousElementSibling.classList.contains("rate-stars")).toBe(true);

        expect(chip(boxes[1]).querySelector(".wlx-chip-shown").textContent).toBe("Time not recorded");
        star(listStars(boxes[1]), 1).trigger("click");
        requests[1].answer.resolve("success");
        expect(timeOf(boxes[1])).not.toBeNull();
        expect(chip(boxes[1]).querySelector(".wlx-vote-time-none")).toBeNull();
    });

    test("go when the vote is cleared, the comments staying, and come back with the next vote", () => {
        const boxes = byRateList([3, 3], undefined, ["2026-01-02T03:04:05.678Z", "2026-01-02T03:04:05.678Z"], { 1: 1 });
        $(listStars(boxes[0])).find("i.raty-cancel").trigger("click");
        requests[0].answer.resolve("success");
        expect(chip(boxes[0])).toBeNull();
        expect(row(boxes[0])).toBeNull(); // no chips left: no row
        star(listStars(boxes[0]), 4).trigger("click");
        requests[1].answer.resolve("success");
        expect(timeOf(boxes[0])).not.toBeNull();
        expect(boxes[0].querySelectorAll(".wlx-tile-foot")).toHaveLength(1);

        $(listStars(boxes[1])).find("i.raty-cancel").trigger("click");
        requests[2].answer.resolve("success");
        expect(chip(boxes[1])).toBeNull();
        expect(Array.from(row(boxes[1]).children).map(c => c.className)).toEqual(["badge wlx-chip wlx-chip-comments"]);
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
        boxes.forEach(b => expect(chip(b)).toBeNull());
    });
});

// All the jurors' votes on a binary round's tiles in the organisers' gallery (addTally): a check
// and a cross with their numbers, read by screen readers as the page's text, none without it.
describe("a tile's approvals and rejections", () => {
    test("show a check and a cross with their numbers, the text for screen readers", () => {
        const badge = addTally(tile(), { approvals: "3", rejections: "2", tally: "3 approvals, 2 rejections" });
        expect(tile().querySelector(".wlx-chip-tally")).toBe(badge);
        const shown = badge.querySelector("[aria-hidden=true]");
        expect(shown.textContent.trim().split(/\s+/)).toEqual(["3", "2"]);
        expect(shown.querySelector("i.bi-check-lg.wlx-vote-selected")).not.toBeNull();
        expect(shown.querySelector("i.bi-x-lg.wlx-vote-rejected")).not.toBeNull();
        expect(badge.querySelector(".visually-hidden").textContent).toBe("3 approvals, 2 rejections");
        expect(badge.title).toBe("3 approvals, 2 rejections");
    });

    test("add nothing to a tile without them, as a juror's own", () => {
        expect(addTally(tile(), { rate: "1" })).toBeNull();
        expect(tile().querySelector(".wlx-chip-tally")).toBeNull();
    });
});

// A file's comments in the contest on its tile (addComments): a speech bubble and the number,
// hidden from screen readers, which read the page's words (its plural form), also the tooltip;
// nothing for a file without comments.
describe("a tile's comments", () => {
    test("show a bubble and the number, the words for screen readers and as the tooltip", () => {
        const badge = addComments(tile(), { comments: "3", commentsLabel: "3 коментарі" });
        expect(tile().querySelector(".wlx-chip-comments")).toBe(badge);
        expect(badge.tagName).toBe("SPAN"); // no large view to link
        expect(badge.querySelector("i.bi-chat-left-text-fill").getAttribute("aria-hidden")).toBe("true");
        const shown = badge.querySelector("span[aria-hidden=true]");
        expect(shown.textContent.trim()).toBe("3");
        expect(badge.querySelector(".visually-hidden").textContent).toBe("3 коментарі");
        expect(badge.title).toBe("3 коментарі");
        // the badge's text as a screen reader has it: the words only
        const read = Array.from(badge.childNodes).filter(n => !(n.getAttribute && n.getAttribute("aria-hidden") === "true"))
            .map(n => n.textContent).join("");
        expect(read).toBe("3 коментарі");
    });

    test("link the file's large view at its comments, named for screen readers, the click its own", () => {
        document.body.firstElementChild.setAttribute("data-comments-open", "{0}: open the comments");
        const clicked = jest.fn();
        tile().addEventListener("click", clicked);
        const link = addComments(tile(), { comments: "3", commentsLabel: "3 comments",
            largeUrl: "/large/round/1/user/2/pageid/101?sort=rate" });
        expect(link.tagName).toBe("A");
        expect(link.getAttribute("href")).toBe("/large/round/1/user/2/pageid/101?sort=rate#comments");
        expect(link.querySelector(".visually-hidden").textContent).toBe("3 comments: open the comments");
        expect(link.title).toBe("3 comments: open the comments");
        expect(link.querySelector("span[aria-hidden=true]").textContent).toBe("3");
        link.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
        expect(clicked).not.toHaveBeenCalled();
    });

    test("follow the vote or the tally in the tile's chips, or come first without one", () => {
        const badge = addComments(tile(), { comments: "2", commentsLabel: "2 comments" });
        expect(tile().querySelector(".wlx-tile-chips").firstElementChild).toBe(badge);
        const tally = addTally(tile(), { approvals: "1", rejections: "0", tally: "1 approval" });
        expect(Array.from(tile().querySelector(".wlx-tile-chips").children)).toEqual([tally, badge]);
        expect(tile().querySelectorAll(".wlx-tile-chips")).toHaveLength(1);
    });

    test("sit in the tile's foot above a rated round's stars", () => {
        const { el } = stars(3);
        addComments(tile(), { comments: "1", commentsLabel: "1 comment" });
        const foot = tile().querySelector(".wlx-tile-foot");
        expect(Array.from(foot.children).map(c => c.className.split(" ")[0])).toEqual(["wlx-tile-chips", "wlx-tile-stars"]);
        expect(el.parentNode).toBe(foot);
    });

    test("add nothing to a tile without comments", () => {
        expect(addComments(tile(), {})).toBeNull();
        expect(addComments(tile(), { comments: "0", commentsLabel: "0 comments" })).toBeNull();
        expect(addComments(tile(), { comments: "x" })).toBeNull();
        expect(tile().querySelector(".wlx-chip-comments")).toBeNull();
    });
});

// The time of the juror's vote on a tile in an order by vote time (addVoteTime), in the vote chip
// after its mark: a <time> with the UTC value, formatted by vote-time.js where loaded; in a rated
// round (no mark) the text for a vote without a time, in a binary round the mark alone; nothing
// for an unrated file or a tile without the attribute (any other order). After a stored vote in
// the tile (voteTimeAfter): a changed vote's time is now, a cleared vote's chip goes, the same
// vote again keeps its time.
describe("a tile's vote time", () => {
    afterEach(() => { delete window.wlxVoteTime; });

    const badge = () => tile().querySelector(".wlx-chip-time");
    const chip = () => tile().querySelector(".wlx-chip-vote");
    const said = () => chip().querySelector(".visually-hidden").textContent;

    test("shows the time formatted, the UTC value as its datetime, in the vote chip", () => {
        window.wlxVoteTime = { format: (iso, lang, zone, now, year) => `formatted ${iso} ${lang || ""}${year ? " year" : ""}`.trim() };
        document.documentElement.setAttribute("lang", "uk");
        const shown = addVoteTime(tile(), { rate: "1", votedAt: "2026-11-12T14:05:00Z" });
        const time = shown.querySelector("time.wlx-vote-time");
        expect(chip()).toBe(shown);
        expect(badge().parentNode.getAttribute("aria-hidden")).toBe("true");
        expect(time.getAttribute("datetime")).toBe("2026-11-12T14:05:00Z");
        expect(time.textContent).toBe("formatted 2026-11-12T14:05:00Z uk");
        expect(time.title).toBe("formatted 2026-11-12T14:05:00Z uk year");
        // a rated round's (no mark): the time alone, read and titled as shown (the title with the year)
        expect(said()).toBe("formatted 2026-11-12T14:05:00Z uk");
        expect(chip().title).toBe("formatted 2026-11-12T14:05:00Z uk year");
    });

    test("keeps the UTC value without the formatter", () => {
        const shown = addVoteTime(tile(), { rate: "3", votedAt: "2026-11-12T14:05:00Z" });
        expect(shown.querySelector("time").textContent).toBe("2026-11-12T14:05:00Z");
        expect(shown.querySelector("time").title).toBe("");
        expect(shown.title).toBe("2026-11-12T14:05:00Z");
    });

    test("says no time was recorded for a rated round's vote without one", () => {
        addVoteTime(tile(), { rate: "3", votedAt: "", votedNone: "Time not recorded" });
        expect(chip().querySelector("time")).toBeNull();
        expect(badge().classList.contains("wlx-vote-time-none")).toBe(true);
        expect(badge().textContent).toBe("Time not recorded");
        expect(said()).toBe("Time not recorded");
    });

    test("one chip in a binary round: the mark, then the time; one name for both", () => {
        window.wlxVoteTime = { format: (iso, lang, zone, now, year) => (year ? "12 Nov 2026, 14:05" : "12 Nov, 14:05") };
        const item = binaryTile(1);
        item.votedAt = "2026-11-12T14:05:00Z";
        addVoteTime(tile(), item);
        expect(tile().querySelectorAll(".wlx-tile-chips > *")).toHaveLength(1);
        const shown = chip().querySelector(".wlx-chip-shown");
        expect(Array.from(shown.children).map(c => c.className)).toEqual(
            ["bi wlx-chip-mark bi-check-lg wlx-vote-selected", "wlx-chip-time"]);
        expect(said()).toBe("Selected, 12 Nov, 14:05");
        expect(chip().title).toBe("Selected, 12 Nov 2026, 14:05");
        // a vote without a time: the mark alone
        item.votedAt = "";
        item.votedNone = "Time not recorded";
        addVoteTime(tile(), item);
        expect(badge()).toBeNull();
        expect(said()).toBe("Selected");
        expect(chip().querySelector("i.bi-check-lg")).not.toBeNull();
    });

    test("the same chip whether the time or the mark comes first (an organiser's view of one juror)", () => {
        const holder = document.body.firstElementChild;
        holder.setAttribute("data-vote-rejected", "Rejected");
        const item = { rate: "-1", votedAt: "2026-11-12T14:05:00Z" };
        addVoteTime(tile(), item);
        addVoteMark(tile(), item, { binary: true });
        expect(tile().querySelectorAll(".wlx-chip-vote")).toHaveLength(1);
        expect(said()).toBe("Rejected, 2026-11-12T14:05:00Z");
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
        expect(tile().querySelectorAll(".wlx-chip-vote")).toHaveLength(1);
        expect(Date.parse(first)).toBeGreaterThanOrEqual(start - 1000);
        expect(item.votedAt).toBe(first);

        vote(tile(), item, 0);
        requests[1].answer.resolve("success");
        expect(badge()).toBeNull();
        expect(chip()).toBeNull();
        expect(tile().querySelector(".wlx-tile-chips")).toBeNull();
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
// no ✓ or ✗ to click; the badge of the juror's own tiles; nothing for an unrated file.
describe("a tile's read-only vote", () => {
    beforeEach(() => {
        const holder = document.body.firstElementChild;
        holder.setAttribute("data-vote-selected", "Selected");
        holder.setAttribute("data-vote-rejected", "Rejected");
    });

    test.each([[1, "wlx-tile-selected", "bi-check-lg", "Selected"],
               [-1, "wlx-tile-rejected", "bi-x-lg", "Rejected"]])(
        "a binary round's %i: state %s, its icon %s and name", (rate, state, icon, name) => {
            const mark = addVoteMark(tile(), { rate: String(rate) }, { binary: true });
            expect(tile().classList.contains(state)).toBe(true);
            expect(mark.className).toBe("badge wlx-chip wlx-chip-vote");
            expect(mark.querySelector("[aria-hidden=true] > i." + icon)).not.toBeNull();
            expect(mark.querySelector("[class*=thumbs]")).toBeNull();
            expect(mark.querySelector(".visually-hidden").textContent).toBe(name);
            expect(mark.title).toBe(name);
            // nothing to vote with
            expect(tile().querySelectorAll("[role=button], .ug-tile-icon, a, button")).toHaveLength(0);
        });

    test("an unrated file in a binary round: no state, no mark", () => {
        expect(addVoteMark(tile(), { rate: "0" }, { binary: true })).toBeNull();
        expect(tile().className).toBe("ug-tile");
    });

    test("none in a rated round (its by-rate list shows the stars)", () => {
        expect(addVoteMark(tile(), { rate: "4" }, { binary: false })).toBeNull();
        expect(tile().className).toBe("ug-tile");
    });
});
