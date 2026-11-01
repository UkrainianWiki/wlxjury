/**
 * @jest-environment jsdom
 */
"use strict";

// The colour mode in the browser: the login page's switch (theme-switch.js), which sets the
// theme cookie and the page's mode with no request, and the layout's first script, which
// resolves "auto" from prefers-color-scheme and follows its changes.

const fs = require("fs");
const path = require("path");

/** A matchMedia stub whose dark scheme can be switched, telling its change listeners. */
function stubScheme(dark) {
    const listeners = [];
    const query = {
        matches: dark,
        addEventListener: (type, f) => { if (type === "change") listeners.push(f); },
    };
    window.matchMedia = jest.fn(() => query);
    return {
        set(value) { query.matches = value; listeners.forEach(f => f({ matches: value })); },
        listeners,
    };
}

function clearCookies() {
    document.cookie.split(";").map(c => c.split("=")[0].trim()).filter(Boolean)
        .forEach(name => { document.cookie = name + "=; Max-Age=0; Path=/"; });
}

/** The layout's first inline script (layout.scala.html), as the page runs it. */
const autoScript = (() => {
    const layout = fs.readFileSync(path.join(__dirname, "../app/views/layout.scala.html"), "utf8");
    const head = layout.substring(layout.indexOf("<head>"));
    const match = /<script>([\s\S]*?)<\/script>/.exec(head);
    return match[1];
})();

function runAutoScript() {
    // eslint-disable-next-line no-new-func
    new Function(autoScript)();
}

describe("the layout's auto script", () => {

    beforeEach(() => {
        document.documentElement.removeAttribute("data-bs-theme");
        document.documentElement.removeAttribute("data-theme-pref");
    });

    test("is plain script, before any stylesheet, with no Twirl expression in it", () => {
        expect(autoScript).not.toMatch(/@/);
        expect(autoScript).toMatch(/prefers-color-scheme: dark/);
    });

    test("sets the browser's scheme for auto, and follows its changes", () => {
        const scheme = stubScheme(true);
        document.documentElement.setAttribute("data-theme-pref", "auto");
        runAutoScript();
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("dark");
        scheme.set(false);
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("light");
        scheme.set(true);
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("dark");
    });

    test("leaves an explicit mode alone, also when the scheme changes", () => {
        const scheme = stubScheme(true);
        document.documentElement.setAttribute("data-theme-pref", "light");
        document.documentElement.setAttribute("data-bs-theme", "light");
        runAutoScript();
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("light");
        scheme.set(false);
        scheme.set(true);
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("light");
    });

    test("falls back to light without matchMedia", () => {
        delete window.matchMedia;
        document.documentElement.setAttribute("data-theme-pref", "auto");
        runAutoScript();
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("light");
    });
});

describe("the login page's switch", () => {
    let themeSwitch;

    beforeEach(() => {
        clearCookies();
        document.documentElement.setAttribute("data-theme-pref", "auto");
        document.documentElement.setAttribute("data-bs-theme", "light");
        document.body.innerHTML = `
            <div id="theme-switch" role="group">
                <button type="button" data-theme-value="light" aria-pressed="false">Light</button>
                <button type="button" data-theme-value="dark" aria-pressed="false">Dark</button>
                <button type="button" data-theme-value="auto" class="active" aria-pressed="true">System</button>
            </div>`;
        jest.resetModules();
        stubScheme(false);
        themeSwitch = require("../public/javascripts/theme-switch");
    });

    function click(value) {
        document.querySelector(`[data-theme-value="${value}"]`).click();
    }

    test("takes light, dark and auto only", () => {
        expect(themeSwitch.parse("dark")).toBe("dark");
        expect(themeSwitch.parse("Dark")).toBe("auto");
        expect(themeSwitch.parse("x\"; Path=/evil")).toBe("auto");
        expect(themeSwitch.parse(null)).toBe("auto");
    });

    test("writes a Lax cookie for the whole site for a year, Secure on https", () => {
        expect(themeSwitch.cookieFor("dark", false)).toBe("theme=dark; Path=/; Max-Age=31536000; SameSite=Lax");
        expect(themeSwitch.cookieFor("light", true)).toBe("theme=light; Path=/; Max-Age=31536000; SameSite=Lax; Secure");
        expect(themeSwitch.cookieFor("<x>", false)).toMatch(/^theme=auto;/);
    });

    test("sets the cookie and the page's mode, and marks the button, with no request", () => {
        const fetchSpy = jest.fn();
        window.fetch = fetchSpy;
        click("dark");
        expect(document.cookie).toContain("theme=dark");
        expect(document.documentElement.getAttribute("data-theme-pref")).toBe("dark");
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("dark");
        expect(document.querySelector('[data-theme-value="dark"]').getAttribute("aria-pressed")).toBe("true");
        expect(document.querySelector('[data-theme-value="dark"]').classList.contains("active")).toBe(true);
        expect(document.querySelector('[data-theme-value="auto"]').getAttribute("aria-pressed")).toBe("false");
        expect(document.querySelector('[data-theme-value="auto"]').classList.contains("active")).toBe(false);

        click("light");
        expect(document.cookie).toContain("theme=light");
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("light");
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    test("back to auto takes the browser's scheme", () => {
        click("dark");
        stubScheme(true);
        click("auto");
        expect(document.cookie).toContain("theme=auto");
        expect(document.documentElement.getAttribute("data-theme-pref")).toBe("auto");
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("dark");
    });

    test("ignores a button with another value", () => {
        document.getElementById("theme-switch").insertAdjacentHTML("beforeend",
            '<button type="button" data-theme-value="purple">x</button>');
        click("purple");
        expect(document.cookie).toContain("theme=auto");
        expect(document.documentElement.getAttribute("data-bs-theme")).toBe("light");
    });
});
