/**
 * @jest-environment jsdom
 */
"use strict";

// The category fields' Commons suggestions (category-suggest.js) on Tom Select, the build the
// pages load (tom-select.complete), with fetch mocked: the API request, the pause in typing and
// the minimum length before it, the full Category: titles stored, an older answer dropped, and
// the field keeping what is typed when Commons fails or is slow.

const path = require("path");

const SCRIPT = path.join(__dirname, "..", "public", "javascripts", "category-suggest.js");

let suggest;

beforeAll(() => {
    window.TomSelect = require("tom-select/dist/js/tom-select.complete.js");
    suggest = require(SCRIPT);
});

beforeEach(() => {
    jest.useFakeTimers();
    window.fetch = jest.fn(() => new Promise(() => {})); // unanswered, unless a test answers
    // jsdom's document has focus only while an element has; Tom Select ignores a blur without it
    jest.spyOn(document, "hasFocus").mockReturnValue(true);
});

afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
    delete window.fetch;
});

/** An API answer with these titles. */
function answer(titles) {
    return {
        ok: true,
        json: () => Promise.resolve({
            batchcomplete: true,
            query: { prefixsearch: titles.map((title, i) => ({ ns: 14, title, pageid: i + 1 })) }
        })
    };
}

/** A fetch answered by hand: `resolve(response)`, `reject(error)`; rejected when aborted. */
function deferredFetch() {
    const calls = [];
    window.fetch.mockImplementation((url, init) => new Promise((resolve, reject) => {
        const call = { url, init, resolve, reject };
        if (init && init.signal) init.signal.addEventListener("abort", () => reject(new Error("aborted")));
        calls.push(call);
    }));
    return calls;
}

async function flush() {
    for (let i = 0; i < 10; i++) await Promise.resolve();
}

function field(mode = "category", value = "", extra = "") {
    document.body.innerHTML = `<form><label for="source">Category</label>` +
        `<input type="text" id="source" name="source" class="form-control" value="${value}" data-category-suggest="${mode}"${extra}></form>`;
    return document.getElementById("source");
}

function type(ts, text) {
    ts.control_input.focus();
    ts.control_input.value = text;
    ts.control_input.dispatchEvent(new Event("input"));
}

function shown(ts) {
    return Array.from(ts.dropdown_content.querySelectorAll(".option")).map(o => o.textContent);
}

function params(url) {
    const u = new URL(url);
    return Object.fromEntries(u.searchParams.entries());
}

describe("the request and its answer", () => {
    test("asks Commons' prefix search in the Category namespace, anonymously with CORS", () => {
        const url = suggest.searchUrl("Wiki Loves Earth");
        expect(url.startsWith("https://commons.wikimedia.org/w/api.php?")).toBe(true);
        expect(params(url)).toEqual({
            action: "query", list: "prefixsearch", psnamespace: "14", pslimit: "10",
            pssearch: "Wiki Loves Earth", format: "json", formatversion: "2", origin: "*"
        });
    });

    test("the name without a Category: prefix, in any case", () => {
        expect(suggest.categoryName("  Category:Wiki Loves Earth ")).toBe("Wiki Loves Earth");
        expect(suggest.categoryName("category: Kyiv")).toBe("Kyiv");
        expect(suggest.categoryName("Kyiv")).toBe("Kyiv");
    });

    test("two characters at least; a mixed field only once its text starts with Category:", () => {
        expect(suggest.shouldSuggest("W", "category")).toBe(false);
        expect(suggest.shouldSuggest("Wi", "category")).toBe(true);
        expect(suggest.shouldSuggest("Category:W", "category")).toBe(false);
        expect(suggest.shouldSuggest("Category:Wi", "category")).toBe(true);
        expect(suggest.shouldSuggest("Wiki Loves", "mixed")).toBe(false);
        expect(suggest.shouldSuggest("Commons:Wiki Loves Earth 2016", "mixed")).toBe(false);
        expect(suggest.shouldSuggest("Category:W", "mixed")).toBe(false);
        expect(suggest.shouldSuggest("category:Wi", "mixed")).toBe(true);
    });

    test("only the Category: titles of a well-formed answer", () => {
        expect(suggest.titles({ query: { prefixsearch: [{ title: "Category:A" }, { title: "File:B.jpg" }, {}] } }))
            .toEqual(["Category:A"]);
        expect(suggest.titles({ error: { code: "x" } })).toEqual([]);
        expect(suggest.titles(null)).toEqual([]);
    });
});

describe("categorySuggest", () => {
    test("asks once typing pauses for 250 ms", () => {
        const ts = suggest.categorySuggest(field());
        type(ts, "Wi");
        jest.advanceTimersByTime(100);
        type(ts, "Wik");
        jest.advanceTimersByTime(249);
        expect(window.fetch).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1);
        expect(window.fetch).toHaveBeenCalledTimes(1);
        expect(params(window.fetch.mock.calls[0][0]).pssearch).toBe("Wik");
        expect(params(window.fetch.mock.calls[0][0]).origin).toBe("*");
        expect(window.fetch.mock.calls[0][1].credentials).toBe("omit");
    });

    test("not for one character", () => {
        const ts = suggest.categorySuggest(field());
        type(ts, "W");
        jest.advanceTimersByTime(1000);
        expect(window.fetch).not.toHaveBeenCalled();
    });

    test("shows the full titles, typed with or without the prefix, and stores the chosen one", async () => {
        window.fetch
            .mockResolvedValueOnce(answer(["Category:Wiki Loves Earth 2016", "Category:Wiki Loves Earth 2016 in Kyiv, Ukraine"]))
            .mockResolvedValueOnce(answer(["Category:Wiki Loves Earth 2016 in Kyiv, Ukraine", "Category:Wiki Loves Earth 2016 in Ghana"]));
        const input = field();
        const ts = suggest.categorySuggest(input);

        type(ts, "category:wiki loves earth");
        jest.advanceTimersByTime(250);
        await flush();
        expect(params(window.fetch.mock.calls[0][0]).pssearch).toBe("wiki loves earth");
        expect(shown(ts)).toEqual(["Category:Wiki Loves Earth 2016", "Category:Wiki Loves Earth 2016 in Kyiv, Ukraine"]);

        type(ts, "Wiki Loves Earth 2016 in");
        jest.advanceTimersByTime(250);
        await flush();
        expect(params(window.fetch.mock.calls[1][0]).pssearch).toBe("Wiki Loves Earth 2016 in");
        expect(shown(ts)).toEqual(["Category:Wiki Loves Earth 2016 in Kyiv, Ukraine", "Category:Wiki Loves Earth 2016 in Ghana"]);
        expect(Object.keys(ts.options)).toHaveLength(2); // the first answer's are gone

        ts.addItem("Category:Wiki Loves Earth 2016 in Kyiv, Ukraine");
        expect(input.value).toBe("Category:Wiki Loves Earth 2016 in Kyiv, Ukraine");
    });

    test("Enter keeps the text typed; Down and Enter take the first suggestion", async () => {
        window.fetch.mockResolvedValue(answer(["Category:Wiki Loves Earth 2016"]));
        const input = field();
        const ts = suggest.categorySuggest(input);
        const key = (keyCode) => ts.control_input.dispatchEvent(new KeyboardEvent("keydown", { keyCode, bubbles: true }));

        type(ts, "Wiki Loves");
        jest.advanceTimersByTime(250);
        await flush();
        expect(ts.activeOption.classList.contains("create")).toBe(true);
        expect(ts.activeOption.textContent).toBe("Wiki Loves");
        key(13);
        expect(input.value).toBe("Wiki Loves");

        ts.clear();
        type(ts, "Wiki Loves E");
        jest.advanceTimersByTime(250);
        await flush();
        key(40);
        key(13);
        expect(input.value).toBe("Category:Wiki Loves Earth 2016");
    });

    test("a title is text, never markup", async () => {
        window.fetch.mockResolvedValue(answer(["Category:<b>x</b>"]));
        const ts = suggest.categorySuggest(field());
        type(ts, "Category:<b>");
        jest.advanceTimersByTime(250);
        await flush();
        expect(ts.dropdown_content.querySelector(".option b")).toBeNull();
    });

    test("drops an older answer arriving after a newer request", async () => {
        const calls = deferredFetch();
        const ts = suggest.categorySuggest(field());
        type(ts, "Wiki");
        jest.advanceTimersByTime(250);
        type(ts, "Wiki Loves");
        jest.advanceTimersByTime(250);
        expect(calls.length).toBe(2);
        expect(calls[0].init.signal.aborted).toBe(true);

        calls[1].resolve(answer(["Category:Wiki Loves Monuments"]));
        calls[0].resolve(answer(["Category:Wikipedia"]));
        await flush();
        expect(Object.keys(ts.options)).toEqual(["Category:Wiki Loves Monuments"]);
        expect(ts.loading).toBe(0);
    });

    test("an older answer that comes first is dropped as well", async () => {
        const abortController = window.AbortController;
        window.AbortController = undefined; // no abort: only the request numbers drop it
        try {
            const calls = deferredFetch();
            const ts = suggest.categorySuggest(field());
            type(ts, "Wiki");
            jest.advanceTimersByTime(250);
            type(ts, "Wiki Loves");
            jest.advanceTimersByTime(250);
            calls[0].resolve(answer(["Category:Wikipedia"]));
            await flush();
            expect(Object.keys(ts.options)).toEqual([]);
            calls[1].resolve(answer(["Category:Wiki Loves Monuments"]));
            await flush();
            expect(Object.keys(ts.options)).toEqual(["Category:Wiki Loves Monuments"]);
        } finally {
            window.AbortController = abortController;
        }
    });

    test.each([
        ["a network error", () => window.fetch.mockRejectedValue(new TypeError("Failed to fetch"))],
        ["a server error", () => window.fetch.mockResolvedValue({ ok: false, status: 503, json: () => Promise.reject(new Error()) })],
        ["an answer that isn't JSON", () => window.fetch.mockResolvedValue({ ok: true, json: () => Promise.reject(new SyntaxError()) })]
    ])("after %s, keeps what is typed, without a message", async (_, failing) => {
        failing();
        const alert = jest.spyOn(window, "alert").mockImplementation(() => {});
        const input = field();
        const ts = suggest.categorySuggest(input);
        type(ts, "Category:Wiki Loves Earth 2016");
        jest.advanceTimersByTime(250);
        await flush();
        expect(Object.keys(ts.options)).toEqual([]);
        expect(ts.loading).toBe(0);
        expect(ts.wrapper.classList.contains("loading")).toBe(false);

        ts.blur();
        jest.runOnlyPendingTimers();
        expect(input.value).toBe("Category:Wiki Loves Earth 2016");
        expect(alert).not.toHaveBeenCalled();
    });

    test("gives up on a slow Commons after five seconds", async () => {
        const calls = deferredFetch();
        const ts = suggest.categorySuggest(field());
        type(ts, "Wiki");
        jest.advanceTimersByTime(250);
        expect(ts.loading).toBe(1);
        jest.advanceTimersByTime(5000);
        await flush();
        expect(calls[0].init.signal.aborted).toBe(true);
        expect(ts.loading).toBe(0);
    });

    test("a mixed field keeps a page title, without asking Commons", () => {
        const input = field("mixed");
        const ts = suggest.categorySuggest(input);
        type(ts, "Commons:Wiki Loves Earth 2016");
        jest.advanceTimersByTime(1000);
        expect(window.fetch).not.toHaveBeenCalled();
        ts.blur();
        jest.runOnlyPendingTimers();
        expect(input.value).toBe("Commons:Wiki Loves Earth 2016");
    });

    test("a mixed field asks once the text starts with Category:, for the name only", () => {
        window.fetch.mockResolvedValue(answer([]));
        const ts = suggest.categorySuggest(field("mixed"));
        type(ts, "Category:Images from Wiki");
        jest.advanceTimersByTime(250);
        expect(params(window.fetch.mock.calls[0][0]).pssearch).toBe("Images from Wiki");
    });

    test("keeps the value the page comes with, a comma in it included", () => {
        const input = field("category", "Category:Paris, France");
        const ts = suggest.categorySuggest(input);
        expect(ts.items).toEqual(["Category:Paris, France"]);
        expect(input.value).toBe("Category:Paris, France");
    });

    test("leaves a readonly field, and does nothing without Tom Select or fetch", () => {
        expect(suggest.categorySuggest(field("category", "Category:A", " readonly"))).toBeNull();
        expect(document.querySelector(".ts-wrapper")).toBeNull();
        expect(suggest.categorySuggest(null)).toBeNull();
        delete window.fetch;
        expect(suggest.categorySuggest(field())).toBeNull();
        window.fetch = jest.fn();
        const ts = window.TomSelect;
        delete window.TomSelect;
        try { expect(suggest.categorySuggest(field())).toBeNull(); } finally { window.TomSelect = ts; }
    });

    test("hangs the list from the body when the field asks for it, with its aria and keys", async () => {
        window.fetch.mockResolvedValue(answer(["Category:Wiki Loves Earth", "Category:Wiki Loves Monuments"]));
        const input = field("mixed", "", ' data-category-suggest-dropdown-parent="body"');
        const ts = suggest.categorySuggest(input);
        expect(ts.settings.dropdownParent).toBe("body");
        expect(ts.dropdown.parentNode).toBe(document.body);
        expect(ts.wrapper.contains(ts.dropdown)).toBe(false);
        // the input still names its list, and the keys still reach the suggestions
        expect(document.getElementById(ts.control_input.getAttribute("aria-controls"))).toBe(ts.dropdown_content);
        type(ts, "Category:Wiki");
        jest.advanceTimersByTime(250);
        await flush();
        expect(shown(ts)).toEqual(expect.arrayContaining(["Category:Wiki Loves Earth", "Category:Wiki Loves Monuments"]));
        const key = (keyCode) => ts.control_input.dispatchEvent(new KeyboardEvent("keydown", { keyCode, bubbles: true }));
        key(40);
        key(13);
        expect(input.value).toBe("Category:Wiki Loves Earth");
    });

    test("keeps the list in the field's wrapper otherwise, whatever else the attribute says", () => {
        const plain = suggest.categorySuggest(field("mixed"));
        expect(plain.settings.dropdownParent).toBeNull();
        expect(plain.wrapper.contains(plain.dropdown)).toBe(true);
        const other = suggest.categorySuggest(field("mixed", "", ' data-category-suggest-dropdown-parent="#elsewhere"'));
        expect(other.settings.dropdownParent).toBeNull();
        expect(other.wrapper.contains(other.dropdown)).toBe(true);
    });

    test("places a body list under its field again when the table's box scrolls, while open, until destroyed", () => {
        document.body.innerHTML = '<div class="table-responsive" id="box"><table><tbody><tr><td>' +
            '<input type="text" id="images" class="form-control" data-category-suggest="mixed" ' +
            'data-category-suggest-dropdown-parent="body"></td></tr></tbody></table></div>';
        const box = document.getElementById("box");
        const ts = suggest.categorySuggest(document.getElementById("images"));
        const placed = jest.spyOn(ts, "positionDropdown");
        const scroll = () => box.dispatchEvent(new Event("scroll"));

        scroll();
        expect(placed).not.toHaveBeenCalled(); // closed: nothing to place
        ts.open();
        placed.mockClear();
        scroll();
        expect(placed).toHaveBeenCalledTimes(1);
        ts.close();
        scroll();
        expect(placed).toHaveBeenCalledTimes(1);

        const removed = jest.spyOn(box, "removeEventListener");
        ts.destroy();
        expect(removed).toHaveBeenCalledWith("scroll", expect.any(Function));
        ts.isOpen = true; // even if it were still thought open, the box no longer moves it
        scroll();
        expect(placed).toHaveBeenCalledTimes(1);
    });

    test("closes the list once the box scrolls its field partly out of view, places it while wholly in view", () => {
        document.body.innerHTML = '<div class="table-responsive" id="box"><table><tbody><tr><td>' +
            '<input type="text" id="images" class="form-control" data-category-suggest="mixed" ' +
            'data-category-suggest-dropdown-parent="body"></td></tr></tbody></table></div>';
        const box = document.getElementById("box");
        const ts = suggest.categorySuggest(document.getElementById("images"));
        const placed = jest.spyOn(ts, "positionDropdown");
        const rect = (left, right) => ({ left, right, top: 0, bottom: 38, width: right - left, height: 38, x: left, y: 0 });
        jest.spyOn(box, "getBoundingClientRect").mockReturnValue(rect(12, 378)); // a phone's box
        const field = jest.spyOn(ts.wrapper, "getBoundingClientRect");
        const scroll = () => box.dispatchEvent(new Event("scroll"));

        field.mockReturnValue(rect(40, 280)); // wholly in view: placed under it
        ts.open();
        placed.mockClear();
        scroll();
        expect(placed).toHaveBeenCalledTimes(1);
        expect(ts.isOpen).toBe(true);

        field.mockReturnValue(rect(200, 440)); // its right part past the box's right edge: closed
        scroll();
        expect(ts.isOpen).toBe(false);
        expect(placed).toHaveBeenCalledTimes(1);

        ts.open();
        field.mockReturnValue(rect(-30, 210)); // past the left edge: closed too
        scroll();
        expect(ts.isOpen).toBe(false);

        ts.open();
        field.mockReturnValue(rect(11.5, 378.5)); // within the pixel's slack: still placed
        placed.mockClear();
        scroll();
        expect(ts.isOpen).toBe(true);
        expect(placed).toHaveBeenCalledTimes(1);
    });

    test("listens to no box for a list kept in its wrapper, or a field outside such a box", () => {
        document.body.innerHTML = '<div class="table-responsive" id="box">' +
            '<input type="text" id="a" class="form-control" data-category-suggest="mixed"></div>' +
            '<input type="text" id="b" class="form-control" data-category-suggest="mixed" data-category-suggest-dropdown-parent="body">';
        const added = jest.spyOn(document.getElementById("box"), "addEventListener");
        const inBox = suggest.categorySuggest(document.getElementById("a"));
        const outside = suggest.categorySuggest(document.getElementById("b"));
        expect(added.mock.calls.filter(([type]) => type === "scroll")).toEqual([]);
        expect(() => { inBox.destroy(); outside.destroy(); }).not.toThrow();
    });

    test("init takes the inputs marked for it only", () => {
        document.body.innerHTML = `<input id="a" data-category-suggest="category"><input id="b" data-category-suggest="mixed"><input id="c">`;
        suggest.init();
        expect(document.getElementById("a").classList.contains("tomselected")).toBe(true);
        expect(document.getElementById("b").classList.contains("tomselected")).toBe(true);
        expect(document.getElementById("c").classList.contains("tomselected")).toBe(false);
        expect(document.querySelectorAll(".wlx-category-suggest").length).toBe(2);
    });
});
