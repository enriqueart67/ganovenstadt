"""Erzeugt Bildmarke (Falke im Sturzflug) und weichen Lichtschein fuer die Menues im Spiel.
Weiss auf transparent – das Spiel faerbt sie ein. Aufruf: python3 tools/make_marks.py"""
from PIL import Image, ImageDraw

OUT = "src/main/resources/assets/peregrine/textures/gui/"
SS = 4

# Falke: gleiche Form wie im Launcher (gui_stoop.rs::falcon)
size = 128
img = Image.new("RGBA", (size * SS, size * SS), (255, 255, 255, 0))
d = ImageDraw.Draw(img)
s = 56 * SS
c = size * SS / 2
v = lambda x, y: (c + x * s, c + y * s)
tip, w1, notch, w2 = v(-0.52, 0.56), v(0.58, -0.18), v(0.14, -0.08), v(0.22, -0.62)
d.polygon([tip, w1, notch], fill=(255, 255, 255, 255))
d.polygon([tip, notch, w2], fill=(255, 255, 255, 255))
img.resize((size, size), Image.LANCZOS).save(OUT + "falcon.png", optimize=True)

# Lichtschein: radialer Verlauf
n = 256
glow = Image.new("RGBA", (n, n), (255, 255, 255, 0))
px = glow.load()
for y in range(n):
    for x in range(n):
        dx, dy = (x - n / 2 + 0.5) / (n / 2), (y - n / 2 + 0.5) / (n / 2)
        r = min(1.0, (dx * dx + dy * dy) ** 0.5)
        px[x, y] = (255, 255, 255, int(255 * (1 - r) ** 2.2))
glow.save(OUT + "glow.png", optimize=True)
print("ok")
