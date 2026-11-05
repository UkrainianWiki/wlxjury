#!/usr/bin/env python3
"""Shrinks the user guides' screenshots (docs/user/images/*.png) after docs-screenshots.spec.js:
photos make 2x PNGs large, so each image is reduced to a 256-colour palette (no dithering, which
keeps the files small) and recompressed. The pixel size is unchanged. Needs Pillow.

  python browser-test/docs-optimize-png.py
"""
from pathlib import Path

from PIL import Image

IMAGES = Path(__file__).resolve().parent.parent / "docs" / "user" / "images"

total = 0
for png in sorted(IMAGES.glob("*.png")):
    before = png.stat().st_size
    image = Image.open(png).convert("RGB")
    if image.mode == "RGB" and len(image.getcolors(1 << 20) or []) > 256:
        image = image.quantize(colors=256, method=Image.FASTOCTREE, dither=Image.NONE)
    image.save(png, optimize=True)
    total += png.stat().st_size
    print(f"{png.name}: {before // 1024} KB -> {png.stat().st_size // 1024} KB")
print(f"{len(list(IMAGES.glob('*.png')))} images, {total // 1024} KB in all")
