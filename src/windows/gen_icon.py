"""生成程序图标：切角黑底 + 荧光黄绿的环形火花符。"""
import os
from PIL import Image, ImageDraw

SIZE = 1024
BG = (10, 11, 12, 255)
ACC = (217, 242, 76, 255)
LINE = (255, 255, 255, 38)

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

cut = int(SIZE * 0.14)
r = int(SIZE * 0.30)

# 切角方形底
d.polygon([(cut, 0), (SIZE - cut, 0), (SIZE, cut), (SIZE, SIZE - cut),
           (SIZE - cut, SIZE), (cut, SIZE), (0, SIZE - cut), (0, cut)], fill=BG)

# 内框细线
m = int(SIZE * 0.055)
d.polygon([(m + cut / 2, m), (SIZE - m - cut / 2, m), (SIZE - m, m + cut / 2),
           (SIZE - m, SIZE - m - cut / 2), (SIZE - m - cut / 2, SIZE - m),
           (m + cut / 2, SIZE - m), (m, SIZE - m - cut / 2), (m, m + cut / 2)],
          outline=LINE, width=max(2, int(SIZE * 0.006)))

# 中心环形火花：菱形外框 + 内部篓空
cx, cy = SIZE / 2, SIZE / 2 - SIZE * 0.03
outer = [(cx, cy - r * 1.45), (cx + r * 1.05, cy), (cx, cy + r * 1.45), (cx - r * 1.05, cy)]
inner = [(cx, cy - r * 0.60), (cx + r * 0.43, cy), (cx, cy + r * 0.60), (cx - r * 0.43, cy)]
d.polygon(outer, fill=ACC)
d.polygon(inner, fill=BG)

out_dir = os.path.dirname(os.path.abspath(__file__))
png = os.path.join(out_dir, "spark.png")
ico = os.path.join(out_dir, "spark.ico")
img.save(png)
img.save(ico, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
print("ICON_OK", ico, os.path.getsize(ico))
