"""Erzeugt das Logo des Peregrine Client: ein fliegender Falke, schlicht schwarz-weiss (braucht Pillow).

    python generate.py

falcon.png: weisser Falke auf transparentem Grund. Der Launcher faerbt ihn passend ein
(dunkles Design: schwarzer Falke auf weissem Abzeichen, helles Design: umgekehrt).
icon.png: Fenster-/Taskleisten-Symbol (weisser Falke auf Schwarz).
"""
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).parent
SS = 8  # Supersampling fuer glatte Kanten

# rechte Haelfte (Kopf oben Mitte -> Fluegelspitze -> Schwanz), wird gespiegelt
RIGHT = [(50, 22), (52.5, 23), (54, 26.5), (54, 31), (56, 34), (63, 33), (72, 30), (82, 28.5), (92, 29), (100, 31),
         (91, 36), (82, 41), (72, 46), (62, 50), (56.5, 54), (55, 63), (55.5, 70), (58.5, 78), (53, 80.5), (50, 79.5)]


def shape():
    pts = RIGHT + [(100 - x, y) for x, y in reversed(RIGHT[1:-1])]
    ys = [y for _, y in pts]
    dy = 50 - (min(ys) + max(ys)) / 2
    return [(x, y + dy) for x, y in pts]


def render(size, badge=None, fg=(255, 255, 255, 255), pad=0.08):
    n = size * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if badge:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=badge)
    sc, off = n * (1 - 2 * pad) / 100, n * pad
    d.polygon([(off + x * sc, off + y * sc) for x, y in shape()], fill=fg)
    return im.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    render(512).save(HERE / "falcon.png", optimize=True)
    render(256, badge=(0, 0, 0, 255)).save(HERE / "icon.png", optimize=True)
