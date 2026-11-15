"""Erzeugt die Hoehenlinien-Textur fuer die Menues im Spiel (Design „Flugkarte“).
Weisse Linien auf transparent; das Spiel faerbt sie passend zum Launcher-Theme ein.
Aufruf: python3 tools/make_chart.py"""
import math
import random
from PIL import Image, ImageDraw

SIZE, SS = 1024, 2  # Ausgabegroesse, Supersampling fuer glatte Linien
OUT = "src/main/resources/assets/peregrine/textures/gui/chart.png"

random.seed(7)
perm = [random.random() for _ in range(4096)]


def h(i, j, seed):
    return perm[(i * 73856093 ^ j * 19349663 ^ seed * 83492791) & 4095]


def vnoise(x, y, seed):
    xi, yi = math.floor(x), math.floor(y)
    xf, yf = x - xi, y - yi
    s = lambda t: t * t * (3 - 2 * t)
    u, v = s(xf), s(yf)
    a = h(xi, yi, seed) + (h(xi + 1, yi, seed) - h(xi, yi, seed)) * u
    b = h(xi, yi + 1, seed) + (h(xi + 1, yi + 1, seed) - h(xi, yi + 1, seed)) * u
    return a + (b - a) * v


def terrain(x, y):
    v, amp, f = 0.0, 0.55, 1.0
    for o in range(4):
        v += vnoise(x * f, y * f, 11 + o) * amp
        amp *= 0.5
        f *= 2.03
    return v


cell = 6
n = SIZE // cell + 2
scale = 1 / 240
grid = [[terrain(i * cell * scale + 3.1, j * cell * scale + 7.7) for i in range(n)] for j in range(n)]
img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (255, 255, 255, 0))
d = ImageDraw.Draw(img)
levels = 16
for l in range(1, levels):
    iso = l / levels * 0.95 + 0.05
    index = l % 4 == 0
    width = (3 if index else 2) * SS // 2 + (1 if index else 0)
    alpha = 255 if index else 150
    for j in range(n - 1):
        for i in range(n - 1):
            v = [grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i]]
            code = sum(1 << k for k in range(4) if v[k] > iso)
            if code in (0, 15):
                continue
            x0, y0 = i * cell, j * cell
            t = lambda a, b: min(1, max(0, (iso - a) / (b - a))) if b != a else 0.5
            top = (x0 + cell * t(v[0], v[1]), y0)
            right = (x0 + cell, y0 + cell * t(v[1], v[2]))
            bottom = (x0 + cell * (1 - t(v[2], v[3])), y0 + cell)
            left = (x0, y0 + cell * (1 - t(v[3], v[0])))
            segs = {1: [(left, top)], 14: [(left, top)], 2: [(top, right)], 13: [(top, right)], 3: [(left, right)], 12: [(left, right)],
                    4: [(right, bottom)], 11: [(right, bottom)], 5: [(left, top), (right, bottom)], 6: [(top, bottom)], 9: [(top, bottom)],
                    7: [(left, bottom)], 8: [(left, bottom)], 10: [(top, right), (left, bottom)]}[code]
            for a, b in segs:
                d.line([(a[0] * SS, a[1] * SS), (b[0] * SS, b[1] * SS)], fill=(255, 255, 255, alpha), width=width)
img = img.resize((SIZE, SIZE), Image.LANCZOS)
img.save(OUT, optimize=True)
print("wrote", OUT)
