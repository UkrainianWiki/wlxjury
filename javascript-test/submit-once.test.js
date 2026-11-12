/**
 * @jest-environment jsdom
 */
"use strict";

// The profile's API token create form (submit-once.js): a double click sends one request.

const { onSubmit, reset } = require("../public/javascripts/submit-once");

function page(attr = "data-submit-once") {
    document.body.innerHTML = `
        <form id="f" method="post" action="/users/tokens" ${attr}>
            <input name="name" value="cli"><button type="submit">Create</button>
        </form>`;
    return document.getElementById("f");
}

function submit(form) {
    const event = new Event("submit", { bubbles: true, cancelable: true });
    form.dispatchEvent(event);
    return event;
}

describe("submit-once", () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());

    test("lets the first submit go, then disables the button and drops a second", () => {
        const form = page();
        expect(submit(form).defaultPrevented).toBe(false);
        jest.runAllTimers();
        expect(form.querySelector("button").disabled).toBe(true);
        expect(submit(form).defaultPrevented).toBe(true);
    });

    test("leaves a form unlocked when another handler cancelled its submit", () => {
        const form = page();
        const cancel = (e) => e.preventDefault();
        form.addEventListener("submit", cancel);
        expect(submit(form).defaultPrevented).toBe(true);
        jest.runAllTimers();
        expect(form.hasAttribute("data-submitted")).toBe(false);
        expect(form.querySelector("button").disabled).toBe(false);
        form.removeEventListener("submit", cancel);
        expect(submit(form).defaultPrevented).toBe(false);
        jest.runAllTimers();
        expect(form.querySelector("button").disabled).toBe(true);
    });

    test("leaves other forms alone", () => {
        const form = page("");
        expect(submit(form).defaultPrevented).toBe(false);
        jest.runAllTimers();
        expect(submit(form).defaultPrevented).toBe(false);
        expect(form.querySelector("button").disabled).toBe(false);
        expect(onSubmit(null)).toBe(true);
    });

    test("a page restored by Back may be sent again", () => {
        const form = page();
        submit(form);
        jest.runAllTimers();
        reset(document);
        expect(form.querySelector("button").disabled).toBe(false);
        expect(submit(form).defaultPrevented).toBe(false);
    });

    test("the restore runs on a persisted pageshow", () => {
        const form = page();
        submit(form);
        jest.runAllTimers();
        const shown = new Event("pageshow");
        shown.persisted = true;
        window.dispatchEvent(shown);
        expect(form.querySelector("button").disabled).toBe(false);
    });
});
