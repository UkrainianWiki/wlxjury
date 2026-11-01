#!/usr/bin/env python3
"""Screenshots of a fixed list of pages, for comparing the UI before and after a change
(docs/plans/2026-10-31-theme-modes-and-design-system.md, "Screenshots").

Needs Python with Playwright and Pillow, and Chrome (or Edge) installed; Playwright's own
browsers aren't downloaded. A venv inside the checkout keeps it local:

    python -m venv target/ui-venv
    target/ui-venv/Scripts/python -m pip install "playwright==1.48.0" "pillow<11"

Take screenshots (`--start-server` runs `Test/runMain gatling.setup.UiShotsServer`, waits
for target/ui-shots/server.json and stops the server afterwards; without it, the script
uses a server that is already running):

    target/ui-venv/Scripts/python scripts/ui-shots.py shoot --label baseline --start-server

Colour modes (`--mode`, comma-separated, each saved in target/ui-shots/<label>/<mode>/):
`light` and `dark` set the theme cookie in the browser; `auto` sets none and emulates the
browser's prefers-color-scheme as `--scheme` (dark by default), so `auto` shows the dark
mode the layout's script picks:

    target/ui-venv/Scripts/python scripts/ui-shots.py shoot --label phase3 --mode light,dark,auto --start-server --csp

Compare two labels (writes diff images to target/ui-shots/diff/<a>-vs-<b>/<mode>/):

    target/ui-venv/Scripts/python scripts/ui-shots.py diff baseline phase1

Images from Commons (thumbnails, the image proxy, and the "/<width>px-" URLs of the fixture's
images, which have no URL) are replaced by one fixed placeholder, so
the shots don't depend on the network; any other request to another host is blocked and
listed (the pages load everything from this host since Phase 1).
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "target" / "ui-shots"
SERVER_JSON = SHOTS / "server.json"
STOP_FILE = SHOTS / "server.stop"
SBT_LAUNCHER = os.environ.get("SBT_LAUNCHER", "C:/Program Files (x86)/sbt/bin/sbt-launch.jar")

# The only hosts a page may load from: the app's own.
ALLOWED_HOSTS = {"localhost", "127.0.0.1"}
VIEWPORT = {"width": 1366, "height": 900}
MAX_HEIGHT = 4000


# A shot's script that marks two gallery tiles or thumbnails as deleted on Commons and makes
# two fail to load, as unitegallery.js and the large view do (the fixture has neither), so that
# the marks' colours show in each mode.
MARKS_JS = """
(() => {
  const tiles = [...document.querySelectorAll('.ug-thumb-wrapper.ug-tile')];
  tiles.slice(1, 3).forEach(t => t.classList.add('wlx-deleted'));
  tiles.slice(4, 6).forEach(t => t.classList.add('wlx-thumb-failed'));
  const thumbs = [...document.querySelectorAll('div.thumb_small')];
  thumbs.slice(0, 1).forEach(t => t.classList.add('wlx-deleted'));
  thumbs.slice(2, 3).forEach(t => t.classList.add('wlx-thumb-failed'));
})();
"""


def pages(s):
    """(name, user, path[, clicks[, script]]): user is "juror", "organizer" or None (signed
    out); clicks, CSS selectors clicked in turn before the shot (a dropdown opened, say);
    script, JavaScript run after them."""
    c, rb, rr, j, p = s["contestId"], s["roundBinaryId"], s["roundRatingId"], s["juror"]["id"], s["pageId"]
    return [
        ("login", None, "/auth"),
        ("error404-anon", None, "/no-such-page"),
        ("gallery", "juror", f"/gallery/round/{rb}/user/{j}/page/1"),
        ("filelist", "juror", f"/filelist/round/{rb}/user/{j}/page/1"),
        ("large", "juror", f"/large/round/{rb}/user/{j}/pageid/{p}"),
        ("large-rated", "juror", f"/large/round/{rr}/user/{j}/pageid/{s.get('ratedPageId', 0)}"),
        ("error404", "juror", "/no-such-page"),
        ("byrate", "organizer", f"/byrate/round/{rr}/user/0/page/1"),
        ("contestfiles", "organizer", f"/contest/{c}/files"),
        ("contestfile-large", "organizer", f"/contest/{c}/files/{p}"),
        ("roundstat", "organizer", f"/roundstat/{rb}"),
        ("rounds", "organizer", f"/admin/rounds?contestId={c}"),
        ("editround", "organizer", f"/admin/rounds/edit?id={rb}&contestId={c}"),
        ("users", "organizer", f"/admin/users?contestId={c}"),
        ("edituser", "organizer", f"/users/edit/{j}"),
        # menus and dropdowns opened
        ("gallery-pagerbox", "juror", f"/gallery/round/{rb}/user/{j}/page/1", [".pager-range"]),
        ("gallery-usermenu", "juror", f"/gallery/round/{rb}/user/{j}/page/1",
         ["nav .navbar-nav:last-child > .dropdown:last-child > [data-bs-toggle=dropdown]"]),
        ("byrate-viewmenu", "organizer", f"/byrate/round/{rr}/user/0/page/1",
         ["a.dropdown-toggle:has(.bi-bar-chart-fill)"]),
        ("contestfiles-regionmenu", "organizer", f"/contest/{c}/files", [".region-menu > [data-bs-toggle=dropdown]"]),
        ("editround-new", "organizer", f"/admin/rounds/edit?contestId={c}"),
        ("editround-new-jurors", "organizer", f"/admin/rounds/edit?contestId={c}", ["#jurors-ts-control"]),
        ("editround-new-prevround", "organizer", f"/admin/rounds/edit?contestId={c}",
         ["#headingImageFiltering a", "#previousRound-ts-control"]),
        # the theme switches, and the deleted / failed marks
        ("users-usermenu", "organizer", f"/admin/users?contestId={c}",
         ["nav .navbar-nav:last-child > .dropdown:last-child > [data-bs-toggle=dropdown]"]),
        ("contestfiles-marks", "organizer", f"/contest/{c}/files", [], MARKS_JS),
        ("contestfile-large-marks", "organizer", f"/contest/{c}/files/{p}", [], MARKS_JS),
    ]


def placeholder_png():
    from PIL import Image, ImageDraw
    img = Image.new("RGB", (640, 480), (120, 140, 160))
    d = ImageDraw.Draw(img)
    d.rectangle([40, 40, 600, 440], outline=(230, 230, 230), width=8)
    d.line([40, 40, 600, 440], fill=(230, 230, 230), width=4)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def start_server(log_path, csp=False):
    SHOTS.mkdir(parents=True, exist_ok=True)
    for f in (SERVER_JSON, STOP_FILE):
        if f.exists():
            f.unlink()
    cmd = ["java", "-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", SBT_LAUNCHER,
           'set Test / javaOptions ++= Seq("-Xmx1g", "-Dfile.encoding=UTF-8")',
           # the test classpath has no public/ assets (Play's Assets serves them from the
           # classpath): add the packaged assets jar, as the production classpath has it
           'set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)']
    if csp:
        # the production content security policy (GatlingTestFixture.cspConfig)
        cmd.append('set Test / javaOptions += "-Dui.csp=production"')
    cmd.append("Test/runMain gatling.setup.UiShotsServer")
    log = open(log_path, "w", encoding="utf-8")
    print(f"[ui-shots] starting the server, log: {log_path}")
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    deadline = time.time() + 1800
    while not SERVER_JSON.exists():
        if proc.poll() is not None:
            raise SystemExit(f"the server exited ({proc.returncode}) before it was ready; see {log_path}")
        if time.time() > deadline:
            proc.kill()
            raise SystemExit("the server wasn't ready in 30 minutes")
        time.sleep(2)
    time.sleep(1)
    return proc


def stop_server(proc):
    STOP_FILE.touch()
    try:
        proc.wait(timeout=180)
    except subprocess.TimeoutExpired:
        proc.kill()


def shoot(args):
    proc = start_server(SHOTS / f"server-{args.label}.log", args.csp) if args.start_server else None
    try:
        for mode in args.mode.split(","):
            if mode not in ("light", "dark", "auto"):
                raise SystemExit(f"unknown mode {mode}")
            print(f"[ui-shots] mode {mode}" + (f" (browser scheme {args.scheme})" if mode == "auto" else ""))
            shoot_mode(args, mode)
    finally:
        if proc:
            stop_server(proc)


def shoot_mode(args, mode):
    from playwright.sync_api import sync_playwright
    s = json.loads(SERVER_JSON.read_text(encoding="utf-8"))
    base = s["baseUrl"]
    out = SHOTS / args.label / mode
    out.mkdir(parents=True, exist_ok=True)
    png = placeholder_png()
    selected = set(args.only.split(",")) if args.only else None
    external = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel=args.channel, headless=True)

        def route(r):
            url = urlparse(r.request.url)
            # the fixture's images have no URL, so the pages ask for "/<width>px-"
            if ("/wikipedia/commons/" in url.path or url.hostname.endswith("wikimedia.org")
                    or re.match(r"^/\d+px-", url.path)):
                return r.fulfill(status=200, content_type="image/png", body=png)
            if url.hostname in ALLOWED_HOSTS:
                return r.continue_()
            external.append(url.hostname + " (blocked)")
            return r.abort()

        contexts = {}

        def context(user):
            if user in contexts:
                return contexts[user]
            # light and dark: the theme cookie; auto: none, and the browser's scheme
            ctx = browser.new_context(viewport=VIEWPORT, device_scale_factor=1, locale="en-US",
                                      reduced_motion="reduce",
                                      color_scheme=args.scheme if mode == "auto" else "light")
            if mode != "auto":
                ctx.add_cookies([{"name": "theme", "value": mode, "url": base}])
            ctx.route("**/*", route)
            if user:
                page = ctx.new_page()
                page.on("console", lambda m: print(f"    console {m.type} (sign-in): {m.text[:200]}")
                        if m.type == "error" else None)
                page.goto(base + "/auth")
                page.fill("input[name=login]", s[user]["login"])
                page.fill("input[name=password]", s[user]["password"])
                with page.expect_navigation():
                    page.press("input[name=password]", "Enter")
                if "/auth" in urlparse(page.url).path:
                    raise SystemExit(f"signing in as {user} failed")
                page.close()
            contexts[user] = ctx
            return ctx

        for name, user, path, *rest in pages(s):
            if selected and name not in selected:
                continue
            page = context(user).new_page()
            failed = []
            page.on("requestfailed", lambda r: failed.append(f"{r.url[:120]} ({r.failure})"))
            # console errors, CSP violations among them
            page.on("console", lambda m: failed.append(f"console {m.type}: {m.text[:200]}")
                    if m.type == "error" or "Content Security Policy" in m.text else None)
            page.on("response", lambda r: failed.append(f"{r.url[:120]} ({r.status})")
                    if r.status >= 400 and r.url != base + path else None)
            response = page.goto(base + path, wait_until="networkidle", timeout=120000)
            page.add_style_tag(content="*{caret-color:transparent !important}")
            page.wait_for_timeout(1500)
            for selector in (rest[0] if rest else []):
                page.click(selector)
                page.wait_for_timeout(700)
            if len(rest) > 1:
                page.evaluate(rest[1])
                page.wait_for_timeout(300)
            shown = page.evaluate("[document.documentElement.getAttribute('data-bs-theme'),"
                                  " document.documentElement.getAttribute('data-theme-pref')]")
            height = min(page.evaluate("document.documentElement.scrollHeight"), MAX_HEIGHT)
            file = out / f"{name}.png"
            page.screenshot(path=str(file), full_page=True,
                            clip={"x": 0, "y": 0, "width": VIEWPORT["width"], "height": max(height, VIEWPORT["height"])})
            print(f"{name:18} {response.status if response else '-'} {shown[0]}/{shown[1]} {path} -> {file.relative_to(ROOT)}")
            expected = args.scheme if mode == "auto" else mode
            if shown[0] != expected or shown[1] != mode:
                print(f"    mode shown {shown}, expected {expected}/{mode}")
            if args.csp and response is not None and "content-security-policy" not in response.headers:
                print("    no Content-Security-Policy header")
            for f in failed[:10]:
                print(f"    failed: {f}")
            page.close()
        browser.close()
    if external:
        print("external hosts requested:", ", ".join(sorted(set(external))))


def diff(args):
    from PIL import Image, ImageChops
    out = SHOTS / "diff" / f"{args.a}-vs-{args.b}" / args.mode
    out.mkdir(parents=True, exist_ok=True)
    da, db = SHOTS / args.a / args.mode, SHOTS / args.b / args.mode
    names = sorted({p.name for p in da.glob("*.png")} | {p.name for p in db.glob("*.png")})
    differing = 0
    for n in names:
        pa, pb = da / n, db / n
        if not pa.exists() or not pb.exists():
            print(f"{n:24} missing in {'a' if not pa.exists() else 'b'}")
            differing += 1
            continue
        a, b = Image.open(pa).convert("RGB"), Image.open(pb).convert("RGB")
        if a.size != b.size:
            w, h = max(a.size[0], b.size[0]), max(a.size[1], b.size[1])
            a2, b2 = Image.new("RGB", (w, h), (255, 0, 255)), Image.new("RGB", (w, h), (255, 0, 255))
            a2.paste(a), b2.paste(b)
            a, b = a2, b2
        d = ImageChops.difference(a, b)
        box = d.getbbox()
        if box is None:
            print(f"{n:24} identical")
            continue
        differing += 1
        mask = d.convert("L").point(lambda v: 255 if v > 0 else 0)
        changed = sum(1 for v in mask.getdata() if v)
        # the new shot, dimmed, with changed pixels in red
        overlay = Image.blend(b, Image.new("RGB", b.size, (255, 255, 255)), 0.6)
        overlay.paste((255, 0, 0), mask=mask)
        overlay.save(out / n)
        sizes = "" if Image.open(pa).size == Image.open(pb).size else f" size {Image.open(pa).size}->{Image.open(pb).size}"
        print(f"{n:24} {changed} px differ, box {box}{sizes}")
    print(f"{differing} of {len(names)} differ; diff images in {out.relative_to(ROOT)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("shoot")
    s.add_argument("--label", required=True)
    s.add_argument("--mode", default="light", help="light, dark, auto, or several comma-separated")
    s.add_argument("--scheme", default="dark", choices=["light", "dark"],
                   help="the browser's prefers-color-scheme for --mode auto")
    s.add_argument("--start-server", action="store_true")
    s.add_argument("--csp", action="store_true",
                   help="with --start-server: the server sends the production content security policy")
    s.add_argument("--channel", default="chrome", help="chrome or msedge")
    s.add_argument("--only", help="comma-separated page names")
    d = sub.add_parser("diff")
    d.add_argument("a")
    d.add_argument("b")
    d.add_argument("--mode", default="light")
    args = ap.parse_args()
    shoot(args) if args.cmd == "shoot" else diff(args)


if __name__ == "__main__":
    main()
