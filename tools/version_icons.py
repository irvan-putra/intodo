#!/usr/bin/env python3
"""Update the launcher's existing version badge. Requires Pillow and DejaVu Sans."""
from pathlib import Path
import re
from PIL import Image, ImageDraw, ImageFont
root=Path(__file__).resolve().parents[1]
version=re.search(r"versionName '([^']+)'",(root/'app/build.gradle').read_text()).group(1)
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    path=root/f'app/src/main/res/mipmap-{density}/ic_launcher.png'
    image=Image.open(path).convert('RGBA').resize((576,576))
    draw=ImageDraw.Draw(image)
    draw.rounded_rectangle((192,312,575,575),radius=78,fill='#F5A000')
    font_path='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
    size_font=79
    while True:
        font=ImageFont.truetype(font_path,size_font)
        box=draw.textbbox((0,0),version,font=font)
        if box[2]-box[0]<=320:break
        size_font-=1
    draw.text((384-(box[2]-box[0])/2,438-(box[3]-box[1])/2-box[1]),version,font=font,fill='#101828')
    image.resize((size,size),Image.Resampling.LANCZOS).save(path)
