/**
 * @jest-environment jsdom
 */
"use strict";

// The tiles' chips (tile-votes.js addVoteMark, addVoteTime, addTally, addComments) are the
// server's (snippets.voteChip, binaryTally's chip, snippets.commentChip): cloned from the gallery
// page's templates (snippets.chipTemplates) and filled in, each built here for the same values as
// one the server rendered (fixtures/chips.html, views.ChipFixturesSpec) has the same elements,
// classes, attributes and words, so the same accessible name.
const fs = require("fs");
const path = require("path");

const FIXTURE = fs.readFileSync(path.join(__dirname, "fixtures", "chips.html"), "utf8");

require("jquery");
const { addVoteMark, addVoteTime, addTally, addComments } = require("../public/javascripts/tile-votes.js");

beforeEach(() => { document.body.innerHTML = FIXTURE; });

const tile = () => document.querySelector("#holder .ug-tile");
const server = id => document.querySelector("#server-" + id).firstElementChild;

/** An element as a tree of its tag, attributes (sorted) and children: elements, and text without
  * the whitespace around it (none if only whitespace), which neither shows nor is read. */
function shape(node) {
    if (node.nodeType === Node.TEXT_NODE) {
        const text = node.textContent.trim();
        return text ? text : null;
    }
    const attrs = Array.from(node.attributes).map(a => [a.name, a.name === "class" ? a.value.split(/\s+/).sort().join(" ") : a.value])
        .sort((a, b) => a[0].localeCompare(b[0]));
    return { tag: node.tagName.toLowerCase(), attrs, children: Array.from(node.childNodes).map(shape).filter(c => c !== null) };
}

/** The words a screen reader reads: the text outside aria-hidden parts. */
function accessibleText(node) {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent;
    if (node.getAttribute && node.getAttribute("aria-hidden") === "true") return "";
    return Array.from(node.childNodes).map(accessibleText).join("");
}

function same(built, rendered) {
    expect(built).not.toBeNull();
    expect(rendered).not.toBeNull();
    expect(shape(built)).toEqual(shape(rendered));
    expect(accessibleText(built).trim()).toBe(accessibleText(rendered).trim());
    expect(built.getAttribute("title")).toBe(rendered.getAttribute("title"));
}

describe("a tile's chip and the server's", () => {
    test("the comments' link", () => {
        same(addComments(tile(), { comments: "3", commentsLabel: "3 comments",
            largeUrl: "/large/round/2/user/7/pageid/101?sort=rate" }), server("comments-link"));
        expect(server("comments-link").getAttribute("title")).toBe("3 comments: open the comments");
    });

    test("the comments without a link", () => {
        same(addComments(tile(), { comments: "1", commentsLabel: "1 comment" }), server("comments"));
    });

    test("all the jurors' tally", () => {
        same(addTally(tile(), { approvals: "12", rejections: "3", tally: server("tally").getAttribute("title") }),
            server("tally"));
    });

    test("one juror's vote, its mark alone", () => {
        same(addVoteMark(tile(), { rate: "1" }, { binary: true }), server("vote-selected"));
    });

    test("one juror's vote and its time (without the formatter: the UTC value)", () => {
        const item = { rate: "-1", votedAt: "2026-11-12T14:05:00Z" };
        addVoteTime(tile(), item);
        same(addVoteMark(tile(), item, { binary: true }), server("vote-rejected-time"));
    });

    test("a rated round's vote without a recorded time", () => {
        same(addVoteTime(tile(), { rate: "3", votedAt: "", votedNone: "Time not recorded" }), server("vote-time-none"));
    });
});

// The server's vote chip on a page (the by-rate gallery's thumbnails, galleryByRate thumbChips):
// vote-time.js formats the time in its words as in its shown part, so screen readers never read
// the raw UTC value, and gives it the tiles' title, the words with the time's year.
describe("the server's vote chip on a page", () => {
    afterEach(() => {
        delete window.wlxVoteTime;
        document.documentElement.removeAttribute("lang");
    });

    test("is read with its time formatted, its title with the year", () => {
        const { localize } = require("../public/javascripts/vote-time.js");
        document.documentElement.setAttribute("lang", "en-GB");
        const rendered = server("vote-rejected-time");
        expect(accessibleText(rendered).trim()).toBe("Rejected, 2026-11-12T14:05:00Z"); // before the script
        localize(document);
        const said = accessibleText(rendered).trim();
        expect(said).toMatch(/^Rejected, \d{1,2} Nov( 2026)?, \d\d:\d\d$/);
        expect(rendered.getAttribute("title")).toMatch(/^Rejected, \d{1,2} Nov 2026, \d\d:\d\d$/);
        expect(rendered.querySelector(".visually-hidden time.wlx-vote-time").textContent)
            .toBe(rendered.querySelector(".wlx-chip-shown time.wlx-vote-time").textContent);
        // a chip without a time keeps its words and title
        localize(document);
        expect(server("vote-selected").getAttribute("title")).toBe("Selected");
    });
});
