"""Erzeugt das Fenster-Symbol des Peregrine Client: ein schlichtes weisses "P" auf Schwarz (braucht Pillow).

    python generate.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
SS = 4


def render(size, letter="P"):
    n = size * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=(0, 0, 0, 255))
    font = ImageFont.truetype(str(HERE.parent / "fonts" / "Poppins-Bold.ttf"), int(n * 0.62))
    l, t, r, b = d.textbbox((0, 0), letter, font=font)
    d.text(((n - (r - l)) / 2 - l, (n - (b - t)) / 2 - t), letter, font=font, fill=(255, 255, 255, 255))
    return im.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    render(256).save(HERE / "icon.png", optimize=True)
