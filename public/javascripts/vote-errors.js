/**
 * What a juror is told when a vote on a gallery tile (ug-theme-tiles.js) fails, in the
 * page's language: the texts are rendered into the page as data-vote-* attributes of the
 * element with data-csrf-token (app/views/gallery.scala.html).
 *
 * voteErrorMessage(status, body, texts) – pure: the message for the response's status
 *   and JSON body (null if it has none). selectWS answers
 *     401 {error: "unauthenticated"}           signed out
 *     403 {error: "csrf", reference}           the CSRF filter (CsrfErrorHandler): the page's
 *                                              token is stale, or the session is gone
 *     403 {error: "forbidden", reference}      a denial (Secured.onUnAuthorizedJson)
 *     409 {error: "round_closed"}              the round is not open for voting
 *   anything else is a generic failure, with the reference if the body has one.
 * voteErrorTexts(el) – the texts from the element's data attributes.
 */

function voteErrorMessage(status, body, texts) {
    var error = body && typeof body === "object" ? body.error : undefined;
    var reference = body && typeof body === "object" && body.reference ? String(body.reference) : "";
    if (status === 401) return texts.signedOut;
    // the filter's CSRF failure, also when the body is not JSON; a denial has a reference
    if (status === 403 && error !== "forbidden") return texts.expired;
    if (status === 409) return texts.closed;
    return reference ? texts.failed + " " + texts.reference.replace("{0}", reference) : texts.failed;
}

function voteErrorTexts(el) {
    function text(name, fallback) {
        var value = el && el.getAttribute("data-vote-" + name);
        return value || fallback;
    }
    return {
        signedOut: text("signed-out", "You have been signed out. Sign in again and reload the page."),
        expired: text("expired", "This page has expired. Reload it and vote again."),
        closed: text("closed", "This round is closed for voting."),
        failed: text("failed", "Your vote could not be saved. Try again."),
        reference: text("reference", "Reference: {0}"),
        loginUrl: text("login-url", "")
    };
}

/* CommonJS export for Jest; ignored in the browser (no module object). */
if (typeof module !== "undefined") {
    module.exports = {
        voteErrorMessage: voteErrorMessage,
        voteErrorTexts: voteErrorTexts
    };
}
