/**
 * @jest-environment jsdom
 */
"use strict";

// large-view.js: votes posted by the vote form, the image failure listener that replaced
// the large views' onerror attributes (one listener on the document, in the capture phase), and
// the scroll back to the comments of a page opened at #comments once its image has loaded.

window.jQuery = require("jquery");
const { vote, imageFailed, imageLoaded, keepCommentsInView } = require("../public/javascripts/large-view");

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

    test("a failed video thumbnail shows the failure mark without the video's play mark (main.css)", () => {
        const css = require("fs").readFileSync(require("path").join(__dirname, "../public/stylesheets/main.css"), "utf8");
        document.head.innerHTML = `<style>${css}</style>`;
        document.body.innerHTML = '<div class="thumb_small"><a class="wlx-box-link"><img src="/v.jpg" data-failed-mark="thumb">' +
            '<span class="wlx-video-mark" aria-hidden="true"></span></a></div>';
        const mark = document.querySelector(".wlx-video-mark");
        expect(getComputedStyle(mark).display).not.toBe("none");
        fail(document.querySelector("img"));
        expect(document.querySelector(".thumb_small").classList.contains("wlx-thumb-failed")).toBe(true);
        expect(getComputedStyle(mark).display).toBe("none");
        document.head.innerHTML = "";
    });

    test("other images and elements are left alone", () => {
        document.body.innerHTML = '<div class="thumb_small"><a><img src="/x.jpg"></a></div><a><video></video></a>';
        fail(document.querySelector("img"));
        document.querySelector("video").dispatchEvent(new Event("error"));
        expect(document.querySelector(".wlx-thumb-failed, .wlx-large-failed")).toBeNull();
        expect(() => imageFailed({ target: null })).not.toThrow();
    });
});

describe("the large image's box until it loads", () => {
    test("goes once the file has loaded, from the document's capture-phase listener", () => {
        document.body.innerHTML = '<a><img id="large_image" src="https://upload.wikimedia.org/a.jpg" class="mw-100 mh-100" ' +
            'width="1280" height="960" data-fallback="/wikipedia/commons/a.jpg" data-failed-mark="large"></a>';
        const img = document.getElementById("large_image");
        fail(img); // a failure keeps it while the fallback loads
        expect([img.getAttribute("width"), img.getAttribute("height")]).toEqual(["1280", "960"]);
        img.dispatchEvent(new Event("load", { bubbles: false }));
        expect(img.hasAttribute("width")).toBe(false);
        expect(img.hasAttribute("height")).toBe(false);
        expect(img.className).toBe("mw-100 mh-100");
    });

    test("other images keep theirs", () => {
        document.body.innerHTML = '<img id="other" width="10" height="10"><div id="large_image" width="10"></div>';
        document.getElementById("other").dispatchEvent(new Event("load"));
        imageLoaded({ target: document.getElementById("large_image") });
        expect(document.querySelectorAll("[width]")).toHaveLength(2);
        expect(() => imageLoaded({ target: null })).not.toThrow();
    });
});

describe("a page opened at its comments", () => {
    let scrolls;
    const jsdomScrollY = Object.getOwnPropertyDescriptor(window, "pageYOffset");
    const INSTANT = { block: "start", behavior: "instant" };

    /** The large view's image, and its comments below, their heading `top` px down the view
      * (below jsdom's 768 px high view by default). */
    function page({ hash = "#comments", comments = true, image = true, top = 900 } = {}) {
        document.body.innerHTML = (image ? '<a><img id="large_image" src="https://upload.wikimedia.org/a.jpg" ' +
            'data-failed-mark="large" data-fallback="/wikipedia/commons/a.jpg"></a>' : "") +
            (comments ? '<section><h2 id="comments">Comments</h2></section>' : "");
        const heading = document.getElementById("comments");
        if (heading) heading.getBoundingClientRect = () => ({ top, bottom: top + 20, left: 0, right: 100, width: 100, height: 20 });
        window.history.replaceState(null, "", hash || window.location.pathname);
        return document.getElementById("large_image");
    }

    /** The page reached by `type` ('navigate', 'reload', 'back_forward'): the navigation timing's,
      * or, with `legacy`, only performance.navigation's (0, 1, 2). */
    function reachedBy(type, { legacy = false } = {}) {
        window.performance.getEntriesByType = jest.fn(() => (legacy ? [] : [{ type }]));
        if (legacy) {
            Object.defineProperty(window.performance, "navigation", {
                value: { type: ["navigate", "reload", "back_forward"].indexOf(type) }, configurable: true });
        }
    }

    /** The page's load event, then the wait for the scroll position to settle (jsdom's stays put). */
    function pageLoad() {
        window.dispatchEvent(new Event("load"));
        jest.advanceTimersByTime(200);
    }

    beforeEach(() => {
        jest.useFakeTimers();
        scrolls = [];
        // jsdom has no scrollIntoView
        Element.prototype.scrollIntoView = function (options) { scrolls.push([this.id, options]); };
    });

    afterEach(() => {
        // the page's load ends any watch a test left
        pageLoad();
        jest.useRealTimers();
        delete Element.prototype.scrollIntoView;
        delete window.performance.getEntriesByType;
        delete window.performance.navigation;
        // jsdom's own scroll position (0) back, after a test's moving one
        Object.defineProperty(window, "pageYOffset", jsdomScrollY);
        window.history.replaceState(null, "", window.location.pathname);
    });

    test("scrolls to the comments again, instantly, when the image loads and when the page has loaded, then no more", () => {
        const img = page();
        expect(keepCommentsInView(document, window)).toBe(true);
        expect(scrolls).toEqual([]);
        img.dispatchEvent(new Event("load"));
        expect(scrolls).toEqual([["comments", INSTANT]]);
        pageLoad();
        expect(scrolls).toEqual([["comments", INSTANT], ["comments", INSTANT]]);
        img.dispatchEvent(new Event("load"));
        pageLoad();
        expect(scrolls).toHaveLength(2);
    });

    test("not when the page has loaded with the comments' heading in view and the image loaded", () => {
        const img = page({ top: 10 });
        Object.defineProperty(img, "complete", { value: true, configurable: true });
        keepCommentsInView(document, window);
        pageLoad();
        expect(scrolls).toEqual([]);
    });

    test("when the page has loaded with the heading in view but the image still loading, or the heading above the view", () => {
        const img = page({ top: 10 });
        Object.defineProperty(img, "complete", { value: false, configurable: true });
        keepCommentsInView(document, window);
        pageLoad();
        expect(scrolls).toHaveLength(1);
        page({ top: -40 });
        keepCommentsInView(document, window);
        pageLoad();
        expect(scrolls).toHaveLength(2);
    });

    test("also when the browser says the image is complete before its load event (WebKit)", () => {
        const img = page();
        Object.defineProperty(img, "complete", { value: true, configurable: true });
        expect(keepCommentsInView(document, window)).toBe(true);
        img.dispatchEvent(new Event("load"));
        expect(scrolls).toHaveLength(1);
    });

    test("watches from the page's DOMContentLoaded", () => {
        page();
        document.dispatchEvent(new Event("DOMContentLoaded"));
        pageLoad();
        expect(scrolls).toHaveLength(1);
    });

    test("when the image fails, and again when its fallback loads", () => {
        const img = page();
        keepCommentsInView(document, window);
        fail(img); // the capture listener loads the fallback
        expect(img.getAttribute("src")).toBe("/wikipedia/commons/a.jpg");
        expect(scrolls).toHaveLength(1);
        img.dispatchEvent(new Event("load"));
        expect(scrolls).toHaveLength(2);
    });

    test("without the scroll-behavior a browser may not know: then as the page's CSS says", () => {
        const img = page();
        Element.prototype.scrollIntoView = function (options) {
            if (options.behavior === "instant") throw new TypeError("'instant' is not a valid value");
            scrolls.push([this.id, options]);
        };
        keepCommentsInView(document, window);
        img.dispatchEvent(new Event("load"));
        expect(scrolls).toEqual([["comments", { block: "start" }]]);
    });

    test("on a page without the large image (a video), when the page has loaded", () => {
        page({ image: false });
        expect(keepCommentsInView(document, window)).toBe(true);
        pageLoad();
        expect(scrolls).toEqual([["comments", INSTANT]]);
    });

    test.each(["wheel", "touchmove", "keydown", "mousedown", "pointerdown"])("not after the user's %s", (type) => {
        const img = page();
        keepCommentsInView(document, window);
        document.body.dispatchEvent(new Event(type, { bubbles: true }));
        img.dispatchEvent(new Event("load"));
        fail(img);
        pageLoad();
        expect(scrolls).toEqual([]);
    });

    test("not once the page left its comments (another hash)", () => {
        const img = page();
        keepCommentsInView(document, window);
        window.history.replaceState(null, "", "#top");
        img.dispatchEvent(new Event("load"));
        pageLoad();
        expect(scrolls).toEqual([]);
    });

    test.each([
        ["reload", false], ["back_forward", false], ["reload", true], ["back_forward", true],
    ])("not on a %s (legacy API only: %s): the browser restores where the user was", (type, legacy) => {
        reachedBy(type, { legacy });
        const img = page();
        expect(keepCommentsInView(document, window)).toBe(false);
        img.dispatchEvent(new Event("load"));
        pageLoad();
        expect(scrolls).toEqual([]);
    });

    test("on a link's navigation, by either API", () => {
        reachedBy("navigate");
        page();
        expect(keepCommentsInView(document, window)).toBe(true);
        pageLoad();
        reachedBy("navigate", { legacy: true });
        page();
        expect(keepCommentsInView(document, window)).toBe(true);
        pageLoad();
        expect(scrolls).toHaveLength(2);
    });

    test("once the page has loaded, after the browser's own scroll has settled", () => {
        page({ top: -300 }); // above the view: the browser's smooth scroll went past it
        let y = 0;
        Object.defineProperty(window, "pageYOffset", { get: () => y, configurable: true });
        keepCommentsInView(document, window);
        window.dispatchEvent(new Event("load"));
        for (let i = 0; i < 5; i++) { y += 40; jest.advanceTimersByTime(50); }
        expect(scrolls).toEqual([]); // still moving
        jest.advanceTimersByTime(150);
        expect(scrolls).toEqual([["comments", INSTANT]]);
    });

    test("not when the user scrolls while it settles", () => {
        page();
        keepCommentsInView(document, window);
        window.dispatchEvent(new Event("load"));
        document.body.dispatchEvent(new Event("wheel", { bubbles: true }));
        jest.advanceTimersByTime(500);
        expect(scrolls).toEqual([]);
    });

    test("watches nothing without #comments or without the comments", () => {
        page({ hash: "" });
        expect(keepCommentsInView(document, window)).toBe(false);
        page({ hash: "#top" });
        expect(keepCommentsInView(document, window)).toBe(false);
        page({ comments: false });
        expect(keepCommentsInView(document, window)).toBe(false);
        pageLoad();
        expect(scrolls).toEqual([]);
    });
});
