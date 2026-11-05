#!/usr/bin/env python3
"""Fetches the photos for the user guides' screenshots (docs/user/images/README.md).

Reads the lists named by DOCS_PHOTOS (default `wlm-ua,wlm-intl`, comma separated):
browser-test/docs-photos-<list>.json: wlm-ua, wlm-intl, wle-ua, wle-intl (Wiki Loves Monuments or Earth,
Ukraine or other countries), public-domain
and CC0 photos from Wikimedia Commons, and writes to the git-ignored target/docs-photos/<list>/:

  good/<hash>.jpg  the photos, 1280 px wide, downloaded once from upload.wikimedia.org
  bad/<hash>.jpg   degraded copies (blur, tilted horizon, over- or underexposed, low resolution with
                   JPEG artefacts) of some of them, for the yes/no examples
  manifest.json    the photos and degraded copies of this run, in the list's order, each with its
                   title and file, the list file's SHA-256 and when it was checked: what
                   gatling.setup.DocsPhotos serves, and only while the list is unchanged

A file's name is a hash of the title and of how it was made (width, crop, variant), so a cached
file always belongs to the entry that names it. It fails closed, on every run:
  - the licence of every file in the list, cached or not, is looked up on the Commons API
    (extmetadata) and must be CC0 or public domain, and the one the list names;
  - files of the list's directories that the run doesn't select are deleted;
  - every file is written to a temporary name, then renamed;
  - a 404 is not retried, and a 429 or 503 waits as its Retry-After says.

Anonymous on Wikimedia (no credentials), with a descriptive User-Agent. A second run downloads
nothing that is cached. Needs Pillow (pip install pillow).

  python browser-test/docs-photos.py
"""
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
HERE = ROOT / "browser-test"
OUT = ROOT / "target" / "docs-photos"
API = "https://commons.wikimedia.org/w/api.php"
UA = "wlxjury-docs-photos/1.0 (https://github.com/UkrainianWiki/wlxjury; user guide screenshots; anonymous) python-urllib"
WIDTHS = {20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840}
LIST_NAME = re.compile(r"^[a-z]+(?:-[a-z]+)*$")
BATCH = 40  # titles per imageinfo query (the API's anonymous limit is 50)
TRIES = 4
MAX_WAIT = 120  # seconds: the longest Retry-After honoured


def list_names(value):
    """The list names of DOCS_PHOTOS: each a lowercase name with a browser-test/docs-photos-<name>.json."""
    names = []
    for raw in value.split(","):
        name = raw.strip()
        if not name:
            continue
        if not LIST_NAME.match(name):
            sys.exit(f"DOCS_PHOTOS: {name!r} is not a list name (wlm-ua, wlm-intl, wle-ua, wle-intl)")
        if not (HERE / f"docs-photos-{name}.json").is_file():
            sys.exit(f"DOCS_PHOTOS: no list {name} (browser-test/docs-photos-{name}.json)")
        if name not in names:
            names.append(name)
    return names


def get(url):
    """The body of `url`. A network error, a 429 or a 5xx is retried (a 429 or 503 after its
    Retry-After, if it gives one); any other HTTP error, a 404 included, stops at once."""
    for attempt in range(TRIES):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as error:
            if error.code != 429 and error.code < 500:
                raise
            if attempt == TRIES - 1:
                raise
            wait = retry_after(error.headers.get("Retry-After"), 5 * (attempt + 1))
            print(f"  HTTP {error.code}: retry in {wait} s")
            time.sleep(wait)
        except OSError as error:  # URLError, a timeout, a reset connection
            if attempt == TRIES - 1:
                raise
            print(f"  retry after {error}")
            time.sleep(5 * (attempt + 1))


def retry_after(header, default):
    """Seconds to wait from a Retry-After header (seconds; a date counts as the default)."""
    if header and header.strip().isdigit():
        wait = int(header.strip())
        if wait > MAX_WAIT:
            sys.exit(f"the server asks to wait {wait} s (Retry-After): try again later")
        return max(wait, 1)
    return default


def imageinfo(titles, width):
    """The imageinfo of each title, under the title as given (the API's normalisation undone)."""
    query = urllib.parse.urlencode({
        "action": "query", "format": "json", "prop": "imageinfo", "titles": "|".join(titles),
        "iiprop": "extmetadata|url", "iiurlwidth": width})
    result = json.loads(get(f"{API}?{query}"))["query"]
    asked = {n["to"]: n["from"] for n in result.get("normalized", [])}
    return {asked.get(p["title"], p["title"]): p["imageinfo"][0] for p in result["pages"].values() if "imageinfo" in p}


def licence_kind(name):
    """'CC0', 'public domain', or None for any other licence name."""
    text = (name or "").strip()
    if text.upper().startswith("CC0"):
        return "CC0"
    if text.lower().startswith("public domain") or re.match(r"^PD\b", text):
        return "public domain"
    return None


def check_licence(title, info, listed):
    """Stops the run unless Commons gives the file CC0 or public domain, as its list entry does."""
    meta = info.get("extmetadata") or {}
    short = meta.get("LicenseShortName", {}).get("value", "")
    kind = licence_kind(short)
    if kind is None:
        sys.exit(f"{title}: licence '{short}' is neither CC0 nor public domain")
    if licence_kind(listed) != kind:
        sys.exit(f"{title}: Commons gives '{short}', the list says '{listed}'")
    if meta.get("NonFree", {}).get("value", "").lower() == "true":
        sys.exit(f"{title}: marked non-free on Commons")


def key(*parts):
    """A file name for what `parts` describe: the same entry always gets the same name."""
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()[:24] + ".jpg"


def write_atomically(target, write):
    """Calls write(temporary path), then renames it to `target`."""
    temporary = target.with_name(target.name + ".tmp")
    try:
        write(temporary)
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()


def degrade(image, kind, p):
    if kind == "blur":
        return image.filter(ImageFilter.GaussianBlur(p["radius"]))
    if kind == "tilt":  # a tilted horizon: rotate, then crop the empty corners
        w, h = image.size
        rotated = image.rotate(p["degrees"], resample=Image.BICUBIC, expand=False)
        cx, cy, k = w / 2, h / 2, 0.78
        return rotated.crop((int(cx - w * k / 2), int(cy - h * k / 2), int(cx + w * k / 2), int(cy + h * k / 2)))
    if kind in ("overexposed", "dark"):
        return ImageEnhance.Brightness(image).enhance(p["brightness"])
    if kind == "lowres":  # tiny, heavily compressed, shown enlarged
        small = image.resize((p["width"], round(image.height * p["width"] / image.width)))
        buffer = io.BytesIO()
        small.save(buffer, "JPEG", quality=p["quality"])
        return Image.open(io.BytesIO(buffer.getvalue())).convert("RGB").resize(image.size, Image.NEAREST)
    sys.exit(f"unknown variant {kind}")


def download(title, info, target, crop):
    url = info.get("thumburl") or info["url"]
    # the API names thumbnails on thumb.wikimedia.org, with tracking parameters
    url = url.replace("https://thumb.wikimedia.org/", "https://upload.wikimedia.org/").split("?")[0]
    if not url.startswith("https://upload.wikimedia.org/"):
        sys.exit(f"{title}: unexpected host {url}")
    print(f"fetching {title}")
    data = get(url)
    probe = Image.open(io.BytesIO(data))
    if probe.format != "JPEG":
        sys.exit(f"{title}: {probe.format}, not a JPEG")
    probe.verify()
    if crop:  # a file with a street-art strip along its bottom edge: keep the rest
        image = Image.open(io.BytesIO(data)).convert("RGB")
        cropped = image.crop((0, 0, image.width, round(image.height * (1 - crop))))
        write_atomically(target, lambda path: cropped.save(path, "JPEG", quality=92))
    else:
        write_atomically(target, lambda path: path.write_bytes(data))
    time.sleep(1)


def fetch_list(name):
    list_bytes = (HERE / f"docs-photos-{name}.json").read_bytes()
    manifest = json.loads(list_bytes.decode("utf-8"))
    width = manifest["width"]
    if width not in WIDTHS:
        sys.exit(f"{width} is not a standard thumbnail width")
    out = OUT / name
    good, bad = out / "good", out / "bad"
    # until this run has checked every file, the list serves nothing (DocsPhotos needs the manifest)
    if (out / "manifest.json").exists():
        (out / "manifest.json").unlink()
    good.mkdir(parents=True, exist_ok=True)
    bad.mkdir(parents=True, exist_ok=True)
    photos = manifest["photos"]
    titles = [p["title"] for p in photos]
    if len(set(titles)) != len(titles):
        sys.exit(f"{name}: a title is listed twice")
    files = {p["title"]: good / key("photo", p["title"], width, p.get("crop_bottom")) for p in photos}

    # every title's licence, on every run: a cached file is served only if its licence still holds
    for start in range(0, len(photos), BATCH):
        batch = photos[start:start + BATCH]
        infos = imageinfo([p["title"] for p in batch], width)
        for p in batch:
            title = p["title"]
            info = infos.get(title) or sys.exit(f"{title}: not on Commons")
            check_licence(title, info, p.get("license"))
            if not files[title].exists():
                download(title, info, files[title], p.get("crop_bottom"))

    degraded = []
    for d in manifest["degraded"]:
        if d["base"] not in files:
            sys.exit(f"{name}: degraded copy of {d['base']}, which the list doesn't have")
        target = bad / key("degraded", d["base"], width, photos[titles.index(d["base"])].get("crop_bottom"), d["variant"], d["params"])
        if not target.exists():
            image = Image.open(files[d["base"]]).convert("RGB")
            changed = degrade(image, d["variant"], d["params"])
            write_atomically(target, lambda path: changed.save(path, "JPEG", quality=85))
        degraded.append({"base": d["base"], "variant": d["variant"], "file": f"bad/{target.name}"})

    # only this run's files stay: an entry removed from the list, or made differently, leaves none
    keep = set(files.values()) | {bad / Path(d["file"]).name for d in degraded}
    for directory in (good, bad):
        for path in directory.iterdir():
            if path not in keep:
                print(f"removing {path.relative_to(ROOT)} (not in the list)")
                path.unlink()

    listing = {
        "list": name,
        # the committed list this run checked: DocsPhotos serves nothing for another version of it
        "list_sha256": hashlib.sha256(list_bytes).hexdigest(),
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "width": width,
        "good": [{"title": t, "file": f"good/{files[t].name}"} for t in titles],
        "bad": degraded,
    }
    write_atomically(out / "manifest.json",
                     lambda path: path.write_text(json.dumps(listing, ensure_ascii=False, indent=1), encoding="utf-8"))
    print(f"{name}: {len(photos)} photos in {good}, {len(degraded)} degraded in {bad}")


def main():
    names = list_names(os.environ.get("DOCS_PHOTOS", "wlm-ua,wlm-intl"))
    if not names:
        sys.exit("DOCS_PHOTOS names no list (wlm-ua, wlm-intl, wle-ua, wle-intl)")
    for name in names:
        fetch_list(name)


if __name__ == "__main__":
    main()
