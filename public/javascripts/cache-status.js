/**
 * Image cache status poller for the contest images admin page.
 *
 * formatCacheStatus(p, texts) – pure function: given a status object, returns the
 * text to display (or null when there is nothing to show yet). A finished run with
 * nothing to download says whether all its images were already cached (p.cached) or it
 * had none, with the time it checked (p.startedAtMs), so an old run's text read on a
 * later page load is dated, not stale; one that downloaded some adds how many were
 * cached before. Those texts are the page's (cacheStatusTexts), none hard-coded.
 *
 * cacheStatusTexts(el) – the element's data-text-* messages ({0}, {1} filled in here,
 * as plain text: the messages must not format them, no {0,number} or choice) and the
 * page's language, for the counts and the time.
 *
 * pollCacheStatus(statusUrl, elementId) – starts the fetch/poll loop, wiring the
 * result into the element with that id (#cache-status by default), as text.  On 401 or
 * 403 (signed out, or no longer an admin of the contest) it stops; on other
 * failures it retries, slower.  A 'wlx:refresh' event on the element (async-form.js,
 * after "Download images locally" started a download) fetches it again at once and
 * polls on while the download runs, one loop at a time.
 *
 * initCacheStatuses(doc) – starts pollCacheStatus for each element with a
 * data-cache-status-url (contest_images, editRound), the URL the template
 * renders, so that no server-side expression is written into a script.  The
 * file does it by itself once the page is parsed.
 */

function fillCacheText(template, values) {
    return template.replace(/\{(\d)\}/g, function (m, i) {
        return values[i] !== undefined ? String(values[i]) : m;
    });
}

function formatCacheTime(ms, lang) {
    var d = new Date(ms);
    try {
        return d.toLocaleString(lang || undefined, { dateStyle: "short", timeStyle: "short" });
    } catch (e) {
        return d.toLocaleString();
    }
}

function formatCacheCount(n, lang) {
    try {
        return n.toLocaleString(lang || undefined);
    } catch (e) {
        return String(n);
    }
}

function formatCacheStatus(p, texts) {
    texts = texts || {};
    var cached   = p.cached > 0 ? p.cached : 0;
    var finished = !p.running && !p.failed && p.startedAtMs > 0;
    if (p.total > 0) {
        var pct    = Math.round(100 * p.done / p.total);
        var errStr = (p.renamed > 0 ? ", " + p.renamed + " renamed on Commons" : "")
            + (p.deleted > 0 ? ", " + p.deleted + " deleted on Commons" : "")
            + (p.errors > 0 ? ", " + p.errors + " errors" : "");
        var rate   = p.ratePerSec > 0 ? " \u2013 " + p.ratePerSec.toFixed(1) + " img/s" : "";
        var eta    = "";
        if (p.running && p.etaSeconds > 0) {
            eta = p.etaSeconds >= 60
                ? " \u2013 ETA " + Math.floor(p.etaSeconds / 60) + "m " + (p.etaSeconds % 60) + "s"
                : " \u2013 ETA " + p.etaSeconds + "s";
        }
        var alsoCached = finished && cached > 0 && texts.alsoCached
            ? " \u2013 " + fillCacheText(texts.alsoCached, [formatCacheCount(cached, texts.lang)]) : "";
        return p.done + " / " + p.total + " downloaded (" + pct + "%" + errStr + ")"
            + rate + eta + (p.running ? " \u2013 running\u2026" : p.failed ? " \u2013 failed" : " \u2013 done")
            + alsoCached;
    } else if (p.running) {
        return "Starting\u2026";
    } else if (p.failed) {
        return "Download failed";
    } else if (finished) {
        var when = formatCacheTime(p.startedAtMs, texts.lang);
        if (cached > 0 && texts.allCached) return fillCacheText(texts.allCached, [formatCacheCount(cached, texts.lang), when]);
        if (cached === 0 && texts.nothing) return fillCacheText(texts.nothing, [when]);
    }
    return null;
}

function cacheStatusTexts(el) {
    function text(name) {
        var value = el && el.getAttribute ? el.getAttribute("data-text-" + name) : null;
        return value || undefined;
    }
    var root = typeof document !== "undefined" && document.documentElement;
    return {
        allCached: text("all-cached"),
        nothing: text("nothing"),
        alsoCached: text("also-cached"),
        lang: (root && root.lang) || undefined
    };
}

function pollCacheStatus(statusUrl, elementId) {
    elementId = elementId || 'cache-status';
    var timer = null;
    function later(ms) {
        clearTimeout(timer);
        timer = setTimeout(update, ms);
    }
    function update() {
        clearTimeout(timer);
        timer = null;
        // X-Requested-With: a poll, which must not switch the session's contest
        return fetch(statusUrl,
                { credentials: "same-origin", headers: { "X-Requested-With": "XMLHttpRequest" } })
            .then(function(r) {
                if (r.status === 401 || r.status === 403) return { denied: true };
                return r.json();
            })
            .then(function(p) {
                if (p.denied) { return p; }
                var el = document.getElementById(elementId);
                var text = formatCacheStatus(p, cacheStatusTexts(el));
                if (text !== null) {
                    el.textContent = text;
                }
                if (p.running) { later(3000); }
                return p;
            })
            .catch(function() { later(5000); });
    }
    var target = document.getElementById(elementId);
    if (target && target.addEventListener) {
        target.addEventListener("wlx:refresh", function () { update(); });
    }
    return update();
}

function initCacheStatuses(doc) {
    var els = doc.querySelectorAll("[data-cache-status-url][id]");
    for (var i = 0; i < els.length; i++) {
        pollCacheStatus(els[i].getAttribute("data-cache-status-url"), els[i].id);
    }
    return els.length;
}

if (typeof document !== "undefined" && typeof module === "undefined") {
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function () { initCacheStatuses(document); });
    } else {
        initCacheStatuses(document);
    }
}

/* CommonJS export for Jest; ignored in the browser (no module object). */
if (typeof module !== "undefined") {
    module.exports = {
        formatCacheStatus: formatCacheStatus,
        formatCacheTime: formatCacheTime,
        formatCacheCount: formatCacheCount,
        cacheStatusTexts: cacheStatusTexts,
        pollCacheStatus: pollCacheStatus,
        initCacheStatuses: initCacheStatuses
    };
}
