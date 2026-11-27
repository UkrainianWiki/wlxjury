/** @jest-environment jsdom */
"use strict";
const labels = require("../public/javascripts/select-labels");
test("native select labels on any page focus without activating the picker", () => {
    document.body.innerHTML = '<form id="import-images"><label for="source">Source</label><select id="source"><option>One</option></select></form>';
    labels.init(document);
    const event = new MouseEvent("click", { bubbles: true, cancelable: true });
    document.querySelector("label").dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(document.querySelector("select"));
});
