/**
 * @jest-environment jsdom
 */
"use strict";

// large-view.js: votes posted by the vote form, and the image failure listener that replaced
// the large views' onerror attributes (one listener on the document, in the capture phase).

window.jQuery = require("jquery");
const { vote, imageFailed } = require("../public/javascripts/large-view");

let submits;

beforeEach(() => {
    submits = [];
    jest.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(function () {
        submits.push(this.getAttribute("action"));
    });
});

afterEach(() => jest.restoreAllMocks());

/** Fires an image's error event as the browser does: not bubbling. */
function fail(img) {
    img.dispatchEvent(new Event("error", { bubbles: false }));
}

describe("vote", () => {
    test("posts the vote form to the path", () => {
        document.body.innerHTML = '<form id="vote-form" method="post"><input type="hidden" name="csrfToken" value="t"></form>';
        expect(vote("/large/round/1/pageid/7/select/1?sort=rate")).toBe(true);
        expect(submits).toEqual(["/large/round/1/pageid/7/select/1?sort=rate"]);
    });

    test("votes nothing without a vote form (a read-only view)", () => {
        document.body.innerHTML = "<p></p>";
        expect(vote("/large/round/1/pageid/7/select/1")).toBe(false);
        expect(submits).toHaveLength(0);
    });

    test("a click on a data-vote-url button posts its vote, not a GET of its link", () => {
        document.body.innerHTML = '<form id="vote-form" method="post"></form>' +
            '<a href="#" class="btn" data-vote-url="/large/round/1/pageid/7/select/-1"><i></i></a>';
        const event = new MouseEvent("click", { bubbles: true, cancelable: true });
        document.querySelector("a i").dispatchEvent(event);
        expect(submits).toEqual(["/large/round/1/pageid/7/select/-1"]);
        expect(event.defaultPrevented).toBe(true);
    });
});

describe("a failed image", () => {
    test("a thumbnail marks its box", () => {
        document.body.innerHTML = '<div class="thumb_small"><a class="wlx-box-link"><img data-failed-mark="thumb"></a></div>';
        fail(document.querySelector("img"));
        expect(document.querySelector(".thumb_small").classList.contains("wlx-thumb-failed")).toBe(true);
    });

    test("the large image is loaded once more from its fallback, without srcset and sizes, then marks its link", () => {
        document.body.innerHTML = '<a id="link"><img id="large_image" src="https://upload.wikimedia.org/a.jpg" ' +
            'srcset="https://upload.wikimedia.org/a.jpg 800w" sizes="100vw" data-fallback="/wikipedia/commons/a.jpg" ' +
            'data-failed-mark="large"></a>';
        const img = document.getElementById("large_image");
        fail(img);
        expect(img.getAttribute("src")).toBe("/wikipedia/commons/a.jpg");
        expect(img.hasAttribute("srcset")).toBe(false);
        expect(img.hasAttribute("sizes")).toBe(false);
        expect(img.hasAttribute("data-fallback")).toBe(false);
        expect(document.getElementById("link").classList.contains("wlx-large-failed")).toBe(false);
        fail(img);
        expect(document.getElementById("link").classList.contains("wlx-large-failed")).toBe(true);
    });

    test("a large image without a fallback (a deleted file's cached thumbnail) marks its link at once", () => {
        document.body.innerHTML = '<a id="link"><img src="/x.jpg" data-failed-mark="large"></a>';
        fail(document.querySelector("img"));
        expect(document.getElementById("link").classList.contains("wlx-large-failed")).toBe(true);
    });

    test("other images and elements are left alone", () => {
        document.body.innerHTML = '<div class="thumb_small"><a><img src="/x.jpg"></a></div><a><video></video></a>';
        fail(document.querySelector("img"));
        document.querySelector("video").dispatchEvent(new Event("error"));
        expect(document.querySelector(".wlx-thumb-failed, .wlx-large-failed")).toBeNull();
        expect(() => imageFailed({ target: null })).not.toThrow();
    });
});
