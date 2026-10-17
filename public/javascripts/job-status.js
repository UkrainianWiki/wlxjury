/**
 * Background job status (services.jobs): any element
 *   <div class="job-status" data-src="/admin/jobs/ID" data-status="running">
 * rendered by app/views/snippets/jobStatus.scala.html is refreshed from its JSON
 * endpoint while the job is queued or running.
 *
 * formatJobStatus(p) – pure: the text for a status object (GET /admin/jobs/:id).
 * pollJobStatus(el)  – the fetch/poll loop for one element; shows the Retry button of a
 *                      failed job and the Cancel button of an active or failed one, and
 *                      reloads the page when a job finishes (data-reload-on-done), so its
 *                      results (image counts, the round's state) show.
 * initJobStatuses()  – starts pollJobStatus for every .job-status element.
 */

function formatCount(n) {
    return Number(n).toLocaleString("en-US");
}

function formatJobProgress(p) {
    var unit = p.progressUnit ? " " + p.progressUnit : "";
    if (p.progressDone != null && p.progressTotal != null && p.progressTotal > 0) {
        var pct = Math.min(100, Math.floor(100 * p.progressDone / p.progressTotal));
        return formatCount(p.progressDone) + " / " + formatCount(p.progressTotal) + unit + " (" + pct + "%)";
    }
    if (p.progressDone != null && p.progressDone > 0) {
        return formatCount(p.progressDone) + unit;
    }
    return "";
}

/* The counts in a job's details, by kind: a distribution's images added (always), an
   import's files and monuments (when there are any). */
var JOB_COUNTS = [
    ["inserted", "new"],
    ["linked", "linked"],
    ["updated", "updated"],
    ["monumentIdsChanged", "monument ids changed"],
    ["monuments", "monuments"]
];

function formatJobCounts(details) {
    var d = details || {};
    var parts = [];
    if (d.imagesAdded != null) parts.push(formatCount(d.imagesAdded) + " added");
    JOB_COUNTS.forEach(function (c) {
        if (d[c[0]] > 0) parts.push(formatCount(d[c[0]]) + " " + c[1]);
    });
    if (d.missingTitles && d.missingTitles.length > 0) parts.push(formatCount(d.missingTitles.length) + " not found");
    return parts.length > 0 ? ", " + parts.join(", ") : "";
}

function formatJobStatus(p) {
    var progress = formatJobProgress(p);
    var added = formatJobCounts(p.details);
    switch (p.status) {
        case "queued":
            return "Queued" + (p.queuePosition > 0 ? " (" + p.queuePosition + " ahead)" : "");
        case "running":
            return (p.title || "Running") + ": " + (progress || "starting") + added + "…";
        case "failed":
            return "Failed" + (progress ? " at " + progress : "") + added + ": " + (p.error || "unknown error");
        case "cancelled":
            return "Cancelled" + (progress ? " at " + progress : "") + added;
        case "done":
            return "Done" + added;
        default:
            return p.status;
    }
}

function isActiveJob(p) {
    return p.status === "queued" || p.status === "running";
}

function pollJobStatus(el, options) {
    var opts = options || {};
    var interval = opts.interval || 3000;
    var reload = opts.reload || function () { window.location.reload(); };
    var wasActive = el.getAttribute("data-status") === "queued" || el.getAttribute("data-status") === "running";

    function show(selector, visible) {
        var node = el.querySelector(selector);
        if (node) node.style.display = visible ? "inline" : "none";
    }

    function update() {
        return fetch(el.getAttribute("data-src"), { credentials: "same-origin" })
            .then(function (r) { return r.json(); })
            .then(function (p) {
                var text = el.querySelector(".job-status-text");
                if (text) text.textContent = formatJobStatus(p);
                el.setAttribute("data-status", p.status);
                show(".job-retry", p.status === "failed");
                show(".job-cancel", isActiveJob(p) || p.status === "failed");
                if (isActiveJob(p)) {
                    wasActive = true;
                    setTimeout(update, interval);
                } else if (wasActive && p.status === "done" && el.getAttribute("data-reload-on-done") === "true") {
                    reload();
                }
                return p;
            })
            .catch(function () { setTimeout(update, interval * 2); });
    }
    return update();
}

function initJobStatuses() {
    var els = document.querySelectorAll(".job-status[data-src]");
    for (var i = 0; i < els.length; i++) pollJobStatus(els[i]);
}

if (typeof document !== "undefined" && typeof module === "undefined") {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", initJobStatuses);
    else initJobStatuses();
}

/* CommonJS export for Jest; ignored in the browser (no module object). */
if (typeof module !== "undefined") {
    module.exports = {
        formatJobStatus: formatJobStatus,
        formatJobProgress: formatJobProgress,
        formatJobCounts: formatJobCounts,
        pollJobStatus: pollJobStatus,
        initJobStatuses: initJobStatuses
    };
}
