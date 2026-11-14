/**
 * @jest-environment jsdom
 */
"use strict";

const { pluralForm, conflictsLabel, authorsLabel, applyCounts, loadRoundStatCounts } =
    require("../public/javascripts/roundstat-counts");

const URL = "/roundstat/7/counts";

/** The links as roundStat.scala.html renders them for an organizer: enabled, without counts. */
function page(lang = "en") {
    const uk = lang === "uk";
    document.body.innerHTML = `
        <p id="round-conflicts-link"><a href="/round/7/conflicts" data-roundstat-counts="${URL}" data-kind="conflicts"
            data-lang="${lang}"
            data-label-count="${uk ? "Конфлікт інтересів у цьому раунді ({0})" : "Conflicts of interest of this round ({0})"}"
            data-label-none="${uk ? "Конфлікт інтересів у цьому раунді: немає" : "Conflicts of interest of this round: none"}"
            data-files-one="${uk ? "{0} файл" : "{0} file"}" data-files-few="${uk ? "{0} файли" : "{0} files"}"
            data-files-many="${uk ? "{0} файлів" : "{0} files"}" data-files-other="${uk ? "{0} файлу" : "{0} files"}"
            data-disqualified-one="${uk ? "{0} дискваліфікований" : "{0} disqualified"}"
            data-disqualified-few="${uk ? "{0} дискваліфіковані" : "{0} disqualified"}"
            data-disqualified-many="${uk ? "{0} дискваліфікованих" : "{0} disqualified"}"
            data-disqualified-other="${uk ? "{0} дискваліфікованого" : "{0} disqualified"}"
            data-history="${uk ? "історія ({0})" : "history ({0})"}" data-history-limit="500">Conflicts of interest of this round</a></p>
        <p id="round-authors-link"><a href="/contest/1/authors?round=7" data-roundstat-counts="${URL}" data-kind="authors"
            data-lang="${lang}"
            data-label-count="Authors of this round ({0})" data-label-none="Authors of this round: none stored"
            data-label-none-unknown="Authors of this round: none stored · {0}"
            data-unknown-one="{0} file without an author" data-unknown-few="{0} files without an author"
            data-unknown-many="{0} files without an author" data-unknown-other="{0} files without an author">Authors of this round</a></p>`;
    return {
        conflicts: document.querySelector("#round-conflicts-link a"),
        authors: document.querySelector("#round-authors-link a")
    };
}

function enabled(link) {
    return link.hasAttribute("href") && !link.hasAttribute("aria-disabled") && !link.hasAttribute("tabindex");
}

function disabled(link) {
    return !link.hasAttribute("href") && link.getAttribute("aria-disabled") === "true" &&
        link.getAttribute("tabindex") === "-1";
}

describe("pluralForm, as views.Plural", () => {
    test("Ukrainian and Russian: one, few, many", () => {
        expect([1, 21, 101, 11, 2, 22, 12, 14, 5, 0, 111].map(n => pluralForm(n, "uk")))
            .toEqual(["one", "one", "one", "many", "few", "few", "many", "many", "many", "many", "many"]);
        expect(pluralForm(3, "ru")).toBe("few");
    });
    test("French one for 0 and 1; English one for 1", () => {
        expect([0, 1, 2].map(n => pluralForm(n, "fr"))).toEqual(["one", "one", "other"]);
        expect([0, 1, 2].map(n => pluralForm(n, "en"))).toEqual(["other", "one", "other"]);
    });
});

describe("the conflicts link", () => {
    test("open conflicts and disqualified files, a 0 part left out", () => {
        const { conflicts } = page();
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 3, disqualified: 2 });
        expect(conflicts.textContent).toBe("Conflicts of interest of this round (3 files · 2 disqualified)");
        expect(enabled(conflicts)).toBe(true);

        const second = page().conflicts;
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 1 });
        expect(second.textContent).toBe("Conflicts of interest of this round (1 disqualified)");
        expect(enabled(second)).toBe(true);
    });

    test("the language's plural form, of the files and the disqualified", () => {
        const { conflicts } = page("uk");
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 22, disqualified: 5 });
        expect(conflicts.textContent).toBe("Конфлікт інтересів у цьому раунді (22 файли · 5 дискваліфікованих)");
        const second = page("uk").conflicts;
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 21 });
        expect(second.textContent).toBe("Конфлікт інтересів у цьому раунді (21 дискваліфікований)");
    });

    test("a history longer than the page lists: its limit and a plus", () => {
        const { conflicts } = page();
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 0, history: 501 });
        expect(conflicts.textContent).toBe("Conflicts of interest of this round: none · history (500+)");
        const second = page().conflicts;
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 0, history: 500 });
        expect(second.textContent).toBe("Conflicts of interest of this round: none · history (500)");
    });

    test("a translation's $ patterns are kept as they are", () => {
        const t = { lang: "en", labelCount: "C $& $1 ({0})", labelNone: "", files: { one: "{0} $&", other: "{0} $'" },
            disqualified: {}, history: "" };
        expect(conflictsLabel({ conflicts: 2, disqualified: 0 }, t).text).toBe("C $& $1 (2 $')");
    });

    test("none, but a history: the link stays, to the history", () => {
        const { conflicts } = page();
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 0, history: 4 });
        expect(conflicts.textContent).toBe("Conflicts of interest of this round: none · history (4)");
        expect(enabled(conflicts)).toBe(true);
    });

    test("none and no history: disabled", () => {
        const { conflicts } = page();
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 0, history: 0 });
        expect(conflicts.textContent).toBe("Conflicts of interest of this round: none");
        expect(disabled(conflicts)).toBe(true);
    });

    test("none without the history in the answer: unchanged and enabled", () => {
        const t = { lang: "en", labelCount: "C ({0})", labelNone: "C: none", files: {}, disqualified: "", history: "" };
        expect(conflictsLabel({ conflicts: 0, disqualified: 0 }, t)).toBeNull();
        const { conflicts } = page();
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 0 });
        expect(conflicts.textContent).toBe("Conflicts of interest of this round");
        expect(enabled(conflicts)).toBe(true);
    });
});

describe("the authors link", () => {
    test("the authors, and the files without one when there are some", () => {
        const { authors } = page();
        applyCounts(document, { authors: 148, withoutAuthor: 12, conflicts: 0, disqualified: 0, history: 0 });
        expect(authors.textContent).toBe("Authors of this round (148 · 12 files without an author)");
        expect(enabled(authors)).toBe(true);

        const second = page().authors;
        applyCounts(document, { authors: 1, withoutAuthor: 0, conflicts: 0, disqualified: 0, history: 0 });
        expect(second.textContent).toBe("Authors of this round (1)");
    });

    test("no author stored and no file without one: disabled", () => {
        const { authors } = page();
        applyCounts(document, { authors: 0, withoutAuthor: 0, conflicts: 1, disqualified: 0 });
        expect(authors.textContent).toBe("Authors of this round: none stored");
        expect(disabled(authors)).toBe(true);
    });

    test("no author stored, but files without one: enabled, saying so", () => {
        const { authors } = page();
        applyCounts(document, { authors: 0, withoutAuthor: 5, conflicts: 1, disqualified: 0 });
        expect(authors.textContent).toBe("Authors of this round: none stored · 5 files without an author");
        expect(enabled(authors)).toBe(true);
    });

    test("an answer without the count: unchanged", () => {
        expect(authorsLabel({}, { lang: "en" })).toBeNull();
        expect(authorsLabel(null, { lang: "en" })).toBeNull();
    });
});

describe("loadRoundStatCounts", () => {
    afterEach(() => { delete global.fetch; });

    test("fetches the URL once, by script, and applies the answer", async () => {
        const links = page();
        global.fetch = jest.fn(() => Promise.resolve({
            ok: true,
            json: () => Promise.resolve({ authors: 0, withoutAuthor: 0, conflicts: 0, disqualified: 0, history: 0 })
        }));
        await loadRoundStatCounts(document);
        expect(global.fetch).toHaveBeenCalledTimes(1);
        expect(global.fetch.mock.calls[0][0]).toBe(URL);
        expect(global.fetch.mock.calls[0][1].headers["X-Requested-With"]).toBe("XMLHttpRequest");
        expect(disabled(links.authors)).toBe(true);
        expect(disabled(links.conflicts)).toBe(true);
    });

    test("a failed fetch keeps both links enabled, without counts", async () => {
        const links = page();
        global.fetch = jest.fn(() => Promise.reject(new Error("offline")));
        await loadRoundStatCounts(document);
        expect(enabled(links.authors)).toBe(true);
        expect(enabled(links.conflicts)).toBe(true);
        expect(links.authors.textContent).toBe("Authors of this round");
    });

    test("a refusal or an error answer keeps both links enabled", async () => {
        for (const status of [401, 403, 404, 500]) {
            const links = page();
            global.fetch = jest.fn(() => Promise.resolve({
                ok: false, status,
                json: () => Promise.resolve({ error: "forbidden" })
            }));
            await loadRoundStatCounts(document);
            expect(enabled(links.authors)).toBe(true);
            expect(enabled(links.conflicts)).toBe(true);
            expect(links.conflicts.textContent).toBe("Conflicts of interest of this round");
        }
    });

    test("no links (no permission for all three): nothing is fetched", async () => {
        document.body.innerHTML = `<p id="round-authors-link"><a href="/a">Authors of this round</a></p>`;
        global.fetch = jest.fn();
        await loadRoundStatCounts(document);
        expect(global.fetch).not.toHaveBeenCalled();
    });
});
