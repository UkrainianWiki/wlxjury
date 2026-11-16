/**
 * @jest-environment jsdom
 */
"use strict";

// The users page's "Find an existing account" (account-search.js, accounts plan A-1): the text
// goes in a POST's body with the CSRF token, never in a URL; the results are drawn as text, with
// "Add to this contest" copied from the page's template for each account not yet a member.

const { search, renderResults, init } = require("../public/javascripts/account-search");

function page() {
    document.body.innerHTML = `
        <section id="account-search" data-search-url="/admin/users/search?contestId=10" data-csrf-token="tok123"
            data-min-length="3" data-text-none="No account found." data-text-more="Only the first 20."
            data-text-short="Type at least 3 characters." data-text-failed="The search failed."
            data-text-no-email="no email" data-text-verified="verified" data-text-member="Already in this contest:">
            <form id="account-search-form" method="post" action="/admin/users/search?contestId=10">
                <input type="search" name="q" id="account-search-text">
                <button type="submit">Search</button>
            </form>
            <p id="account-search-status" role="status"></p>
            <table id="account-search-results" class="d-none"><tbody></tbody></table>
            <template id="account-search-add">
                <form method="post" action="/admin/users/add-existing?contestId=10">
                    <input type="hidden" name="csrfToken" value="tok123">
                    <input type="hidden" name="accountId" value="">
                    <label data-for="role">Role</label>
                    <select name="role" data-id="role"><option value="jury" selected>Juror</option></select>
                    <button type="submit">Add to this contest</button>
                    <input type="checkbox" name="sendInvitation" value="true" data-id="send">
                    <label data-for="send">Send an invitation email</label>
                    <input type="checkbox" name="tellOwner" value="true" data-id="tell">
                    <label data-for="tell">Tell its owner by email</label>
                </form>
            </template>
        </section>`;
    return document.getElementById("account-search");
}

const outside = {
    id: 7, name: "Anna <b>Kovalenko</b>", email: "a***@example.org", emailFull: false, wikiAccount: "AnnaWiki",
    wikiVerified: true, contests: [], member: null, editUrl: null
};
const member = {
    id: 8, name: "Anna Member", email: "member@example.org", emailFull: true, wikiAccount: null,
    wikiVerified: false, contests: [{ name: "WLM 2026", role: "Juror" }], member: "Juror",
    editUrl: "/users/edit/8?contestId=10"
};

function answer(body, status = 200) {
    return jest.fn(() => Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) }));
}

function rows(box) {
    return Array.from(box.querySelectorAll("tbody tr"));
}

describe("account-search", () => {

    test("posts the trimmed text in the body with the CSRF token, never in the URL", async () => {
        const box = page();
        const fetchImpl = answer({ results: [], more: false, unsearchable: false });
        await search(box, "  anna k ", fetchImpl);
        expect(fetchImpl).toHaveBeenCalledTimes(1);
        const [url, options] = fetchImpl.mock.calls[0];
        expect(url).toBe("/admin/users/search?contestId=10");
        expect(url).not.toContain("anna");
        expect(options.method).toBe("POST");
        expect(options.headers["Csrf-Token"]).toBe("tok123");
        expect(options.headers["X-Requested-With"]).toBe("XMLHttpRequest");
        expect(options.body).toBe("q=anna+k");
        expect(box.querySelector("#account-search-status").textContent).toBe("No account found.");
        expect(box.querySelector("#account-search-results").classList.contains("d-none")).toBe(true);
    });

    test("sends nothing under the least length, and says so", async () => {
        const box = page();
        const fetchImpl = answer({ results: [] });
        await expect(search(box, " an ", fetchImpl)).resolves.toBe(0);
        expect(fetchImpl).not.toHaveBeenCalled();
        expect(box.querySelector("#account-search-status").textContent).toBe("Type at least 3 characters.");
    });

    test("draws each account as text: masked email, wiki account, contests, add form or member role", async () => {
        const box = page();
        const shown = await search(box, "anna", answer({ results: [outside, member], more: true }));
        expect(shown).toBe(2);
        const [first, second] = rows(box);
        expect(box.querySelector("#account-search-results").classList.contains("d-none")).toBe(false);
        expect(box.querySelector("#account-search-status").textContent).toBe("Only the first 20.");

        // a name is text, never markup; no edit link where the server gives none
        const cells = first.querySelectorAll("td");
        expect(cells[0].textContent).toBe("Anna <b>Kovalenko</b>");
        expect(cells[0].querySelector("b")).toBeNull();
        expect(cells[0].querySelector("a")).toBeNull();
        expect(cells[1].textContent).toBe("a***@example.org");
        expect(cells[2].textContent).toBe("AnnaWiki verified");
        expect(cells[3].textContent).toBe("");
        const form = cells[4].querySelector("form");
        expect(form.getAttribute("action")).toBe("/admin/users/add-existing?contestId=10");
        expect(form.querySelector('input[name="accountId"]').value).toBe("7");
        expect(form.querySelector('input[name="csrfToken"]').value).toBe("tok123");
        expect(form.querySelector("select").id).toBe("account-search-role-7");
        expect(form.querySelector('label[data-for="role"]').htmlFor).toBe("account-search-role-7");
        expect(form.querySelector('input[name="sendInvitation"]').checked).toBe(false);
        // never a mail nobody chose: both mails unticked, each with its own label
        const tell = form.querySelector('input[name="tellOwner"]');
        expect(tell.checked).toBe(false);
        expect(tell.id).toBe("account-search-tell-7");
        expect(form.querySelector('label[data-for="tell"]').htmlFor).toBe("account-search-tell-7");

        const memberCells = second.querySelectorAll("td");
        expect(memberCells[0].querySelector("a").getAttribute("href")).toBe("/users/edit/8?contestId=10");
        expect(memberCells[1].textContent).toBe("member@example.org");
        expect(memberCells[3].textContent).toBe("WLM 2026: Juror");
        expect(memberCells[4].textContent).toBe("Already in this contest: Juror");
        expect(memberCells[4].querySelector("form")).toBeNull();
    });

    test("says no email for an account without one, and replaces the earlier results", () => {
        const box = page();
        renderResults(box, { results: [outside, member] });
        renderResults(box, { results: [{ ...outside, id: 9, email: null }] });
        const [only, ...rest] = rows(box);
        expect(rest).toHaveLength(0);
        expect(only.querySelectorAll("td")[1].textContent).toBe("no email");
        expect(box.querySelector("#account-search-status").textContent).toBe("");
    });

    test("says the search failed on a refusal or a network error, showing nothing", async () => {
        const box = page();
        renderResults(box, { results: [outside] });
        await search(box, "anna", answer({ error: "forbidden" }, 403));
        expect(rows(box)).toHaveLength(0);
        expect(box.querySelector("#account-search-status").textContent).toBe("The search failed.");
        await search(box, "anna", jest.fn(() => Promise.reject(new Error("offline"))));
        expect(box.querySelector("#account-search-status").textContent).toBe("The search failed.");
    });

    test("sends the form through the script, not as a page load", async () => {
        const box = page();
        const fetchImpl = answer({ results: [member] });
        expect(init(document, fetchImpl)).toBe(box);
        box.querySelector("#account-search-text").value = "anna";
        const event = new Event("submit", { bubbles: true, cancelable: true });
        box.querySelector("#account-search-form").dispatchEvent(event);
        expect(event.defaultPrevented).toBe(true);
        expect(fetchImpl).toHaveBeenCalledTimes(1);
        await new Promise((resolve) => setTimeout(resolve, 0));
        expect(rows(box)).toHaveLength(1);
    });

    test("does nothing on a page without the box", () => {
        document.body.innerHTML = "<p>no search</p>";
        expect(init(document, answer({}))).toBeNull();
    });
});
