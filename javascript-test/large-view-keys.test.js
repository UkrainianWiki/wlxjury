/**
 * @jest-environment jsdom
 * @jest-environment-options {"url": "https://jury.example/large/round/1/user/1/pageid/107"}
 */
"use strict";

// Keyboard voting and moving in the large views, pressed in jsdom on the pages the app
// renders (javascript-test/fixtures/large, kept in step by views.LargeViewFixturesSpec), as
// served over https://jury.example. A vote must post the vote form, with its CSRF token, to
// this same origin: behind HTTPS a form posted to http:// is redirected as a GET and the
// vote is lost (c4a6c6b1). Runs offline: the CDN's jQuery is the same version from
// node_modules; Bootstrap's script, which no key uses, is left out.

const fs = require("fs");
const path = require("path");

const FIXTURES = path.join(__dirname, "fixtures", "large");
const PUBLIC = path.join(__dirname, "..", "public");
const ORIGIN = "https://jury.example";

/** The scripts a page loads, by src: the local file to run in its place, or null to skip. */
function localScript(src) {
    if (src === "https://ajax.googleapis.com/ajax/libs/jquery/1.11.1/jquery.min.js") {
        return require.resolve("jquery/dist/jquery.min.js");
    }
    if (src === "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.1/js/bootstrap.min.js") {
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

/**
 * Loads a fixture into the document and runs its scripts: the local ones first, as the
 * page's inline scripts only use them once they run, then the inline ones, in order, with
 * a `window` whose `location` assignments are recorded (jsdom doesn't navigate).
 */
async function loadPage(name, transform = html => html) {
    if (window.jQuery) window.jQuery(document).off();
    delete window.jQuery;
    delete window.$;

    const html = transform(fs.readFileSync(path.join(FIXTURES, name), "utf8"));
    const inner = /<html[^>]*>([\s\S]*)<\/html>/i.exec(html);
    if (!inner) throw new Error(name + " has no <html> element");
    document.documentElement.innerHTML = inner[1]; // scripts added so don't run

    const scripts = Array.from(document.querySelectorAll("script"));
    for (const script of scripts.filter(s => s.src)) {
        const file = localScript(script.getAttribute("src"));
        if (file) (0, eval)(fs.readFileSync(file, "utf8"));
    }

    const recordingWindow = new Proxy(window, {
        get(target, prop) {
            const value = Reflect.get(target, prop);
            return typeof value === "function" ? value.bind(target) : value;
        },
        set(target, prop, value) {
            if (prop === "location") {
                navigations.push(String(value));
                return true;
            }
            return Reflect.set(target, prop, value);
        }
    });
    const inline = scripts.filter(s => !s.src).map(s => s.textContent).join("\n;\n");
    new Function("window", inline).call(recordingWindow, recordingWindow);

    // let $(document).ready handlers run (the stars, checkwidth.js)
    await new Promise(resolve => setTimeout(resolve, 0));
}

function press(key) {
    const keyCode = typeof key === "number" ? key : KEY_CODES[key];
    if (keyCode === undefined) throw new Error("No key code for " + key);
    document.body.dispatchEvent(new KeyboardEvent("keydown", {
        keyCode, which: keyCode, charCode: 0, bubbles: true, cancelable: true
    }));
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
            html => html.split('voteLarge("/large/').join('voteLarge("http://jury.example/large/'));
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

    test("Down moves to the next file on this origin", () => {
        press("down");
        expectNavigation("/large/round/1/user/1/pageid/108");
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
