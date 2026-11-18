// The import form's identity warning (support diagnostics plan, DG10): as the admin types or picks
// a source on the contest files page, the server's check of it against the contest's name, year
// and country (the health page's H1, ImageController.sourceIdentity: no Commons call) shows or
// hides the warning under the field, before anything is queued. The field is a Tom Select
// (category-suggest.js), which sets the source only when a text is chosen or the field is left:
// the text typed in its own input (#source-ts-control) is checked as it is typed, and a source not
// yet checked when the form is sent is checked then, at once, before the send goes on. A warning
// only: the form is sent either way, also when the check fails or takes longer than
// SOURCE_IDENTITY_SUBMIT_WAIT_MS; the files page shows the warning with the queued job too. The
// box (#source-identity-warning, role="status") comes rendered for the form's source. A failed
// check clears the box (its warning was another text's) and leaves the text to be checked again.
"use strict";

var SOURCE_IDENTITY_DELAY_MS = 300;
var SOURCE_IDENTITY_SUBMIT_WAIT_MS = 2000;

/** Starts the check on `doc`'s source field: true if the page has the field and its box.
  * `delayMs`: the wait after the last edit; `submitWaitMs`: the longest a send waits for a check. */
function initSourceIdentity(doc, delayMs, submitWaitMs) {
    var box = doc.getElementById("source-identity-warning");
    var input = doc.getElementById("source");
    var url = box && box.getAttribute("data-src");
    if (!box || !input || !url || typeof fetch !== "function") return false;
    var delay = typeof delayMs === "number" ? delayMs : SOURCE_IDENTITY_DELAY_MS;
    var submitWait = typeof submitWaitMs === "number" ? submitWaitMs : SOURCE_IDENTITY_SUBMIT_WAIT_MS;
    var form = input.form;
    var timer = null;
    var latest = 0; // the newest request's number: an answer to an older one is dropped
    var checked = input.value; // the text the box answers for (the server rendered it)
    var sending = false; // our own send after the check: let it through
    var waiting = false; // a send waits for its check: another one (a second click, Enter) waits too

    /** The source as the admin has it now: Tom Select's typed text while there is one, else the field's. */
    function current() {
        var typed = doc.getElementById("source-ts-control");
        return typed && typed.value.trim() ? typed.value : input.value;
    }

    function show(text) {
        box.textContent = text || "";
        box.hidden = !text;
    }

    /** Asks about `value`; resolves when answered or failed (never rejects). */
    function ask(value) {
        var n = ++latest;
        if (!value.trim()) {
            checked = value;
            show(null);
            return Promise.resolve();
        }
        var target = url + (url.indexOf("?") < 0 ? "?" : "&") + "source=" + encodeURIComponent(value);
        return fetch(target, {
            credentials: "same-origin",
            headers: { "X-Requested-With": "XMLHttpRequest", Accept: "application/json" }
        })
            .then(function (response) { return response.ok ? response.json() : Promise.reject(response.status); })
            .then(function (json) {
                if (n !== latest) return;
                checked = value;
                show(typeof json.warning === "string" ? json.warning : null);
            })
            .catch(function () {
                // unchecked: the next edit or the send asks again; the box's warning was another text's
                if (n === latest) show(null);
            });
    }

    function check() {
        var value = current();
        if (value !== checked) ask(value);
    }

    function later() {
        clearTimeout(timer);
        timer = setTimeout(check, delay);
    }

    input.addEventListener("input", later);
    input.addEventListener("change", later);
    // Tom Select's own input, typed into (it may come after this script: listened to on the document)
    doc.addEventListener("input", function (event) {
        if (event.target && event.target.id === "source-ts-control") later();
    });

    if (form) {
        form.addEventListener("submit", function (event) {
            if (sending) return;
            if (waiting) {
                event.preventDefault();
                return;
            }
            if (input.value === checked) return;
            event.preventDefault();
            waiting = true;
            clearTimeout(timer);
            var submitter = event.submitter || null;
            var gone = false;
            var send = function () {
                if (gone) return;
                gone = true;
                waiting = false;
                sending = true;
                if (typeof form.requestSubmit === "function") form.requestSubmit(submitter);
                else {
                    if (submitter && submitter.name) {
                        var hidden = doc.createElement("input");
                        hidden.type = "hidden";
                        hidden.name = submitter.name;
                        hidden.value = submitter.value;
                        form.appendChild(hidden);
                    }
                    form.submit();
                }
            };
            setTimeout(send, submitWait);
            ask(input.value).then(send);
        });
    }
    // a page the browser brings back (Back after the send) may be sent again
    if (typeof window !== "undefined") {
        window.addEventListener("pageshow", function (event) {
            if (event.persisted) {
                sending = false;
                waiting = false;
            }
        });
    }
    return true;
}

if (typeof document !== "undefined" && typeof module === "undefined") {
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function () { initSourceIdentity(document); });
    } else {
        initSourceIdentity(document);
    }
}

/* CommonJS export for Jest; ignored in the browser (no module object). */
if (typeof module !== "undefined") {
    module.exports = { initSourceIdentity: initSourceIdentity };
}
