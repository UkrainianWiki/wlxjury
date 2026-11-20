/**
 * @jest-environment jsdom
 */
"use strict";

// async-form.js: a form[data-async-form] posted by fetch, the CSRF token in the Csrf-Token
// header, the status element refreshed on success, the reason said in place on a failure.

const asyncForm = require("../public/javascripts/async-form");

const FORM = '<div id="cache-status"></div>' +
    '<form method="post" action="/contest/3/images/cache" data-async-form data-async-refresh="cache-status"' +
    ' data-csrf-token="tok123" data-async-signed-out="Signed out." data-async-expired="Expired."' +
    ' data-async-forbidden="Forbidden." data-async-failed="Failed." data-async-reference="Ref: {0}">' +
    '<input type="hidden" name="csrfToken" value="tok123"><button type="submit">Go</button>' +
    '<p data-async-error hidden></p></form>';

function answer(status, json) {
    return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        json: () => (json === undefined ? Promise.reject(new Error("no json")) : Promise.resolve(json)),
    });
}

beforeAll(() => asyncForm.bind(document));
beforeEach(() => { document.body.innerHTML = FORM; });
afterEach(() => { delete global.fetch; });

const form = () => document.querySelector("form");
const error = () => document.querySelector("[data-async-error]");
const settle = () => new Promise((r) => setTimeout(r, 0));

test("posts by script with the token and X-Requested-With, and refreshes the status in place", async () => {
    global.fetch = jest.fn(() => answer(204));
    const refreshed = jest.fn();
    document.getElementById("cache-status").addEventListener("wlx:refresh", refreshed);
    const event = new Event("submit", { bubbles: true, cancelable: true });
    form().dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(form().querySelector("button").disabled).toBe(true);
    await settle();
    await settle();
    const [url, init] = global.fetch.mock.calls[0];
    expect(url).toBe("http://localhost/contest/3/images/cache");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("same-origin");
    expect(init.headers["Csrf-Token"]).toBe("tok123");
    expect(init.headers["X-Requested-With"]).toBe("XMLHttpRequest");
    expect(init.body).toBe("csrfToken=tok123");
    expect(refreshed).toHaveBeenCalledTimes(1);
    expect(error().hidden).toBe(true);
    expect(form().querySelector("button").disabled).toBe(false);
});

test("leaves other forms to the browser", () => {
    document.body.innerHTML = '<form action="/x"><button type="submit">Go</button></form>';
    global.fetch = jest.fn();
    const event = new Event("submit", { bubbles: true, cancelable: true });
    document.querySelector("form").dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(global.fetch).not.toHaveBeenCalled();
});

test.each([
    [401, { error: "unauthenticated" }, "Signed out."],
    [403, { error: "forbidden", reference: "AB12" }, "Forbidden. Ref: AB12"],
    [403, { error: "csrf", reference: "CD34" }, "Expired. Ref: CD34"],
    [403, undefined, "Expired."],
    [500, { reference: "EF56" }, "Failed. Ref: EF56"],
    [404, { error: "round_not_found" }, "Forbidden."],
])("says why it failed in place: %i %j", async (status, json, text) => {
    global.fetch = jest.fn(() => answer(status, json));
    const refreshed = jest.fn();
    document.getElementById("cache-status").addEventListener("wlx:refresh", refreshed);
    expect(await asyncForm.submit(form(), document)).toBe(false);
    expect(error().hidden).toBe(false);
    expect(error().textContent).toBe(text);
    expect(refreshed).not.toHaveBeenCalled();
});

test("a network failure is a generic failure; a later success clears the text", async () => {
    global.fetch = jest.fn(() => Promise.reject(new TypeError("offline")));
    expect(await asyncForm.submit(form(), document)).toBe(false);
    expect(error().textContent).toBe("Failed.");
    global.fetch = jest.fn(() => answer(204));
    expect(await asyncForm.submit(form(), document)).toBe(true);
    expect(error().hidden).toBe(true);
    expect(error().textContent).toBe("");
});

test("its own texts are the English messages' (messages.en)", () => {
    document.body.innerHTML = '<form data-async-form></form>';
    const t = asyncForm.texts(document.querySelector("form"));
    expect(t.forbidden).toBe("You may no longer do this here. Reload the page.");
    expect(t.signedOut).toBe("You have been signed out. Sign in again and reload the page.");
    expect(t.expired).toBe("This page has expired. Reload it and try again.");
    expect(t.failed).toBe("This could not be done. Try again.");
});

test("the error text is plain text", () => {
    const t = asyncForm.texts(form());
    expect(asyncForm.errorMessage(403, { error: "forbidden", reference: "<b>x</b>" }, t)).toBe("Forbidden. Ref: <b>x</b>");
});
