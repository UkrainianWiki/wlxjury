/**
 * @jest-environment jsdom
 */
"use strict";

// "Stop the round" on a round's conflicts page (confirm-submit.js): sent only once confirmed.

const { onSubmit } = require("../public/javascripts/confirm-submit");

function page(attr = 'data-confirm="Stop round 2?"') {
    document.body.innerHTML = `
        <form id="f" method="post" action="/admin/setround" ${attr}>
            <input type="hidden" name="currentId" value="7"><button type="submit">Stop the round</button>
        </form>`;
    return document.getElementById("f");
}

function submit(form) {
    const event = new Event("submit", { bubbles: true, cancelable: true });
    form.dispatchEvent(event);
    return event;
}

describe("confirm-submit", () => {
    afterEach(() => jest.restoreAllMocks());

    test("asks with the form's question, and sends it when confirmed", () => {
        const ask = jest.spyOn(window, "confirm").mockReturnValue(true);
        expect(submit(page()).defaultPrevented).toBe(false);
        expect(ask).toHaveBeenCalledWith("Stop round 2?");
    });

    test("drops the submit when declined", () => {
        jest.spyOn(window, "confirm").mockReturnValue(false);
        expect(submit(page()).defaultPrevented).toBe(true);
    });

    test("leaves other forms alone, without asking", () => {
        const ask = jest.spyOn(window, "confirm").mockReturnValue(false);
        expect(submit(page("")).defaultPrevented).toBe(false);
        expect(ask).not.toHaveBeenCalled();
        expect(onSubmit(null)).toBe(true);
    });

    test("takes the question asker it is given", () => {
        const form = page();
        expect(onSubmit(form, null, () => true)).toBe(true);
        expect(onSubmit(form, null, () => false)).toBe(false);
    });
});
