/**
 * The counts of the round statistics page's links to the round's authors and its conflicts
 * of interest (roundStat.scala.html), loaded after the page from GET /roundstat/:round/counts
 * (numbers only: authors, withoutAuthor, conflicts, disqualified, and history when there is
 * neither a conflict nor a disqualified file).
 *
 * Each link carries data-roundstat-counts (the URL), data-kind ("authors" or "conflicts"),
 * data-lang and its label templates, `{0}` for a number: data-label-count, data-label-none,
 * data-label-none-unknown (no author stored, but files without one), the plural forms
 * data-files-* / data-disqualified-* / data-unknown-* (one, few, many, other), data-history and
 * data-history-limit (the conflicts page lists that many of the latest events: more is shown as
 * "500+", and the answer counts no further than one more). The page renders the links enabled, without counts; this script changes a
 * link's text, and disables it (no href, aria-disabled, out of the tab order) only when the
 * answer says its count is 0 (for the conflicts link: and no history). On a refusal, an error
 * or a failed fetch the links stay as they are.
 *
 * pluralForm(n, lang) – the app's plural forms (views.Plural): uk/ru one, few, many; fr one
 *   for 0 and 1; others one for 1; other for the rest.
 * conflictsLabel(counts, t) / authorsLabel(counts, t) – pure: { text, enabled } for a link's
 *   templates `t` (its data attributes), or null if the answer lacks what the rule needs.
 * applyCounts(doc, counts) – fills in the links of the document.
 * loadRoundStatCounts(doc) – fetches each URL once and applies its answer.
 */
(function () {
    'use strict';

    function pluralForm(n, lang) {
        var abs = Math.abs(n);
        if (lang === "uk" || lang === "ru") {
            var mod10 = abs % 10, mod100 = abs % 100;
            if (mod10 === 1 && mod100 !== 11) return "one";
            if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) return "few";
            return "many";
        }
        if (lang === "fr") return abs <= 1 ? "one" : "other";
        return abs === 1 ? "one" : "other";
    }

    function formatNumber(n, lang) {
        try {
            return Number(n).toLocaleString(lang || undefined);
        } catch (e) {
            return String(n);
        }
    }

    function fill(template, value) {
        // a function, so that `$&` or `$1` in a translation is never a replacement pattern
        return String(template || "").replace("{0}", function () { return value; });
    }

    function isCount(n) {
        return typeof n === "number" && isFinite(n) && n >= 0;
    }

    function conflictsLabel(counts, t) {
        if (!counts || !isCount(counts.conflicts) || !isCount(counts.disqualified)) return null;
        var lang = t.lang;
        var parts = [];
        if (counts.conflicts > 0) {
            parts.push(fill(t.files[pluralForm(counts.conflicts, lang)], formatNumber(counts.conflicts, lang)));
        }
        if (counts.disqualified > 0) {
            parts.push(fill(t.disqualified[pluralForm(counts.disqualified, lang)], formatNumber(counts.disqualified, lang)));
        }
        if (parts.length > 0) return { text: fill(t.labelCount, parts.join(" · ")), enabled: true };
        // neither: the link stays while the round has a history, which its page lists
        if (!isCount(counts.history)) return null;
        if (counts.history > 0) {
            var limit = t.historyLimit;
            var shown = limit > 0 && counts.history > limit ? formatNumber(limit, lang) + "+" : formatNumber(counts.history, lang);
            return { text: t.labelNone + " · " + fill(t.history, shown), enabled: true };
        }
        return { text: t.labelNone, enabled: false };
    }

    function authorsLabel(counts, t) {
        if (!counts || !isCount(counts.authors)) return null;
        var lang = t.lang;
        var unknown = isCount(counts.withoutAuthor) && counts.withoutAuthor > 0
            ? fill(t.unknown[pluralForm(counts.withoutAuthor, lang)], formatNumber(counts.withoutAuthor, lang))
            : null;
        if (counts.authors === 0) {
            // none stored, but files without one: the list shows those, so the link stays
            return unknown === null ? { text: t.labelNone, enabled: false }
                : { text: fill(t.labelNoneUnknown, unknown), enabled: true };
        }
        var inner = formatNumber(counts.authors, lang) + (unknown === null ? "" : " · " + unknown);
        return { text: fill(t.labelCount, inner), enabled: true };
    }

    var PLURAL_FORMS = ["one", "few", "many", "other"];

    function templatesOf(link) {
        function forms(prefix) {
            var out = {};
            PLURAL_FORMS.forEach(function (form) { out[form] = link.getAttribute("data-" + prefix + "-" + form) || ""; });
            return out;
        }
        return {
            lang: link.getAttribute("data-lang") || "en",
            labelCount: link.getAttribute("data-label-count") || "",
            labelNone: link.getAttribute("data-label-none") || "",
            labelNoneUnknown: link.getAttribute("data-label-none-unknown") || "",
            files: forms("files"),
            unknown: forms("unknown"),
            disqualified: forms("disqualified"),
            history: link.getAttribute("data-history") || "",
            historyLimit: parseInt(link.getAttribute("data-history-limit"), 10) || 0
        };
    }

    function applyLabel(link, label) {
        if (!label) return;
        link.textContent = label.text;
        if (!label.enabled) {
            link.removeAttribute("href");
            link.setAttribute("aria-disabled", "true");
            link.setAttribute("tabindex", "-1");
            link.classList.add("text-body-secondary");
        }
    }

    function applyCounts(doc, counts, url) {
        var links = doc.querySelectorAll("a[data-roundstat-counts]");
        for (var i = 0; i < links.length; i++) {
            var link = links[i];
            if (url && link.getAttribute("data-roundstat-counts") !== url) continue;
            var kind = link.getAttribute("data-kind");
            var t = templatesOf(link);
            if (kind === "conflicts") applyLabel(link, conflictsLabel(counts, t));
            else if (kind === "authors") applyLabel(link, authorsLabel(counts, t));
        }
    }

    function loadRoundStatCounts(doc) {
        var links = doc.querySelectorAll("a[data-roundstat-counts]");
        var urls = [];
        for (var i = 0; i < links.length; i++) {
            var url = links[i].getAttribute("data-roundstat-counts");
            if (url && urls.indexOf(url) < 0) urls.push(url);
        }
        return Promise.all(urls.map(function (url) {
            // X-Requested-With: a script request, which never switches the session's contest
            return fetch(url, {
                credentials: "same-origin",
                headers: { "X-Requested-With": "XMLHttpRequest", "Accept": "application/json" }
            })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (counts) { if (counts) applyCounts(doc, counts, url); })
                // a failed fetch leaves the links as they are: enabled, without counts
                .catch(function () {});
        }));
    }

    if (typeof module !== "undefined") {
        module.exports = {
            pluralForm: pluralForm, conflictsLabel: conflictsLabel, authorsLabel: authorsLabel,
            applyCounts: applyCounts, loadRoundStatCounts: loadRoundStatCounts
        };
    }

    if (typeof document !== "undefined" && typeof module === "undefined") {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", function () { loadRoundStatCounts(document); });
        } else {
            loadRoundStatCounts(document);
        }
    }
})();
