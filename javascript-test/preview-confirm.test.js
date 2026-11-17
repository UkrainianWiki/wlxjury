/**
 * @jest-environment jsdom
 */
"use strict";

// The add juror and reassign pages' confirm (preview-confirm.js): it posts the choice the figures were
// shown for, so it is disabled, with a hint, while the boxes or the select differ from that choice.

const { stale, sync, init } = require("../public/javascripts/preview-confirm");

function page() {
    document.body.innerHTML = `
        <form id="choose" method="get">
            <select name="juror"><option value="1">A</option><option value="2" selected>B</option></select>
            <input type="checkbox" name="receiver" value="3" checked>
            <input type="checkbox" name="receiver" value="4">
            <button type="submit">Show what this does</button>
        </form>
        <p id="hint" role="status" data-stale-text="Show what this does first"></p>
        <form id="confirm" method="post" data-preview-form="choose" data-preview-hint="hint">
            <input type="hidden" name="receiver[]" value="3"><button type="submit">Reassign</button>
        </form>`;
    init(document);
    return {
        choose: document.getElementById("choose"),
        confirm: document.getElementById("confirm"),
        button: document.querySelector("#confirm button"),
        hint: document.getElementById("hint"),
    };
}

function change(el) {
    el.dispatchEvent(new Event("change", { bubbles: true }));
}

function submit(form) {
    const event = new Event("submit", { bubbles: true, cancelable: true });
    form.dispatchEvent(event);
    return event;
}

describe("preview-confirm", () => {
    test("leaves the confirm enabled, the hint hidden, while the choice is the one shown", () => {
        const p = page();
        expect(stale(p.choose)).toBe(false);
        expect(p.button.disabled).toBe(false);
        expect(p.hint.textContent).toBe("");
        expect(p.button.hasAttribute("aria-describedby")).toBe(false);
        expect(submit(p.confirm).defaultPrevented).toBe(false);
    });

    test("disables the confirm and shows the hint when a box is ticked or unticked, and again when put back", () => {
        const p = page();
        const [ticked, unticked] = p.choose.querySelectorAll('input[type="checkbox"]');
        unticked.checked = true;
        change(unticked);
        expect(p.button.disabled).toBe(true);
        expect(p.hint.textContent).toBe("Show what this does first");
        expect(p.button.getAttribute("aria-describedby")).toBe("hint");
        expect(submit(p.confirm).defaultPrevented).toBe(true);
        unticked.checked = false;
        change(unticked);
        expect(p.button.disabled).toBe(false);
        expect(p.button.hasAttribute("aria-describedby")).toBe(false);
        ticked.checked = false;
        change(ticked);
        expect(p.button.disabled).toBe(true);
    });

    test("disables the confirm when another option is chosen in the select", () => {
        const p = page();
        const select = p.choose.querySelector("select");
        select.value = "1";
        change(select);
        expect(p.button.disabled).toBe(true);
        select.value = "2";
        change(select);
        expect(p.button.disabled).toBe(false);
    });

    test("takes a select rendered with no option marked as showing its first", () => {
        document.body.innerHTML = `
            <form id="c"><select><option value="1">A</option><option value="2">B</option></select></form>`;
        const choose = document.getElementById("c");
        expect(stale(choose)).toBe(false);
        choose.querySelector("select").value = "2";
        expect(stale(choose)).toBe(true);
    });

    test("checks again a page restored from the back-forward cache, its boxes as left", () => {
        const p = page();
        // the browser restores the boxes as left, with no change event; submit-once.js re-enables buttons
        p.choose.querySelectorAll('input[type="checkbox"]')[1].checked = true;
        p.button.disabled = false;
        const restored = new Event("pageshow");
        Object.defineProperty(restored, "persisted", { value: true });
        window.dispatchEvent(restored);
        expect(p.button.disabled).toBe(true);
        expect(p.hint.textContent).toBe("Show what this does first");
        // a plain load's pageshow (not persisted) leaves it to the load's own check
        p.button.disabled = false;
        window.dispatchEvent(new Event("pageshow"));
        expect(p.button.disabled).toBe(false);
    });

    test("does nothing for a confirm whose choose form is missing", () => {
        document.body.innerHTML = `<form id="f" data-preview-form="nope"><button type="submit">Go</button></form>`;
        const form = document.getElementById("f");
        expect(sync(form, document)).toBe(false);
        expect(form.querySelector("button").disabled).toBe(false);
    });
});
