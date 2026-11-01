/**
 * @jest-environment jsdom
 * @jest-environment-options {"url": "https://jury.example/large/round/1/user/1/pageid/107"}
 */
"use strict";

// Keyboard voting and moving in the large views, and the number by their stars, in jsdom on
// the pages the app renders (javascript-test/fixtures/large, kept in step by
// views.LargeViewFixturesSpec), as served over https://jury.example. A vote must post the
// vote form, with its CSRF token, to this same origin: behind HTTPS a form posted to http://
// is redirected as a GET and the vote is lost (c4a6c6b1). Runs offline: the webjar's jQuery is the same version from
// node_modules; Bootstrap's script, which no key uses, is left out.

const fs = require("fs");
const path = require("path");

const FIXTURES = path.join(__dirname, "fixtures", "large");
const PUBLIC = path.join(__dirname, "..", "public");
const ORIGIN = "https://jury.example";

/** The scripts a page loads, by src: the local file to run in its place, or null to skip. */
function localScript(src) {
    if (src === "/webjars/jquery/3.7.1/jquery.min.js") {
        return require.resolve("jquery/dist/jquery.min.js");
    }
    if (src === "/webjars/bootstrap/5.3.8/js/bootstrap.bundle.min.js") {
        return null; // Bootstrap's widgets: no key handler uses them
    }
    const asset = /^\/assets\/(.+)$/.exec(src);
    if (asset) return path.join(PUBLIC, asset[1]);
    throw new Error("A large view loads a script the test can't run offline: " + src);
}

/** A URL the page votes or moves to, if it is on the page's own origin, by its protocol. */
function sameOriginUrl(url) {
    const resolved = new URL(url, document.baseURI);
    if (resolved.origin !== window.location.origin || resolved.protocol !== window.location.protocol) {
        throw new Error(`${url} resolves to ${resolved.origin}, not this page's ${window.location.origin}`);
    }
    return resolved;
}

const KEY_CODES = {
    space: 32, del: 46, esc: 27,
    pageup: 33, pagedown: 34, end: 35, home: 36, up: 38, down: 40
};

let submits; // the vote form's submissions: { form, action }
let navigations; // window.location assignments

/** The app's own scripts, run with the recording `window` (they move by `window.location`). */
const RECORDED = new Set(["large-view.js", "large-keys.js", "large-stars.js", "large-rate.js", "large-sections.js"]);

/**
 * Loads a fixture into the document and runs its scripts, in order: the local ones, the app's
 * own (RECORDED) with a `window` whose `location` assignments are recorded (jsdom doesn't
 * navigate), then the inline ones (the side sections' restore) with it too.
 */
async function loadPage(name, transform = html => html) {
    if (window.jQuery) window.jQuery(document).off();
    delete window.jQuery;
    delete window.$;

    const html = transform(fs.readFileSync(path.join(FIXTURES, name), "utf8"));
    const inner = /<html[^>]*>([\s\S]*)<\/html>/i.exec(html);
    if (!inner) throw new Error(name + " has no <html> element");
    document.documentElement.innerHTML = inner[1]; // scripts added so don't run

    const recordingWindow = new Proxy(window, {
        get(target, prop) {
            const value = Reflect.get(target, prop);
            // the browser's own functions need the real window as `this`; jQuery keeps its
            // properties ($.fn) only unbound
            return typeof value === "function" && /\[native code\]/.test(Function.prototype.toString.call(value))
                ? value.bind(target) : value;
        },
        set(target, prop, value) {
            if (prop === "location") {
                navigations.push(String(value));
                return true;
            }
            return Reflect.set(target, prop, value);
        }
    });

    const scripts = Array.from(document.querySelectorAll("script"));
    for (const script of scripts.filter(s => s.src)) {
        const file = localScript(script.getAttribute("src"));
        if (!file) continue;
        const code = fs.readFileSync(file, "utf8");
        if (RECORDED.has(path.basename(file))) new Function("window", code).call(recordingWindow, recordingWindow);
        else (0, eval)(code);
    }

    // the only inline scripts, JSON data aside: the layout's theme script and the side
    // sections' restore; no vote or move is in a script
    const inline = scripts.filter(s => !s.src && s.type !== "application/json").map(s => s.textContent);
    expect(inline.filter(s => !s.includes("prefers-color-scheme")).map(s => s.trim()))
        .toEqual(["if (window.wlxLargeSections) wlxLargeSections.restore(document);"]);
    new Function("window", inline.join(";")).call(recordingWindow, recordingWindow);

    // let $(document).ready handlers run (the stars), within this page's test:
    // jQuery 3 runs them asynchronously, in order, and reports their errors in a later task
    await new Promise(resolve => window.jQuery(() => setTimeout(resolve, 0)));
}

/** Presses `key` on `target`: the page's body, or an element with the focus. */
function press(key, target = document.body) {
    const keyCode = typeof key === "number" ? key : KEY_CODES[key];
    if (keyCode === undefined) throw new Error("No key code for " + key);
    target.dispatchEvent(new KeyboardEvent("keydown", {
        keyCode, which: keyCode, charCode: 0, bubbles: true, cancelable: true
    }));
}

/** A side section's toggle (large-sections.js), focused. */
function focusedToggle(id) {
    const toggle = document.querySelector(`button[aria-controls=${id}]`);
    toggle.focus();
    return toggle;
}

const digit = n => 48 + n;

beforeEach(() => {
    submits = [];
    navigations = [];
    jest.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(function () {
        submits.push({ form: this, action: this.getAttribute("action") });
    });
});

afterEach(() => {
    jest.restoreAllMocks();
});

describe("the same-origin guard", () => {

    test("accepts a path and an https URL on the page's origin", () => {
        expect(sameOriginUrl("/large/round/1/pageid/107/select/1").href)
            .toBe("https://jury.example/large/round/1/pageid/107/select/1");
        expect(() => sameOriginUrl("https://jury.example/large/round/1/pageid/107/select/1")).not.toThrow();
    });

    test("rejects an http:// URL to the same host, as the keys voted to before c4a6c6b1", () => {
        expect(() => sameOriginUrl("http://jury.example/large/round/1/pageid/107/select/1"))
            .toThrow(/http:\/\/jury\.example, not this page's https:\/\/jury\.example/);
        expect(() => sameOriginUrl("//elsewhere.example/large/round/1/pageid/107/select/1")).toThrow();
    });

    test("fails a key vote of a page whose vote URL is http://, as the keys' were before c4a6c6b1", async () => {
        await loadPage("binary-own.html",
            html => html.split('data-vote-key-space="/large/').join('data-vote-key-space="http://jury.example/large/'));
        press("space");
        expect(submits).toHaveLength(1);
        expect(() => sameOriginUrl(submits[0].action)).toThrow(/not this page's https:\/\/jury\.example/);
    });
});

function expectVote(select, pageId = 107) {
    expect(submits).toHaveLength(1);
    const { form, action } = submits[0];
    expect(form.id).toBe("vote-form");
    expect(form.getAttribute("method")).toBe("post");
    expect(form.querySelector("input[type=hidden][name=csrfToken]").value).toBe("fixture-csrf-token");
    const url = sameOriginUrl(action);
    expect(url.protocol).toBe("https:");
    expect(url.pathname).toBe(`/large/round/1/pageid/${pageId}/select/${select}`);
}

function expectNavigation(pathname, hash = "") {
    expect(submits).toHaveLength(0);
    expect(navigations).toHaveLength(1);
    const url = sameOriginUrl(navigations[0]);
    expect(url.pathname).toBe(pathname);
    expect(url.hash).toBe(hash);
}

describe("a binary round, the juror's own large view of an unrated file", () => {

    beforeEach(() => loadPage("binary-own.html"));

    test("Space selects it: posts the vote form, with its CSRF token, to this origin", () => {
        press("space");
        expectVote(1);
        expect(navigations).toHaveLength(0);
    });

    test("Del rejects it", () => {
        press("del");
        expectVote(-1);
    });

    test("the select and reject buttons post the same votes", () => {
        document.querySelector(".btn-success[data-vote-url]").click();
        expect(sameOriginUrl(submits[0].action).pathname).toBe("/large/round/1/pageid/107/select/1");
        document.querySelector(".btn-danger[data-vote-url]").click();
        expect(sameOriginUrl(submits[1].action).pathname).toBe("/large/round/1/pageid/107/select/-1");
    });

    test.each([
        ["down", 108], ["up", 106], ["pagedown", 112], ["pageup", 102], ["home", 101], ["end", 113]
    ])("%s moves to file %i on this origin", (key, pageId) => {
        press(key);
        expectNavigation(`/large/round/1/user/1/pageid/${pageId}`);
    });

    test("Esc goes back to the gallery on this origin", () => {
        press("esc");
        expectNavigation("/gallery/round/1/user/1/region/all/pageid/107", "#107");
    });

    test.each(["large-info", "large-next"])("Space and Enter on the %s toggle open or close it, and don't vote", id => {
        const toggle = focusedToggle(id);
        press("space", toggle);
        press(13, toggle);
        expect(submits).toHaveLength(0);
        expect(navigations).toHaveLength(0);
    });

    test("Del and Down still vote and move with the focus on a toggle", () => {
        const toggle = focusedToggle("large-info");
        press("del", toggle);
        expectVote(-1);
        press("down", toggle);
        expect(navigations).toHaveLength(1);
        expect(sameOriginUrl(navigations[0]).pathname).toBe("/large/round/1/user/1/pageid/108");
    });

    test("a digit does nothing in a binary round", () => {
        press(digit(1));
        expect(submits).toHaveLength(0);
        expect(navigations).toHaveLength(0);
    });
});

describe("a rated round (1..5), the juror's own large view", () => {

    beforeEach(() => loadPage("rated-own.html"));

    test.each([1, 2, 3, 4, 5])("digit %i rates the file", n => {
        press(digit(n));
        expectVote(n);
    });

    test("a digit above the round's 5 does nothing", () => {
        for (const n of [6, 7, 8, 9]) press(digit(n));
        expect(submits).toHaveLength(0);
        expect(navigations).toHaveLength(0);
    });

    test("Space and Del don't vote in a rated round", () => {
        press("space");
        press("del");
        expect(submits).toHaveLength(0);
    });

    test("the stars are Bootstrap Icons: five empty ones and the cancel icon", () => {
        const stars = document.querySelectorAll("#rate-image i.wlx-star");
        expect(stars).toHaveLength(5);
        stars.forEach(star => expect(star.className).toBe("bi bi-star wlx-star"));
        expect(document.querySelector("#rate-image i.raty-cancel").className).toBe("raty-cancel bi bi-x-circle");
        expect(document.querySelectorAll("#rate-image img")).toHaveLength(0);
    });

    test.each([1, 3, 5])("clicking star %i rates the file, as its digit does", n => {
        window.jQuery("#rate-image i.wlx-star").eq(n - 1).trigger("click");
        expectVote(n);
    });

    test("clicking the cancel icon unrates the file", () => {
        window.jQuery("#rate-image i.raty-cancel").trigger("click");
        expectVote(0);
    });

    test("Down moves to the next file on this origin", () => {
        press("down");
        expectNavigation("/large/round/1/user/1/pageid/108");
    });

    test("a digit still rates the file with the focus on a toggle", () => {
        press(digit(3), focusedToggle("large-next"));
        expectVote(3);
    });
});

// The number above the stars (large-rate.js): the stored rate as rendered, the rate a click on
// a hovered star gives, and the stored one again when the pointer leaves the stars.

const number = (id = "rate-number") => document.getElementById(id).textContent;
const star = n => window.jQuery("#rate-image i.wlx-star").eq(n - 1);
const leave = () => window.jQuery("#rate-image").trigger("mouseleave");

/** The pointer over a half-star round's star `n`, in its left or right half: near the star's
 * left edge, or just right of its middle, where the score rounded to the nearest half (as the
 * click did) is a half star less than drawn. raty finds the half by the pointer's offset in
 * the star, 1em wide: jsdom lays nothing out, so each star is at 0 and the font size is set
 * here. */
function moveOver(n, half) {
    star(1)[0].style.fontSize = "16px";
    star(n).trigger(window.jQuery.Event("mousemove", { pageX: half === "left" ? 1 : 10 }));
}

describe("the number by a rated round's stars (1..5), the juror's own view of an unrated file", () => {

    beforeEach(() => loadPage("rated-own.html"));

    test("shows – for unrated, and tells screen readers so, not the hovered rates", () => {
        expect(number()).toBe("–");
        const shown = document.getElementById("rate-number");
        expect(shown.getAttribute("aria-hidden")).toBe("true");
        expect(shown.nextElementSibling.className).toBe("visually-hidden");
        expect(shown.nextElementSibling.textContent).toBe("large.rate.none");
    });

    test("shows the hovered star's rate, and – again when the pointer leaves the stars", () => {
        star(3).trigger("mouseover");
        expect(number()).toBe("3");
        star(5).trigger("mouseover");
        expect(number()).toBe("5");
        leave();
        expect(number()).toBe("–");
    });

    test("shows – over the cancel icon, which unrates", () => {
        star(4).trigger("mouseover");
        window.jQuery("#rate-image i.raty-cancel").trigger("mouseover");
        expect(number()).toBe("–");
    });

    test("gives the stars no tooltips, the number shows the rate", () => {
        document.querySelectorAll("#rate-image i.wlx-star").forEach(s => expect(s.title).toBe(""));
    });

    test("gives none to stars 11 to 20 either, in a round of 20", async () => {
        await loadPage("rated-own.html", html => html.split('data-number="5"').join('data-number="20"'));
        const stars = document.querySelectorAll("#rate-image i.wlx-star");
        expect(stars).toHaveLength(20);
        stars.forEach(s => expect(s.title).toBe(""));
        star(15).trigger("mouseover");
        expect(number()).toBe("15");
    });
});

describe("the number by a half-star round's stars (1..10 as five), the juror's own view of a file rated 7", () => {

    beforeEach(() => loadPage("rated-half-own.html"));

    test("shows 3.5, as the stars do", () => {
        expect(number()).toBe("3.5");
        expect(document.getElementById("rate-number").nextElementSibling.textContent).toBe("large.rate");
        const classes = Array.from(document.querySelectorAll("#rate-image i.wlx-star"), s => s.className);
        expect(classes).toEqual(["bi bi-star-fill wlx-star", "bi bi-star-fill wlx-star", "bi bi-star-fill wlx-star",
            "bi bi-star-half wlx-star", "bi bi-star wlx-star"]);
    });

    test("shows a half star over a star's left half and the whole star over its right half, as drawn", () => {
        moveOver(2, "left");
        expect(number()).toBe("1.5");
        expect(star(2)[0].className).toBe("bi bi-star-half wlx-star");
        moveOver(4, "right");
        expect(number()).toBe("4");
        expect(star(4)[0].className).toBe("bi bi-star-fill wlx-star");
        leave();
        expect(number()).toBe("3.5");
    });

    test.each([["left", 7], ["right", 8]])("a click on star 4's %s half gives the rate shown, %i of 10", (half, rate) => {
        moveOver(4, half);
        star(4).trigger("click");
        expectVote(rate);
    });
});

describe("the number by the stars in another juror's view of the rated round: read-only", () => {

    beforeEach(() => loadPage("rated-readonly.html"));

    test("shows the juror's rate, 4, and no hover changes it", () => {
        expect(number()).toBe("4");
        star(2).trigger("mouseover");
        expect(number()).toBe("4");
        star(2).trigger("click");
        expect(submits).toHaveLength(0);
        expect(document.getElementById("vote-form")).toBeNull();
    });
});

describe("another juror's large view of the binary round: read-only", () => {

    beforeEach(() => loadPage("binary-readonly.html"));

    test("has no vote form", () => {
        expect(document.getElementById("vote-form")).toBeNull();
    });

    test.each([["Space", "space"], ["Del", "del"], ["1", digit(1)]])("%s doesn't vote", (_, key) => {
        press(key);
        expect(submits).toHaveLength(0);
        expect(navigations).toHaveLength(0);
    });

    test("Down still moves, to the other juror's next file, on this origin", () => {
        press("down");
        expectNavigation("/large/round/1/user/2/pageid/108");
    });
});

describe("a contest file's large view", () => {

    beforeEach(() => loadPage("contest-file.html"));

    test("has no vote form, and no key votes", () => {
        expect(document.getElementById("vote-form")).toBeNull();
        press("space");
        press("del");
        press(digit(1));
        expect(submits).toHaveLength(0);
        expect(navigations).toHaveLength(0);
    });

    test.each([
        ["down", 108], ["up", 106], ["pagedown", 112], ["pageup", 102], ["home", 101], ["end", 113]
    ])("%s moves to file %i on this origin", (key, pageId) => {
        press(key);
        expectNavigation(`/contest/1/files/${pageId}`);
    });

    test("Esc goes back to the files page on this origin", () => {
        press("esc");
        expect(navigations).toHaveLength(1);
        expect(sameOriginUrl(navigations[0]).pathname).toBe("/contest/1/files");
    });
});
