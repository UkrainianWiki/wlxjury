#!/usr/bin/env python3
"""Fetches the photos for the user guides' screenshots (docs/user/images/README.md).

Reads browser-test/docs-photos.json (public-domain and CC0 photos from Wikimedia Commons) and
writes to the git-ignored target/docs-photos/:

  good/NN.jpg   the photos, 1280 px wide, downloaded once from upload.wikimedia.org
  bad/NN.jpg    degraded copies (blur, tilted horizon, over- or underexposed, low resolution with
                JPEG artefacts) of some of them, for the yes/no examples

Anonymous on Wikimedia (no credentials), with a descriptive User-Agent; the licence of every file
is checked again against the Commons API (extmetadata) and anything but CC0 or public domain
stops the run. A second run downloads nothing that is cached. Needs Pillow (pip install pillow).

  python browser-test/docs-photos.py
"""
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "browser-test" / "docs-photos.json"
OUT = ROOT / "target" / "docs-photos"
API = "https://commons.wikimedia.org/w/api.php"
UA = "wlxjury-docs-photos/1.0 (https://github.com/intracer/wlxjury; user guide screenshots; anonymous) python-urllib"
WIDTHS = {20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840}


def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return r.read()
        except Exception as error:  # 429 and network blips: wait and retry
            if attempt == 3:
                raise
            print(f"  retry after {error}")
            time.sleep(5 * (attempt + 1))


def imageinfo(titles, width):
    query = urllib.parse.urlencode({
        "action": "query", "format": "json", "prop": "imageinfo", "titles": "|".join(titles),
        "iiprop": "extmetadata|url", "iiurlwidth": width})
    pages = json.loads(get(f"{API}?{query}"))["query"]["pages"].values()
    return {p["title"]: p["imageinfo"][0] for p in pages if "imageinfo" in p}


def check_licence(title, info):
    meta = info["extmetadata"]
    short = meta.get("LicenseShortName", {}).get("value", "")
    if not (short.upper().startswith("CC0") or "public domain" in short.lower()):
        sys.exit(f"{title}: licence '{short}' is neither CC0 nor public domain")


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


def main():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    width = manifest["width"]
    if width not in WIDTHS:
        sys.exit(f"{width} is not a standard thumbnail width")
    good, bad = OUT / "good", OUT / "bad"
    good.mkdir(parents=True, exist_ok=True)
    bad.mkdir(parents=True, exist_ok=True)
    photos = manifest["photos"]
    files = {p["title"]: good / f"{i:02d}.jpg" for i, p in enumerate(photos)}
    missing = [t for t, f in files.items() if not f.exists()]
    for start in range(0, len(missing), 40):
        batch = missing[start:start + 40]
        infos = imageinfo(batch, width)
        for title in batch:
            info = infos.get(title) or sys.exit(f"{title}: not on Commons")
            check_licence(title, info)
            url = info.get("thumburl") or info["url"]
            # the API names thumbnails on thumb.wikimedia.org, with tracking parameters
            url = url.replace("https://thumb.wikimedia.org/", "https://upload.wikimedia.org/").split("?")[0]
            if not url.startswith("https://upload.wikimedia.org/"):
                sys.exit(f"{title}: unexpected host {url}")
            print(f"fetching {title}")
            data = get(url)
            Image.open(io.BytesIO(data)).verify()
            files[title].write_bytes(data)
            time.sleep(1)
    for i, d in enumerate(manifest["degraded"]):
        target = bad / f"{i:02d}.jpg"
        image = Image.open(files[d["base"]]).convert("RGB")
        degrade(image, d["variant"], d["params"]).save(target, "JPEG", quality=85)
    print(f"{len(photos)} photos in {good}, {len(manifest['degraded'])} degraded in {bad}")


if __name__ == "__main__":
    main()
