/**
 * @jest-environment jsdom
 */
"use strict";

// unitegallery.js (vendored, patched for wlxjury): a tile's thumbnail alt is the text of the
// item's title, which is html (gallery.scala.html's caption: a link whose text is the file
// title escaped once more), escaped for the single-quoted attribute: no markup, no visible
// entities, no breaking out of the quotes.

const fs = require("fs");
const path = require("path");

const src = fs.readFileSync(path.join(__dirname, "../public/javascripts/unitegallery.js"), "utf8");

beforeAll(() => {
    global.jQuery = global.$ = require("jquery");
    // a plain script: its functions become globals, as in the page
    (0, eval)(src);
});

/** The `alt` the browser decodes from gallery.scala.html's attribute for `title`. */
function caption(title) {
    const escaped = title.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;").replace(/'/g, "&#x27;");
    return `<a href='https://commons.wikimedia.org/wiki/x' target='_blank'>${escaped}</a>`;
}

/** The thumbnail `<img>` the tile design builds, as the browser parses it. */
function thumbAlt(title) {
    const f = new global.UGFunctions();
    const html = "<img src='" + f.escapeAttr("/thumb?x='y") + "' alt='" + f.escapeAttr(f.htmlToText(caption(title))) + "'>";
    document.body.innerHTML = html;
    return document.body.querySelector("img");
}

describe("the thumbnail alt", () => {
    test.each([
        "File:St Andrew's church.jpg",
        "File:A & B.jpg",
        "File:A <i>b</i>.jpg",
        "File:x' onerror='alert(1).jpg",
        "File:\"quoted\".jpg"
    ])("is the title as text: %s", (title) => {
        const img = thumbAlt(title);
        expect(img.getAttribute("alt")).toBe(title);
        expect(img.getAttribute("onerror")).toBeNull();
        expect(img.getAttribute("src")).toBe("/thumb?x='y");
        expect(document.body.querySelectorAll("i")).toHaveLength(0);
    });

    test("htmlToText runs nothing: markup is parsed inert", () => {
        window.ran = false;
        const f = new global.UGFunctions();
        expect(f.htmlToText("<img src=x onerror='window.ran = true'>a<script>window.ran = true</script>")).toBe("awindow.ran = true");
        expect(window.ran).toBe(false);
        expect(f.htmlToText(null)).toBe("");
    });

    test("both thumbnail sinks use it (setHtmlThumb and the image effect overlay)", () => {
        const sinks = src.match(/<img src='"\+[^;]*alt='"\+[^;]*;/g);
        expect(sinks).toHaveLength(2);
        sinks.forEach((s) => {
            expect(s).toContain("escapeAttr(objItem.urlThumb)");
            expect(s.includes("escapeAttr(g_functions.htmlToText(objItem.title))") || s.includes("alt='\"+imageAlt+\"'")).toBe(true);
        });
        expect(src).toContain("var imageAlt = g_functions.escapeAttr(g_functions.htmlToText(objItem.title));");
    });
});
