#!/usr/bin/env python3
"""Generate version-badged qualification icons. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    image = Image.open(ROOT / ('app/src/main/res/mipmap-' + density + '/ic_launcher.png')).convert('RGBA').resize((576, 576))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((12, 338, 564, 564), radius=28, fill='#123138', outline='#E7CB76', width=8)
    for text, y, font_size in [('0.5.11', 346, 96), ('S1', 449, 89)]:
        font = ImageFont.truetype(FONT, font_size)
        box = draw.textbbox((0, 0), text, font=font)
        draw.text(((576 - (box[2] - box[0])) / 2, y - box[1]), text, font=font, fill='#FFFFFF')
    dest = ROOT / ('app/src/phoneSmoke/res/mipmap-' + density + '/ic_launcher_smoke.png')
    dest.parent.mkdir(parents=True, exist_ok=True)
    image.resize((size, size), Image.Resampling.LANCZOS).save(dest)
