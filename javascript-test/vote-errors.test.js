"use strict";

const { voteErrorMessage, voteErrorTexts } = require("../public/javascripts/vote-errors");

const texts = {
    signedOut: "SIGNED OUT",
    expired: "EXPIRED",
    closed: "CLOSED",
    failed: "FAILED",
    reference: "Ref {0}",
    loginUrl: "/login?returnTo=%2Fgallery"
};

describe("voteErrorMessage", () => {

    test("tells a signed-out juror to sign in again", () => {
        expect(voteErrorMessage(401, { error: "unauthenticated" }, texts)).toBe("SIGNED OUT");
    });

    test("tells the juror to reload a page whose CSRF token was refused", () => {
        expect(voteErrorMessage(403, { error: "csrf", reference: "0a1b2c3d" }, texts)).toBe("EXPIRED");
        // the filter's answer without the app's handler: not JSON
        expect(voteErrorMessage(403, null, texts)).toBe("EXPIRED");
    });

    test("shows a denial's reference", () => {
        expect(voteErrorMessage(403, { error: "forbidden", reference: "0a1b2c3d" }, texts)).toBe("FAILED Ref 0a1b2c3d");
    });

    test("says the round is closed", () => {
        expect(voteErrorMessage(409, { error: "round_closed" }, texts)).toBe("CLOSED");
    });

    test("is generic for any other failure, with the reference if the body has one", () => {
        expect(voteErrorMessage(500, null, texts)).toBe("FAILED");
        expect(voteErrorMessage(0, undefined, texts)).toBe("FAILED");
        expect(voteErrorMessage(500, { reference: "deadbeef" }, texts)).toBe("FAILED Ref deadbeef");
        expect(voteErrorMessage(500, "<html>", texts)).toBe("FAILED");
    });
});

describe("voteErrorTexts", () => {

    function element(attributes) {
        return { getAttribute: name => (name in attributes ? attributes[name] : null) };
    }

    test("reads the page's texts from its data-vote-* attributes", () => {
        const read = voteErrorTexts(element({
            "data-vote-signed-out": "Déconnecté",
            "data-vote-expired": "Expirée",
            "data-vote-closed": "Fermé",
            "data-vote-failed": "Échec",
            "data-vote-reference": "Référence : {0}",
            "data-vote-login-url": "/login"
        }));
        expect(read).toEqual({
            signedOut: "Déconnecté", expired: "Expirée", closed: "Fermé", failed: "Échec",
            reference: "Référence : {0}", loginUrl: "/login"
        });
        expect(voteErrorMessage(403, { error: "forbidden", reference: "0a1b2c3d" }, read))
            .toBe("Échec Référence : 0a1b2c3d");
    });

    test("falls back to English without the attributes, and to no login page", () => {
        const read = voteErrorTexts(null);
        expect(read.expired).toBe("This page has expired. Reload it and vote again.");
        expect(read.loginUrl).toBe("");
    });
});
