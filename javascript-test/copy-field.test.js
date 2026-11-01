/**
 * @jest-environment jsdom
 */
"use strict";

// The Copy button of the profile's new API token (copy-field.js): the field's text goes to
// the clipboard and the button says so; without the Clipboard API the text stays selected.

const { copyField } = require("../public/javascripts/copy-field");

function page() {
    document.body.innerHTML = `
        <input type="text" id="new-api-token" value="wlxj_secret" readonly>
        <button type="button" data-copy-target="new-api-token" data-copied-text="Copied"><span>Copy</span></button>`;
    return document.querySelector("button");
}

describe("copyField", () => {

    test("puts the field's text on the clipboard and says so on the button", async () => {
        const button = page();
        const writeText = jest.fn(() => Promise.resolve());
        await expect(copyField(button, { writeText })).resolves.toBe(true);
        expect(writeText).toHaveBeenCalledWith("wlxj_secret");
        expect(button.textContent).toBe("Copied");
    });

    test("leaves the text selected without the Clipboard API, or if it refuses", async () => {
        const button = page();
        await expect(copyField(button, undefined)).resolves.toBe(false);
        await expect(copyField(button, { writeText: () => Promise.reject(new Error("denied")) })).resolves.toBe(false);
        const field = document.getElementById("new-api-token");
        expect(document.activeElement).toBe(field);
        expect(field.selectionStart).toBe(0);
        expect(field.selectionEnd).toBe("wlxj_secret".length);
        expect(button.textContent).toBe("Copy");
    });

    test("copies nothing for a missing field", async () => {
        const button = page();
        button.setAttribute("data-copy-target", "missing");
        const writeText = jest.fn(() => Promise.resolve());
        await expect(copyField(button, { writeText })).resolves.toBe(false);
        expect(writeText).not.toHaveBeenCalled();
    });
});
