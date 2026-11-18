/**
 * @jest-environment jsdom
 */
"use strict";

// "Add people from another of your contests" (add-from-contest.js): "Tick all shown" is shown only
// with someone to tick, ticks and unticks every box that can be ticked (never a disabled one), and
// follows the boxes ticked one by one.

const { init } = require("../public/javascripts/add-from-contest");

function page(rows) {
    document.body.innerHTML = `
        <form id="add-from-form">
            <div class="form-check" id="add-from-all-box" hidden>
                <input type="checkbox" id="add-from-all"><label for="add-from-all">Tick all shown</label>
            </div>
            <table><tbody>${rows.map((disabled, i) =>
                `<tr><td><input type="checkbox" class="add-from-pick" name="accountId" value="${i + 1}"
                    ${disabled ? "disabled" : ""}></td></tr>`).join("")}</tbody></table>
        </form>`;
    init(document);
    return {
        all: document.getElementById("add-from-all"),
        box: document.getElementById("add-from-all-box"),
        boxes: Array.from(document.querySelectorAll("input.add-from-pick")),
    };
}

function click(input) {
    input.checked = !input.checked;
    input.dispatchEvent(new Event("change", { bubbles: true }));
}

describe("Tick all shown", () => {

    test("starts unticked and shown, with nobody ticked", () => {
        const { all, box, boxes } = page([false, true, false]);
        expect(box.hidden).toBe(false);
        expect(all.checked).toBe(false);
        expect(all.indeterminate).toBe(false);
        expect(boxes.some((b) => b.checked)).toBe(false);
    });

    test("ticks and unticks every box that can be ticked, never a disabled one", () => {
        const { all, boxes } = page([false, true, false]);
        click(all);
        expect(boxes.map((b) => b.checked)).toEqual([true, false, true]);
        expect(all.checked).toBe(true);
        click(all);
        expect(boxes.map((b) => b.checked)).toEqual([false, false, false]);
        expect(all.checked).toBe(false);
    });

    test("follows the boxes ticked one by one: mixed, then all", () => {
        const { all, boxes } = page([false, false]);
        click(boxes[0]);
        expect(all.checked).toBe(false);
        expect(all.indeterminate).toBe(true);
        click(boxes[1]);
        expect(all.checked).toBe(true);
        expect(all.indeterminate).toBe(false);
    });

    test("makes a row bold while its role is Admin", () => {
        document.body.innerHTML = `
            <form id="add-from-form">
                <div id="add-from-all-box" hidden><input type="checkbox" id="add-from-all"></div>
                <table><tbody><tr><td><input type="checkbox" class="add-from-pick"></td>
                    <td><select class="add-from-role"><option value="jury" selected>Juror</option>
                        <option value="admin">Admin</option></select></td></tr></tbody></table>
            </form>`;
        init(document);
        const select = document.querySelector("select");
        const row = document.querySelector("tr");
        select.value = "admin";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        expect(row.classList.contains("fw-bold")).toBe(true);
        select.value = "jury";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        expect(row.classList.contains("fw-bold")).toBe(false);
    });

    test("stays hidden when nobody can be ticked, and does nothing without the form", () => {
        const { box } = page([true, true]);
        expect(box.hidden).toBe(true);
        document.body.innerHTML = "<p>nothing</p>";
        expect(() => init(document)).not.toThrow();
    });
});
