/**
 * @jest-environment jsdom
 */
"use strict";

// The pager's page box (pager-jump.js): the page typed, kept within the list's pages, by the
// list's page URL with {page} in place of the number.

const { pageUrl, fitInWindow } = require("../public/javascripts/pager-jump");

function form(value, max = "41", url = "/gallery/round/2/user/10/page/{page}?sort=file") {
    document.body.innerHTML = `
        <form class="pager-jump" data-page-url="${url}">
            <input type="number" name="page" min="1" max="${max}" value="${value}">
        </form>`;
    return document.querySelector("form");
}

describe("pageUrl", () => {

    test("goes to the page typed", () => {
        expect(pageUrl(form("7"))).toBe("/gallery/round/2/user/10/page/7?sort=file");
        expect(pageUrl(form("3", "41", "/contest/5/files?page={page}&region=07"))).toBe("/contest/5/files?page=3&region=07");
    });

    test("keeps the page within the list's pages", () => {
        expect(pageUrl(form("1000"))).toBe("/gallery/round/2/user/10/page/41?sort=file");
        expect(pageUrl(form("0"))).toBe("/gallery/round/2/user/10/page/1?sort=file");
        expect(pageUrl(form("-3"))).toBe("/gallery/round/2/user/10/page/1?sort=file");
    });

    test("goes nowhere without a number", () => {
        expect(pageUrl(form(""))).toBeNull();
    });
});

describe("fitInWindow", () => {

    /** A box that ends at `right` when opened rightwards, and 280px further left when not. */
    function box(right) {
        document.body.innerHTML = `<div class="dropdown-menu pager-jump-box"></div>`;
        const el = document.querySelector("div");
        el.getBoundingClientRect = () => ({ right: el.classList.contains("dropdown-menu-end") ? right - 280 : right });
        return el;
    }

    test("opens under the items shown, rightwards, when it fits in the window", () => {
        const el = box(1200);
        fitInWindow(el, 1300);
        expect(el.classList.contains("dropdown-menu-end")).toBe(false);
    });

    test("opens leftwards only when rightwards would leave the window", () => {
        const el = box(1350);
        fitInWindow(el, 1300);
        expect(el.classList.contains("dropdown-menu-end")).toBe(true);
    });

    test("decides again on every opening, as the window may have been resized", () => {
        const el = box(1350);
        el.classList.add("dropdown-menu-end");
        fitInWindow(el, 1400);
        expect(el.classList.contains("dropdown-menu-end")).toBe(false);
    });
});

describe("opening the box", () => {

    test("focuses and selects the number when Bootstrap shows the dropdown", () => {
        const bootstrap = require("bootstrap/dist/js/bootstrap.bundle.js");
        document.body.innerHTML = `
            <nav class="navbar navbar-expand"><ul class="navbar-nav"><li class="nav-item">
            <ul class="pagination" id="pagenav">
                <li class="page-item dropdown pager-jump-menu">
                    <button type="button" class="page-link dropdown-toggle pager-range" data-bs-toggle="dropdown"
                        data-bs-auto-close="outside" aria-expanded="false">46–60 of 600</button>
                    <div class="dropdown-menu pager-jump-box">
                        <form class="pager-jump" data-page-url="/contest/5/files?page={page}">
                            <input type="number" name="page" min="1" max="40" value="4">
                        </form>
                    </div>
                </li>
            </ul></li></ul></nav>`;
        const toggle = document.querySelector(".pager-range");

        toggle.click();

        expect(document.querySelector(".pager-jump-box").classList.contains("show")).toBe(true);
        expect(document.activeElement).toBe(document.querySelector("input[name=page]"));
    });
});
