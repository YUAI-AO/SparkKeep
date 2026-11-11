# -*- coding: utf-8 -*-
"""生成 Android 各密度启动图标（沿用桌面版 spark.png 的近黑底白火苗）。"""
import os
from PIL import Image

SRC = r"C:\Users\17098\Doubao\chats\2026-11-08\new-chat\spark-src\spark.png"
ROOT = r"C:\Users\17098\Doubao\chats\2026-11-08\new-chat\android\res"
SIZES = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}

im = Image.open(SRC).convert("RGBA")
# 若源图是方形，直接缩放；留 6% 内边距，避免被系统圆形裁剪切掉火苗
for folder, px in SIZES.items():
    d = os.path.join(ROOT, folder)
    os.makedirs(d, exist_ok=True)
    pad = int(px * 0.06)
    inner = px - pad * 2
    sm = im.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (px, px), (5, 6, 7, 255))
    canvas.paste(sm, (pad, pad), sm)
    canvas.save(os.path.join(d, "ic_launcher.png"), "PNG")
    print(folder, px, "ok")
print("done")
