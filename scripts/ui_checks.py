"""Browser checks on the screenshot fixture (scripts/ui-shots.py shoot --checks), in Chrome:

- vote, then Back (docs/plans/2026-11-01-open-followups.md, 3.3): in the juror's large view
  of the binary and of the rated round, a vote by key (Space / Del, a digit) posts to a path
  on this host and moves on; Back then shows the file as a fresh load does, with the vote,
  not the page as it was before it (the pages are no-store);
- the large view's side sections (large-sections.js) are drawn in the viewer's stored state
  from the first frame that shows them, not first in their default state.

`run` returns the number of failed checks, after printing each check's result. The checks
vote, so ui-shots.py runs them after the shots.
"""
import base64
from urllib.parse import urlparse

VIEWPORT = {"width": 1366, "height": 900}

# The large view's state that a vote changes: the number by the stars, and which of the
# binary round's select and reject buttons it shows.
SIGNATURE_JS = """
(() => {
  const n = document.getElementById('rate-number');
  return JSON.stringify({
    rate: n ? n.textContent.trim() : null,
    select: !!document.querySelector('.btn-success[data-vote-url]'),
    reject: !!document.querySelector('.btn-danger[data-vote-url]')
  });
})()
"""

# Before any of the page's scripts: the viewer's stored states (the details closed, the
# next files open, the opposite of the defaults), and a record of the sections' state in the
# first animation frame that has them, which runs before that frame is painted.
SECTIONS_PROBE_JS = """
try {
  localStorage.setItem('wlx.large.section.large-info', 'closed');
  localStorage.setItem('wlx.large.section.large-next', 'open');
} catch (e) {}
(function probe() {
  requestAnimationFrame(function () {
    var info = document.getElementById('large-info'), next = document.getElementById('large-next');
    if (!info) { if (document.readyState !== 'complete') probe(); return; }
    window.__wlxFirstFrame = {
      readyState: document.readyState,
      infoOpen: info.classList.contains('show'),
      nextOpen: next ? next.classList.contains('show') : null
    };
  });
})();
"""


def sign_in(browser, base, account):
    ctx = browser.new_context(viewport=VIEWPORT, locale="en-US", color_scheme="light")
    ctx.add_cookies([{"name": "theme", "value": "light", "url": base}])
    page = ctx.new_page()
    page.goto(base + "/auth")
    page.fill("input[name=login]", account["login"])
    page.fill("input[name=password]", account["password"])
    with page.expect_navigation():
        page.press("input[name=password]", "Enter")
    if "/auth" in urlparse(page.url).path:
        raise SystemExit("signing in failed")
    page.close()
    return ctx


# a 1x1 PNG, for every image from Commons or the image proxy, which aren't fetched
PIXEL = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")


def block_commons(ctx):
    ctx.route("**/*", lambda r: r.fulfill(status=200, content_type="image/png", body=PIXEL)
              if ("/wikipedia/commons/" in r.request.url or "wikimedia.org" in r.request.url)
              else r.continue_())


def vote_then_back(ctx, base, path, keys):
    """Votes on the file at `path` with the first of `keys` that changes it, goes Back, and
    compares the page with a fresh load of it. Returns (ok, message)."""
    page = ctx.new_page()
    errors = []
    page.on("console", lambda m: errors.append(m.text[:200]) if m.type == "error" else None)
    page.on("pageerror", lambda e: errors.append(str(e)[:200]))
    posts = []
    page.on("request", lambda r: posts.append(r.url) if r.method == "POST" else None)
    url = base + path
    page.goto(url, wait_until="networkidle")
    before = page.evaluate(SIGNATURE_JS)
    for key in keys(before):
        posts.clear()
        with page.expect_navigation():
            page.keyboard.press(key)
        page.wait_for_load_state("networkidle")
        moved_to = page.url
        page.go_back(wait_until="networkidle")
        back_url = page.url
        back = page.evaluate(SIGNATURE_JS)
        fresh_page = ctx.new_page()
        fresh_page.goto(url, wait_until="networkidle")
        fresh = fresh_page.evaluate(SIGNATURE_JS)
        fresh_page.close()
        if fresh == before:
            continue  # the vote stored the rate the file had: try the next key
        page.close()
        bad_posts = [p for p in posts if urlparse(p).scheme != urlparse(base).scheme
                     or urlparse(p).netloc != urlparse(base).netloc]
        ok = (back == fresh and back_url == url and len(posts) == 1 and not bad_posts
              and moved_to != url and not errors)
        return ok, (f"key {key}: posted {[urlparse(p).path for p in posts]}, moved to {urlparse(moved_to).path}; "
                    f"before {before}, after Back {back}, fresh {fresh}"
                    + (f"; console errors {errors}" if errors else "")
                    + ("" if back_url == url else f"; Back went to {back_url}"))
    page.close()
    return False, f"no key changed the file: before {before}"


def sections_first_frame(ctx, base, path, loads=5):
    """Loads `path` `loads` times with the probe; returns (ok, message)."""
    ctx.add_init_script(SECTIONS_PROBE_JS)
    seen = []
    for _ in range(loads):
        page = ctx.new_page()
        page.goto(base + path, wait_until="load")
        page.wait_for_timeout(300)
        seen.append(page.evaluate("window.__wlxFirstFrame || null"))
        page.close()
    ok = all(f is not None and f["infoOpen"] is False and f["nextOpen"] in (True, None) for f in seen)
    return ok, f"first frames: {seen}"


def run(s, channel="chrome"):
    from playwright.sync_api import sync_playwright
    base = s["baseUrl"]
    j, rb, rr, p, c = s["juror"]["id"], s["roundBinaryId"], s["roundRatingId"], s["pageId"], s["contestId"]
    results = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel=channel, headless=True)

        juror = sign_in(browser, base, s["juror"])
        block_commons(juror)
        results.append(("vote then Back, binary round", *vote_then_back(
            juror, base, f"/large/round/{rb}/user/{j}/pageid/{p}",
            lambda sig: (["Space", "Delete"] if '"select":true' in sig else ["Delete", "Space"]))))
        if s.get("ratedPageId"):
            results.append(("vote then Back, rated round", *vote_then_back(
                juror, base, f"/large/round/{rr}/user/{j}/pageid/{s['ratedPageId']}",
                lambda sig: ["3", "4"])))
        juror.close()

        for name, account, path in [
            ("side sections at the first frame, large view", s["juror"], f"/large/round/{rb}/user/{j}/pageid/{p}"),
            ("side sections at the first frame, contest file", s["organizer"], f"/contest/{c}/files/{p}"),
        ]:
            ctx = sign_in(browser, base, account)
            block_commons(ctx)
            results.append((name, *sections_first_frame(ctx, base, path)))
            ctx.close()
        browser.close()

    for name, ok, message in results:
        print(f"[ui-checks] {'ok    ' if ok else 'FAILED'} {name}: {message}")
    return sum(1 for _, ok, _ in results if not ok)
