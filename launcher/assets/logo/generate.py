"""Erzeugt das Falken-Logo des Peregrine Client (braucht Pillow).

    python generate.py

falcon.png / falcon-64.png: nur der Falke (transparent) – der Launcher legt ihn
auf ein Abzeichen in deiner Akzentfarbe. icon.png: Fenster-Symbol mit blauem Hintergrund.
"""
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).parent
SS = 8  # Supersampling fuer glatte Kanten

HEAD = [(10, 96), (12, 80), (15, 64), (20, 50), (27, 39), (36, 30), (46, 24), (57, 20), (67, 19), (75, 21), (80, 25),
        (86, 28), (91, 33), (94, 39), (95, 46), (93, 52), (91, 47), (87, 45), (83, 47), (80, 51), (75, 58), (68, 66),
        (62, 75), (58, 86), (57, 96)]
# dunkle Kappe, Nacken/Ruecken und der typische "Bart"-Streifen unter dem Auge
HOOD = [(10, 96), (12, 80), (15, 64), (20, 50), (27, 39), (36, 30), (46, 24), (57, 20), (67, 19), (75, 21), (80, 25),
        (80, 30), (74, 30), (66, 33), (62, 40), (62, 50), (64, 60), (62, 70), (57, 73), (53, 66), (50, 56), (44, 53),
        (37, 57), (31, 66), (27, 80), (25, 96)]
BEAK = [(80, 25), (86, 28), (91, 33), (94, 39), (95, 46), (93, 52), (91, 47), (87, 45), (83, 46), (80, 41)]
CERE = [(78, 27), (82, 27), (83, 42), (79, 40)]
EYE = (70, 32)

WHITE = (255, 255, 255, 255)
DARK = (14, 22, 44, 255)
BEAK_C = (52, 62, 86, 255)
GOLD = (255, 196, 64, 255)


def render(size, badge=None, pad=0.12):
    n = size * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if badge:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=badge + (255,))
    sc, off = n * (1 - 2 * pad) / 100, n * pad
    P = lambda pts: [(off + x * sc, off + y * sc) for x, y in pts]
    d.polygon(P(HEAD), fill=WHITE)
    d.polygon(P(HOOD), fill=DARK)
    d.polygon(P(BEAK), fill=BEAK_C)
    d.polygon(P(CERE), fill=GOLD)
    cx, cy = off + EYE[0] * sc, off + EYE[1] * sc
    for r, c in [(6.0, GOLD), (3.7, DARK)]:
        d.ellipse([cx - r * sc, cy - r * sc, cx + r * sc, cy + r * sc], fill=c)
    d.ellipse([cx + 0.5 * sc, cy - 2.5 * sc, cx + 2.0 * sc, cy - 1.0 * sc], fill=WHITE)
    return im.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    render(512).save(HERE / "falcon.png", optimize=True)
    render(64).save(HERE / "falcon-64.png", optimize=True)
    render(256, badge=(79, 140, 255)).save(HERE / "icon.png", optimize=True)
