/**
 * @jest-environment jsdom
 */
"use strict";

// The wlxjury patches of the vendored gallery (docs/vendor/unitegallery.md): unitegallery.js and
// ug-theme-tiles.js are loaded as the page loads them and run on a #gallery of <img> tags, as
// gallery.scala.html and contestFiles.scala.html write it. Each test fails if its patch is
// reverted (the patch table names the test). jsdom loads no images and lays nothing out, so
// the img fakes below give a thumbnail its natural size (or a failed load) by its src.

const fs = require("fs");
const path = require("path");

const dir = path.join(__dirname, "../public/javascripts");
const ugSrc = fs.readFileSync(path.join(dir, "unitegallery.js"), "utf8");
const themeSrc = fs.readFileSync(path.join(dir, "ug-theme-tiles.js"), "utf8");

/** Natural sizes by src: an <img> whose src is here loads, any other fails. */
const sizes = {};
const readyErrors = [];

beforeAll(() => {
    const natural = (i) => function () { const s = sizes[this.getAttribute("src")]; return s ? s[i] : 0; };
    Object.defineProperty(HTMLImageElement.prototype, "naturalWidth", { configurable: true, get: natural(0) });
    Object.defineProperty(HTMLImageElement.prototype, "naturalHeight", { configurable: true, get: natural(1) });
    const setAttribute = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function (name, value) {
        setAttribute.call(this, name, value);
        if (this.tagName === "IMG" && name === "src") {
            const img = this;
            setTimeout(() => img.dispatchEvent(new Event(sizes[value] ? "load" : "error")));
        }
    };
    // isRgbaSupported reads the page's first script element
    document.head.innerHTML = "<script></script>";
    global.jQuery = global.$ = require("jquery");
    // a ready handler's error is rethrown later by jQuery 3; keep it to assert on
    jQuery.readyException = (e) => { readyErrors.push(e); };
    (0, eval)(ugSrc);
    (0, eval)(themeSrc);
});

beforeEach(() => {
    window.pwned = undefined;
    window.open = jest.fn();
    window.wlxTileVotes = { addVote: jest.fn(), addStars: jest.fn(), addTally: jest.fn(), addVoteTime: jest.fn(), addVoteMark: jest.fn(), addComments: jest.fn(), vote: jest.fn() };
});

const tick = (ms) => new Promise((r) => setTimeout(r, ms));

async function until(cond, what) {
    for (let i = 0; i < 100; i++) {
        if (cond()) return;
        await tick(20);
    }
    throw new Error("timed out waiting for " + what);
}

const galleryInit = require("../public/javascripts/gallery-init.js");

/**
 * The gallery's options as gallery-init.js gives them for the #gallery element `el`.
 * `extra`'s `jury`, `binary` and `wlx_stars` become el's data-jury, data-binary and data-stars
 * (as views.GalleryTiles writes them); its other keys override options a page doesn't set
 * (tile_enable_image_effect, a theme that isn't loaded).
 */
function options(el, extra) {
    const { jury, binary, wlx_stars: stars, ...rest } = extra || {};
    if (jury !== undefined) el.setAttribute("data-jury", String(jury));
    if (binary !== undefined) el.setAttribute("data-binary", String(binary));
    if (stars) el.setAttribute("data-stars", JSON.stringify(stars));
    return Object.assign(galleryInit.options(el), rest);
}

describe("gallery-init.js's options", () => {
    // N15's premise (docs/vendor/unitegallery.md §3): the link sinks are unreachable while
    // these stay at unitegallery's defaults, off
    test("leave the tile and thumbnail links off", () => {
        const el = document.createElement("div");
        const o = galleryInit.options(el);
        ["tile_show_link_icon", "tile_as_link", "thumb_wrapper_as_link"].forEach((k) => expect(o[k]).toBeUndefined());
        expect(o.gallery_theme).toBe("tiles");
    });

    test("unitegallery's defaults for them are off", () => {
        expect(ugSrc).toMatch(/tile_as_link: false,/);
        expect(ugSrc).toMatch(/tile_show_link_icon: false,/);
        expect(ugSrc).toMatch(/thumb_wrapper_as_link: false,/);
    });
});

let nextId = 1;

/**
 * Starts the gallery on a #gallery of one <img> per item (its attributes: `alt`, `src`, and
 * `data-*` for the rest) and waits for its tiles. Returns the tiles.
 */
async function gallery(items, extra) {
    document.body.innerHTML = "";
    const el = document.createElement("div");
    el.id = "gallery";
    el.setAttribute("style", "width:1000px");
    items.forEach((item) => {
        const id = nextId++;
        const img = document.createElement("img");
        const attrs = Object.assign({
            alt: "File:" + id + ".jpg",
            src: "/thumb/" + id + ".jpg",
            "data-image": "/large/" + id + ".jpg",
            "data-pageId": String(id),
            "data-largeUrl": "/large-view/" + id
        }, item);
        Object.keys(attrs).forEach((k) => { if (attrs[k] !== null) img.setAttribute(k, attrs[k]); });
        el.appendChild(img);
    });
    document.body.appendChild(el);
    jQuery("#gallery").unitegallery(options(el, extra));
    await until(() => document.querySelectorAll("#gallery .ug-tile").length === items.length, "the tiles");
    await tick(60);
    return Array.from(document.querySelectorAll("#gallery .ug-tiles-wrapper .ug-tile"));
}

/** A loaded thumbnail's src. */
function loaded(src, w, h) {
    sizes[src] = [w || 400, h || 300];
    return src;
}

describe("the thumbnail's alt and src (setHtmlThumb): escaped for the quoted attribute", () => {
    const hostile = "x' onerror='window.pwned=1' y='";

    test("an alt that would close the attribute stays its text", async () => {
        const [tile] = await gallery([{ alt: hostile, src: loaded("/t/a.jpg") }]);
        const img = tile.querySelector("img.ug-thumb-image");
        expect(img.getAttribute("alt")).toBe(hostile);
        expect(img.getAttribute("onerror")).toBeNull();
        expect(img.getAttribute("y")).toBeNull();
        expect(window.pwned).toBeUndefined();
    });

    test("the caption's markup is text, its entities decoded once", async () => {
        const caption = "<a href='https://commons.wikimedia.org/wiki/x' target='_blank'>File:A &amp; B &lt;i&gt;c&lt;/i&gt; d&#x27;e.jpg</a>";
        const [tile] = await gallery([{ alt: caption, src: loaded("/t/b.jpg") }]);
        const img = tile.querySelector("img.ug-thumb-image");
        expect(img.getAttribute("alt")).toBe("File:A & B <i>c</i> d'e.jpg");
        expect(tile.querySelectorAll("i, a[href*='commons']")).toHaveLength(0);
    });

    test("a src with quotes stays the src", async () => {
        const src = loaded("/t/c.jpg?x=' onerror='window.pwned=1");
        const [tile] = await gallery([{ src }]);
        const img = tile.querySelector("img.ug-thumb-image");
        expect(img.getAttribute("src")).toBe(src);
        expect(img.getAttribute("onerror")).toBeNull();
    });

    test("the image effect's overlay copy too (tile_enable_image_effect)", async () => {
        const src = loaded("/t/d.jpg?x=' onerror='window.pwned=1");
        const [tile] = await gallery([{ alt: hostile, src }], { tile_enable_image_effect: true });
        const img = tile.querySelector(".ug-tile-image-overlay img");
        expect(img).not.toBeNull();
        expect(img.getAttribute("alt")).toBe(hostile);
        expect(img.getAttribute("src")).toBe(src);
        expect(img.getAttribute("onerror")).toBeNull();
        expect(window.pwned).toBeUndefined();
    });
});

describe("the lightbox's slides (placeImageInsideParent): the large url escaped", () => {
    test("a data-image with a double quote stays the src", async () => {
        const large = "/large/e.jpg?x=\" onerror=\"window.pwned=1";
        await gallery([{ src: loaded("/t/e.jpg"), "data-image": large }]);
        await until(() => document.querySelectorAll(".ug-slide-wrapper img").length > 0, "the slides");
        const slides = Array.from(document.querySelectorAll(".ug-slide-wrapper img"));
        slides.forEach((img) => {
            expect(img.getAttribute("src")).toBe(large);
            expect(img.getAttribute("onerror")).toBeNull();
        });
        expect(window.pwned).toBeUndefined();
    });
});

describe("the lightbox's title (upstream, not patched): the template escapes it", () => {
    test("a caption as gallery.scala.html writes it shows as text", async () => {
        const caption = "<a href='https://commons.wikimedia.org/wiki/x' target='_blank'>&lt;img src=x onerror=window.pwned=1&gt;</a>";
        await gallery([{ alt: caption, src: loaded("/t/f.jpg") }]);
        const title = document.querySelector(".ug-lightbox .ug-textpanel-title");
        expect(title.textContent).toBe("<img src=x onerror=window.pwned=1>");
        expect(title.querySelectorAll("img")).toHaveLength(0);
    });
});

describe("a file deleted on Commons (data-deleted)", () => {
    const commons = "https://commons.wikimedia.org/wiki/File:Gone.jpg";

    test("its tile is marked, the mark a link to its page there in a new tab", async () => {
        const [gone, kept] = await gallery([
            { src: loaded("/t/g.jpg"), "data-deleted": "Deleted on Commons 2026-10-01", "data-commonsUrl": commons },
            { src: loaded("/t/h.jpg") }
        ]);
        expect(gone.classList).toContain("wlx-deleted");
        const label = gone.querySelector("a.wlx-deleted-label");
        expect(label.getAttribute("href")).toBe(commons);
        expect(label.getAttribute("target")).toBe("_blank");
        expect(label.getAttribute("rel")).toBe("noopener");
        expect(label.textContent).toBe("Deleted on Commons 2026-10-01");
        expect(label.getAttribute("title")).toBe("Deleted on Commons 2026-10-01");
        expect(kept.classList).not.toContain("wlx-deleted");
        expect(kept.querySelector(".wlx-deleted-label")).toBeNull();
    });

    test("the mark's text is text", async () => {
        const [tile] = await gallery([{ src: loaded("/t/i.jpg"), "data-deleted": "<img src=x onerror='window.pwned=1'>", "data-commonsUrl": commons }]);
        const label = tile.querySelector("a.wlx-deleted-label");
        expect(label.textContent).toBe("<img src=x onerror='window.pwned=1'>");
        expect(label.querySelectorAll("img")).toHaveLength(0);
    });

    test("a click on the mark doesn't reach the tile (which opens the large view)", async () => {
        const [tile] = await gallery([{ src: loaded("/t/j.jpg"), "data-deleted": "Deleted", "data-commonsUrl": commons }]);
        tile.querySelector("a.wlx-deleted-label").dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
        expect(window.open).not.toHaveBeenCalled();
        // the tile itself still opens it
        tile.querySelector("img.ug-thumb-image").dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
        expect(window.open).toHaveBeenCalledWith(expect.stringMatching(/^\/large-view\/\d+$/), "_self");
    });
});

describe("a thumbnail that fails to load (onThumbImageLoaded)", () => {
    test("is marked and keeps a size: its data-width / data-height, else 4:3", async () => {
        const tiles = await gallery([
            { src: loaded("/t/k.jpg", 400, 300) },
            { src: "/t/missing-1.jpg", "data-width": "300", "data-height": "600" },
            { src: "/t/missing-2.jpg" }
        ]);
        await until(() => document.querySelectorAll(".wlx-thumb-failed").length === 2, "the failed marks");
        expect(tiles.map((t) => t.classList.contains("wlx-thumb-failed"))).toEqual([false, true, true]);
        // every tile placed: a NaN ratio would leave them without a size and a place
        tiles.forEach((t) => {
            ["width", "height", "left", "top"].forEach((p) => expect(t.style[p]).toMatch(/^\d+(\.\d+)?px$/));
        });
    });
});

describe("ismouseover's iframe load handler (jQuery 3)", () => {
    test("runs on document ready without an error, and tracks the mouse", async () => {
        await tick(20);
        expect(readyErrors).toEqual([]);
        document.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 0, clientY: 0 }));
        expect(jQuery.mlp).toBeDefined();
    });
});

describe("the theme's constructor, looked up by name (no eval under the CSP)", () => {
    test("the tiles start with eval refused", async () => {
        const realEval = global.eval;
        global.eval = () => { throw new EvalError("refused by the content security policy"); };
        try {
            const tiles = await gallery([{ src: loaded("/t/l.jpg") }]);
            expect(tiles).toHaveLength(1);
            expect(document.getElementById("gallery").textContent).not.toContain("Unite Gallery Error");
        } finally {
            global.eval = realEval;
        }
    });

    test("a theme that isn't loaded is an error that names it", () => {
        document.body.innerHTML = "<div id='gallery' style='width:1000px'><img alt='a' src='/t/m.jpg'></div>";
        const opts = options(document.getElementById("gallery"), { gallery_theme: "nope" });
        expect(() => jQuery("#gallery").unitegallery(opts))
            .toThrow("unite gallery: the theme UGTheme_nope isn't loaded");
    });
});

describe("the binary round's thumbs (issue #6)", () => {
    const tile = (overrides) => Object.assign({ src: loaded("/t/n" + nextId + ".jpg") }, overrides);

    test("a juror's tiles in a binary round have them, and get their stored votes", async () => {
        const tiles = await gallery([
            tile({ "data-rate": "1", "data-pageId": "42", "data-roundId": "7" }),
            tile({ "data-rate": "0" })
        ], { jury: true, binary: true });
        tiles.forEach((t) => {
            expect(t.querySelectorAll(".ug-icon-select")).toHaveLength(1);
            expect(t.querySelectorAll(".ug-icon-reject")).toHaveLength(1);
        });
        expect(window.wlxTileVotes.addVote).toHaveBeenCalledTimes(2);
        const [el, item] = window.wlxTileVotes.addVote.mock.calls[0];
        expect(el).toBe(tiles[0]);
        // the fields tile-votes.js sends the vote with (voteUrl: round and page id)
        expect([item.rate, item.pageId, item.roundId]).toEqual(["1", "42", "7"]);
    });

    test("the thumbs sit below the zoom icon, select left of reject (positionElements)", async () => {
        const [t] = await gallery([tile()], { jury: true, binary: true });
        const at = (sel) => {
            const s = t.querySelector(sel).style;
            return { left: parseFloat(s.left), top: parseFloat(s.top) };
        };
        const zoom = at(".ug-icon-zoom"), select = at(".ug-icon-select"), reject = at(".ug-icon-reject");
        [zoom, select, reject].forEach((p) => { expect(Number.isFinite(p.left)).toBe(true); expect(Number.isFinite(p.top)).toBe(true); });
        expect(select.top).toBeGreaterThan(zoom.top);
        expect(reject.top).toBeGreaterThan(zoom.top);
        expect(select.left).toBeLessThan(reject.left);
    });

    test("no thumbs for an organiser, nor in a rated round", async () => {
        let tiles = await gallery([tile()], { jury: false, binary: true });
        expect(tiles[0].querySelector(".ug-icon-select, .ug-icon-reject")).toBeNull();
        tiles = await gallery([tile()], { jury: true, binary: false });
        expect(tiles[0].querySelector(".ug-icon-select, .ug-icon-reject")).toBeNull();
        expect(window.wlxTileVotes.addVote).not.toHaveBeenCalled();
    });

    test.each([
        ["select", "0", 1], ["select", "-1", 1], ["select", "1", 0],
        ["reject", "0", -1], ["reject", "1", -1], ["reject", "-1", 0]
    ])("a %s click on a file rated %s votes %d, without opening the large view", async (icon, rate, vote) => {
        const [t] = await gallery([tile({ "data-rate": rate })], { jury: true, binary: true });
        t.querySelector(".ug-icon-" + icon).dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
        expect(window.wlxTileVotes.vote).toHaveBeenCalledTimes(1);
        const [el, item, r] = window.wlxTileVotes.vote.mock.calls[0];
        expect(el).toBe(t);
        expect(item.rate).toBe(rate);
        expect(r).toBe(vote);
        expect(window.open).not.toHaveBeenCalled();
    });
});

describe("the tiles theme (ug-theme-tiles.js)", () => {
    test("a tile click opens the file's large view in this tab, not the lightbox", async () => {
        const [t] = await gallery([{ src: loaded("/t/o.jpg"), "data-largeUrl": "/large-view/o?order=1" }]);
        t.querySelector("img.ug-thumb-image").dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
        expect(window.open).toHaveBeenCalledWith("/large-view/o?order=1", "_self");
        expect(document.querySelector(".ug-lightbox").style.display).not.toBe("block");
    });

    test("a rated round's stars on every tile (wlx_stars)", async () => {
        const stars = { number: 5, half: true };
        const tiles = await gallery([{ src: loaded("/t/p.jpg") }, { src: loaded("/t/q.jpg") }], { jury: true, wlx_stars: stars });
        expect(window.wlxTileVotes.addStars).toHaveBeenCalledTimes(2);
        expect(window.wlxTileVotes.addStars.mock.calls[1][0]).toBe(tiles[1]);
        expect(window.wlxTileVotes.addStars.mock.calls[1][2]).toEqual(stars);
    });

    test("the jurors' tally on every tile, from data-approvals / data-rejections / data-tally", async () => {
        const tiles = await gallery([{ src: loaded("/t/r.jpg"), "data-approvals": "a, b", "data-rejections": "c", "data-tally": "2 yes, 1 no" }]);
        expect(window.wlxTileVotes.addTally).toHaveBeenCalledTimes(1);
        const [el, item] = window.wlxTileVotes.addTally.mock.calls[0];
        expect(el).toBe(tiles[0]);
        expect([item.approvals, item.rejections, item.tally]).toEqual(["a, b", "c", "2 yes, 1 no"]);
    });

    test("the file's comments on every tile, from data-comments / data-comments-label", async () => {
        const tiles = await gallery([
            { src: loaded("/t/c.jpg"), "data-comments": "3", "data-comments-label": "3 коментарі" },
            { src: loaded("/t/d.jpg") }
        ]);
        expect(window.wlxTileVotes.addComments).toHaveBeenCalledTimes(2);
        const items = window.wlxTileVotes.addComments.mock.calls.map(([el, item]) => [el, item.comments, item.commentsLabel]);
        expect(items).toEqual([
            [tiles[0], "3", "3 коментарі"],
            // a file without comments: no attribute, which addComments takes as no badge
            [tiles[1], undefined, undefined]
        ]);
    });

    test("the juror's vote time on every tile, from data-voted-at / data-voted-none", async () => {
        const tiles = await gallery([
            { src: loaded("/t/v.jpg"), "data-rate": "1", "data-voted-at": "2026-11-12T14:05:00.123Z" },
            { src: loaded("/t/w.jpg"), "data-rate": "-1", "data-voted-at": "", "data-voted-none": "Time not recorded" },
            { src: loaded("/t/x.jpg") }
        ]);
        expect(window.wlxTileVotes.addVoteTime).toHaveBeenCalledTimes(3);
        const items = window.wlxTileVotes.addVoteTime.mock.calls.map(([el, item]) => [el, item.votedAt, item.votedNone]);
        expect(items).toEqual([
            [tiles[0], "2026-11-12T14:05:00.123Z", undefined],
            [tiles[1], "", "Time not recorded"],
            // another order: no attribute, which addVoteTime takes as no time to show
            [tiles[2], undefined, undefined]
        ]);
    });

    test("one juror's vote, read-only, on every tile where the page shows it (wlx_view_votes)", async () => {
        const view = { binary: true };
        const tiles = await gallery([
            { src: loaded("/t/y.jpg"), "data-rate": "-1" },
            { src: loaded("/t/z.jpg"), "data-rate": "0" }
        ], { binary: true, wlx_view_votes: view });
        expect(window.wlxTileVotes.addVoteMark).toHaveBeenCalledTimes(2);
        const [el, item, v] = window.wlxTileVotes.addVoteMark.mock.calls[0];
        expect(el).toBe(tiles[0]);
        expect(item.rate).toBe("-1");
        expect(v).toEqual(view);
        // no thumbs to vote with: the tiles aren't the viewer's (jury off)
        expect(tiles[0].querySelectorAll(".ug-icon-select, .ug-icon-reject")).toHaveLength(0);
        expect(window.wlxTileVotes.addVote).not.toHaveBeenCalled();
    });

    test("no read-only votes without wlx_view_votes", async () => {
        await gallery([{ src: loaded("/t/za.jpg"), "data-rate": "1" }]);
        expect(window.wlxTileVotes.addVoteMark).not.toHaveBeenCalled();
    });

    test("a page without tile-votes.js (contestFiles) gets no tally, and no error", async () => {
        delete window.wlxTileVotes;
        const tiles = await gallery([{ src: loaded("/t/s.jpg") }]);
        expect(tiles).toHaveLength(1);
        expect(document.getElementById("gallery").textContent).not.toContain("Unite Gallery Error");
    });
});
