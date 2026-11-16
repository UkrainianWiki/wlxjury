/**
 * @jest-environment jsdom
 */
"use strict";

// The Edit user form's "wiki account taken" panel (wiki-taken.js, accounts plan A-2): "Keep them
// separate" puts the stored wiki account back into the form, keeping the other fields typed, and
// hides the panel; nothing is sent.

const { keepSeparate, init } = require("../public/javascripts/wiki-taken");

function page(stored) {
    document.body.innerHTML = `
        <section id="wiki-taken"><h2>Other accounts with the wiki account Taken</h2>
            <a href="/users/edit/5?contestId=10" id="wiki-taken-keep" data-wiki-account="${stored}">Keep them separate</a>
        </section>
        <form>
            <div class="mb-3" id="fullname_field"><input id="fullname" name="fullname" value="New name typed"></div>
            <div class="mb-3" id="wikiAccount_field">
                <input id="wikiAccount" name="wikiAccount" value="Taken" class="form-control is-invalid"
                    aria-describedby="wikiAccount_error" aria-invalid="true">
                <div class="invalid-feedback" id="wikiAccount_error">Another account already has this wiki account.</div>
            </div>
        </form>`;
}

describe("Keep them separate", () => {

    test("puts the stored wiki account back, clears the error, keeps the other fields and hides the panel", () => {
        page("Own Name");
        const fetchSpy = jest.fn();
        global.fetch = fetchSpy;
        init(document);
        const link = document.getElementById("wiki-taken-keep");
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        link.dispatchEvent(click);

        expect(click.defaultPrevented).toBe(true);
        const field = document.getElementById("wikiAccount");
        expect(field.value).toBe("Own Name");
        expect(field.classList.contains("is-invalid")).toBe(false);
        expect(field.hasAttribute("aria-invalid")).toBe(false);
        expect(field.hasAttribute("aria-describedby")).toBe(false);
        expect(document.getElementById("wikiAccount_error")).toBeNull();
        expect(document.getElementById("fullname").value).toBe("New name typed");
        expect(document.getElementById("wiki-taken").hidden).toBe(true);
        expect(document.activeElement).toBe(field);
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    test("empties the field for an account that had no wiki account", () => {
        page("");
        expect(keepSeparate(document)).toBe(true);
        expect(document.getElementById("wikiAccount").value).toBe("");
    });

    test("leaves the link to the edit page where the form isn't on the page", () => {
        page("Own Name");
        document.querySelector("form").remove();
        init(document);
        // whether the script let the link go, read after it ran; then stopped here (jsdom doesn't navigate)
        let followed = null;
        document.addEventListener("click", (e) => { followed = !e.defaultPrevented; e.preventDefault(); });
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        document.getElementById("wiki-taken-keep").dispatchEvent(click);
        expect(followed).toBe(true);
        expect(document.getElementById("wiki-taken").hidden).toBe(false);
    });

    test("does nothing on a page without the panel", () => {
        document.body.innerHTML = `<form><input id="wikiAccount" value="x"></form>`;
        expect(init(document)).toBeNull();
        expect(keepSeparate(document)).toBe(false);
    });
});
