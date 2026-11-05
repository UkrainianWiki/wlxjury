#!/usr/bin/env python3
"""Shrinks the user guides' screenshots (docs/user/images/*.png) after docs-screenshots.spec.js:
photos make 2x PNGs large, so each image is reduced to a 256-colour palette (dithered when it
holds photos, plain when it is a flat page) and recompressed. The pixel size is unchanged. Needs
Pillow.

  python browser-test/docs-optimize-png.py
"""
from pathlib import Path

from PIL import Image

IMAGES = Path(__file__).resolve().parent.parent / "docs" / "user" / "images"

PHOTO_BYTES = 700 * 1024  # a raw screenshot above this holds photos

total = 0
for png in sorted(IMAGES.glob("*.png")):
    before = png.stat().st_size
    image = Image.open(png)
    if image.mode != "P":  # an image already reduced stays as it is
        image = image.convert("RGB")
        if len(image.getcolors(1 << 20) or []) > 256:
            # photos (large files) are dithered so that skies show no bands; flat pages are not
            # (Pillow dithers only against a given palette: the first pass makes it)
            palette = image.quantize(colors=256, method=Image.FASTOCTREE, dither=Image.NONE)
            image = image.quantize(palette=palette, dither=Image.FLOYDSTEINBERG) if before > PHOTO_BYTES else palette
    image.save(png, optimize=True)
    total += png.stat().st_size
    print(f"{png.name}: {before // 1024} KB -> {png.stat().st_size // 1024} KB")
print(f"{len(list(IMAGES.glob('*.png')))} images, {total // 1024} KB in all")
