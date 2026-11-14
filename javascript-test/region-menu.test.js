/**
 * @jest-environment jsdom
 * @jest-environment-options {"url": "https://jury.example/gallery/round/1/user/2/page/1"}
 */
"use strict";

// A round gallery's region menu (region-menu.js): its regions are read on its first opening
// only, listed with their counts, kept for the page; a failure keeps the page's own items and
// says so, and the next opening tries again. The menu below is regionMenu.scala.html's, as
// rendered in the navbar for a juror in a region; it is a Bootstrap 5 dropdown, opened and closed here
// by Bootstrap's own script (the bundle, as the pages load it), with and without jQuery.

const path = require("path");

const bootstrap = require("bootstrap/dist/js/bootstrap.bundle.js");
const SCRIPT = path.join(__dirname, "..", "public", "javascripts", "region-menu.js");
const REGIONS_URL = "/regions/round/1/user/2/region/80";

const MENU = `
<nav class="navbar navbar-expand" data-bs-theme="dark">
<ul class="navbar-nav">
    <li class="nav-item dropdown region-menu" id="regionmenu" data-regions-url="${REGIONS_URL}"
        data-loading="LOADING" data-failed="FAILED">
        <a class="nav-link dropdown-toggle" href="/gallery/round/1/user/2/page/1" data-bs-toggle="dropdown" role="button"
            aria-expanded="false">Region: <b>Kyiv</b></a>
        <ul class="dropdown-menu region-menu-list">
            <li><a class="dropdown-item" href="/gallery/round/1/user/2/page/1">All</a></li>
            <li><a class="dropdown-item active" href="/gallery/round/1/user/2/region/80/page/1">Kyiv</a></li>
            <li class="region-menu-status dropdown-item-text"></li>
        </ul>
    </li>
</ul>
</nav>`;

const REGIONS = {
    current: "80",
    all: { name: "All", url: "/gallery/round/1/user/2/page/1" },
    regions: [
        { id: "07", name: "Volyn", count: 12, kind: "Region", url: "/gallery/round/1/user/2/region/07/page/1" },
        { id: "80", name: "Kyiv", count: 0, kind: "Region", url: "/gallery/round/1/user/2/region/80/page/1" },
        { id: "WLE", name: "Nature <b>reserves</b>", count: null, kind: "Nomination",
          url: "/gallery/round/1/user/2/region/WLE/page/1" }
    ]
};

/** A fetch answer: JSON with the status. */
function answer(status, body) {
    return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) });
}

/** Lets the fetch's promise chain run. */
async function settle() {
    await new Promise(resolve => setTimeout(resolve, 0));
    await new Promise(resolve => setTimeout(resolve, 0));
}

function menu() {
    return document.getElementById("regionmenu");
}

function toggle() {
    return menu().querySelector("[data-bs-toggle=dropdown]");
}

/** Opens the menu as a click on its toggle does (Bootstrap's data API); if it is open, closes it. */
async function click() {
    toggle().click();
    await settle();
}

/** Opens the menu (Bootstrap's show.bs.dropdown, dispatched on the toggle). */
async function open() {
    bootstrap.Dropdown.getOrCreateInstance(toggle()).show();
    await settle();
}

async function close() {
    bootstrap.Dropdown.getOrCreateInstance(toggle()).hide();
    await settle();
}

/** The menu's items: text, link and classes (the item's and its link's, but dropdown-item). */
function items() {
    return Array.from(menu().querySelectorAll(".region-menu-list > li")).map(li => {
        const a = li.querySelector("a");
        const classes = li.querySelector("hr.dropdown-divider") ? "divider"
            : [li.className, a ? a.className : ""].join(" ").split(/\s+/)
                .filter(c => c && c !== "dropdown-item" && c !== "dropdown-item-text" && c !== "region-menu-status").join(" ");
        return { text: li.textContent.trim(), href: a && a.getAttribute("href"), classes };
    });
}

beforeEach(() => {
    document.body.innerHTML = MENU;
    global.fetch = jest.fn();
    window.jQuery = window.$ = require("jquery");
    jest.isolateModules(() => require(SCRIPT));
});

afterEach(() => {
    delete global.fetch;
    if (window.jQuery) window.jQuery(document).off();
});

test("reads nothing until the menu is opened", async () => {
    await settle();
    expect(fetch).not.toHaveBeenCalled();
    expect(items().map(i => i.text)).toEqual(["All", "Kyiv", ""]);
});

test("on its first opening reads the regions as a script request, saying it is loading", async () => {
    let respond;
    fetch.mockReturnValue(new Promise(resolve => { respond = resolve; }));

    await open();

    expect(fetch).toHaveBeenCalledTimes(1);
    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe(REGIONS_URL);
    expect(options.credentials).toBe("same-origin");
    // a script request: the server answers JSON and never switches the session's contest
    expect(options.headers).toEqual({ "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" });
    expect(menu().querySelector(".region-menu-status").textContent).toBe("LOADING");

    respond(await answer(200, REGIONS));
    await settle();
    expect(menu().querySelector(".region-menu-status")).toBeNull();
});

test("lists all, then the regions with their counts, the region shown active, kinds apart", async () => {
    fetch.mockReturnValue(answer(200, REGIONS));

    await open();

    expect(items()).toEqual([
        { text: "All", href: "/gallery/round/1/user/2/page/1", classes: "" },
        { text: "Volyn (12)", href: "/gallery/round/1/user/2/region/07/page/1", classes: "" },
        { text: "Kyiv (0)", href: "/gallery/round/1/user/2/region/80/page/1", classes: "active" },
        { text: "", href: null, classes: "divider" },
        // no count (not counted): the name alone; a name is text, never markup
        { text: "Nature <b>reserves</b>", href: "/gallery/round/1/user/2/region/WLE/page/1",
          classes: "region-nomination" }
    ]);
    expect(menu().querySelector(".region-menu-list b")).toBeNull();
    // the place shown marked as this page, as the navbar's other menus mark theirs
    const current = Array.from(menu().querySelectorAll(".region-menu-list a[aria-current]"));
    expect(current.map(a => [a.textContent, a.getAttribute("aria-current")])).toEqual([["Kyiv (0)", "page"]]);
});

test("marks all active when no region is shown", async () => {
    fetch.mockReturnValue(answer(200, { ...REGIONS, current: null }));

    await open();

    expect(items().filter(i => i.classes.includes("active")).map(i => i.text)).toEqual(["All"]);
});

test("keeps the regions for the page: opening it again reads nothing", async () => {
    fetch.mockReturnValue(answer(200, REGIONS));

    await open();
    await close();
    await open();

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(items().map(i => i.text)).toEqual(["All", "Volyn (12)", "Kyiv (0)", "", "Nature <b>reserves</b>"]);
});

test("a second toggle while loading reads nothing more", async () => {
    fetch.mockReturnValue(new Promise(() => {}));

    await open();
    await open();

    expect(fetch).toHaveBeenCalledTimes(1);
});

test.each([
    ["an error status", () => answer(500, { error: "server_error", reference: "0a1b2c3d" })],
    ["a refusal", () => answer(403, { error: "forbidden", reference: "0a1b2c3d" })],
    ["no answer", () => Promise.reject(new TypeError("Failed to fetch"))]
])("on %s keeps all and the region shown, says so, and tries again on the next opening",
    async (_, failure) => {
        fetch.mockImplementationOnce(failure).mockReturnValue(answer(200, REGIONS));

        await open();

        expect(items().map(i => [i.text, i.href])).toEqual([
            ["All", "/gallery/round/1/user/2/page/1"],
            ["Kyiv", "/gallery/round/1/user/2/region/80/page/1"],
            ["FAILED", null]
        ]);

        await close();
        await open();

        expect(fetch).toHaveBeenCalledTimes(2);
        expect(items().map(i => i.text)).toEqual(["All", "Volyn (12)", "Kyiv (0)", "", "Nature <b>reserves</b>"]);
    });

test("closing the menu reads nothing", async () => {
    await close();
    expect(fetch).not.toHaveBeenCalled();
});

test("a click on the toggle opens it and reads the regions", async () => {
    fetch.mockReturnValue(answer(200, REGIONS));

    await click();

    expect(toggle().getAttribute("aria-expanded")).toBe("true");
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(items().map(i => i.text)).toEqual(["All", "Volyn (12)", "Kyiv (0)", "", "Nature <b>reserves</b>"]);
});

test("works without jQuery: Bootstrap 5 then dispatches its events natively only", async () => {
    document.body.innerHTML = MENU;
    delete window.jQuery;
    delete window.$;
    jest.isolateModules(() => require(SCRIPT));
    fetch.mockReturnValue(answer(200, REGIONS));

    await open();

    expect(fetch).toHaveBeenCalledTimes(1);
});
