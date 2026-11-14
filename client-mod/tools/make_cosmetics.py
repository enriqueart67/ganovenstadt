"""Textures for the 3D cosmetics (wings and headwear). Run: python3 make_cosmetics.py <out dir>

Box UV layout (Minecraft): a box w×h×d at texOffs(u, v) uses
  top    (u+d,       v)      w×d     bottom (u+d+w,     v)   w×d
  east   (u,         v+d)    d×h     north  (u+d,       v+d) w×h
  west   (u+d+w,     v+d)    d×h     south  (u+d+w+d,   v+d) w×h
"""
import math, random, sys
from PIL import Image

OUT = sys.argv[1]
random.seed(11)


def clamp(c):
    return tuple(max(0, min(255, int(round(x)))) for x in c)


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def paint_box(img, u, v, w, h, d, fn):
    """fn(face, x, y, fw, fh) -> (r,g,b) or None (transparent)"""
    px = img.load()
    faces = {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "east": (u, v + d, d, h), "north": (u + d, v + d, w, h),
        "west": (u + d + w, v + d, d, h), "south": (u + d + w + d, v + d, w, h),
    }
    for face, (fx, fy, fw, fh) in faces.items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is not None:
                    px[fx + x, fy + y] = clamp(c) + (255,)


# ------------------------------------------------------------------ wings (plane 24×18, depth 0)
W, H = 24, 18


def wing_shape(x, y, kind):
    """Membrane mask in wing space: x 0 (spine) → 23 (tip), y 0 (top) → 17."""
    u = x / (W - 1)
    if kind in ("dragon", "demon"):
        top = 1 + (1 - math.sin(u * math.pi * 0.95)) * 3  # arched leading edge
        # scalloped trailing edge between 4 finger bones
        ribs = 4
        seg = (u * ribs) % 1.0
        bottom = 17 - u * 7 - math.sin(seg * math.pi) * 4.5
        return top <= y <= bottom
    if kind == "angel":
        top = 1 + (1 - u) * 2 + u * u * 1.5
        bottom = 17 - u * 9 + (math.sin(u * 30) * 0.8)
        return top <= y <= bottom
    # butterfly: two rounded lobes
    up = ((u - 0.55) / 0.48) ** 2 + ((y - 5.5) / 5.6) ** 2 <= 1
    lo = ((u - 0.35) / 0.34) ** 2 + ((y - 12.5) / 4.8) ** 2 <= 1
    return up or lo


def wing_color(x, y, kind):
    u = x / (W - 1)
    if kind in ("dragon", "demon"):
        bone = (14, 12, 16) if kind == "demon" else (40, 34, 44)
        membrane_a = (150, 22, 30) if kind == "demon" else (58, 74, 110)
        membrane_b = (70, 6, 12) if kind == "demon" else (24, 30, 52)
        # leading-edge bone and 4 finger bones fanning out from the root
        top = 1 + (1 - math.sin(u * math.pi * 0.95)) * 3
        if y <= top + 1.2:
            return bone
        for i in range(1, 5):
            ang = 0.15 + i * 0.32  # radians from the leading edge
            yy = 2 + x * math.tan(ang) * 0.42
            fx = (x / (i * 6.0 / 4 + 0.001))
            if abs(y - (2 + u * (2.5 + i * 3.3))) < 0.75 and u > 0.05:
                return bone
        t = (y / H) * 0.7 + u * 0.3
        c = mix(membrane_a, membrane_b, t)
        if random.random() < 0.08:
            c = mix(c, (0, 0, 0), 0.15)
        return c
    if kind == "angel":
        # rows of feathers: lighter at the top, soft grey edges
        row = int((y - 1) / 3.2)
        feather_x = (x + row * 2) % 4
        base = mix((252, 252, 255), (205, 212, 228), min(1, row / 4))
        if feather_x == 0:
            base = mix(base, (170, 178, 196), 0.6)
        if (y - 1) % 3.2 < 0.8:
            base = mix(base, (190, 196, 214), 0.5)
        return base
    # butterfly: deep blue to cyan with black veins and white dots
    up = ((u - 0.55) / 0.48) ** 2 + ((y - 5.5) / 5.6) ** 2
    lo = ((u - 0.35) / 0.34) ** 2 + ((y - 12.5) / 4.8) ** 2
    edge = min(up, lo)
    if edge > 0.72:
        if (x + y) % 5 == 0:
            return (240, 240, 245)
        return (12, 12, 18)
    if (x % 6 == 0 and y < 9) or (y == 9 and x > 3):
        return (20, 22, 40)
    return mix((40, 210, 255), (30, 60, 220), edge)


def make_wing(kind):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    for y in range(H):
        for x in range(W):
            if wing_shape(x, y, kind):
                c = clamp(wing_color(x, y, kind)) + (255,)
                px[x, y] = c          # north face (seen from behind the player… mirrored model handles sides)
                px[W + (W - 1 - x), y] = c  # south face, mirrored so both sides line up
    img.save(f"{OUT}/wings_{kind}.png")


for k in ("dragon", "angel", "demon", "butterfly"):
    make_wing(k)

# ------------------------------------------------------------------ headwear
BLACK, BLACK_HI = (22, 22, 24), (52, 52, 58)


def tophat():
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    paint_box(img, 0, 0, 11, 1, 11, lambda f, x, y, w, h: mix(BLACK, BLACK_HI, 0.5 if f == "top" and (x in (0, w - 1) or y in (0, h - 1)) else 0.0))

    def crown(f, x, y, w, h):
        if f in ("north", "south", "east", "west") and y >= h - 2:
            return (200, 40, 30) if y == h - 2 else (150, 26, 20)  # band
        c = BLACK
        if f in ("north", "south", "east", "west") and x == 0:
            c = BLACK_HI
        if f == "top":
            c = mix(BLACK, BLACK_HI, 0.35)
        return c

    paint_box(img, 0, 12, 8, 7, 8, crown)
    img.save(f"{OUT}/hat_tophat.png")


def crown():
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    gold, gold_dark, gold_hi = (236, 186, 52), (176, 122, 24), (255, 232, 140)

    def band(f, x, y, w, h):
        c = mix(gold, gold_dark, y / max(1, h - 1) * 0.6)
        if y == 0:
            c = gold_hi
        if f in ("north", "south") and w > 4 and x == w // 2:
            return (220, 30, 60)  # ruby
        if f in ("east", "west") and x == w // 2 and w > 2:
            return (40, 110, 230)  # sapphire
        return c

    paint_box(img, 0, 0, 9, 2, 1, band)     # front/back strips
    paint_box(img, 0, 4, 1, 2, 7, band)     # side strips
    paint_box(img, 0, 10, 1, 2, 1, lambda f, x, y, w, h: gold_hi if y == 0 else gold)  # spikes
    img.save(f"{OUT}/hat_crown.png")


def halo():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    paint_box(img, 0, 0, 3, 1, 1, lambda f, x, y, w, h: (255, 226, 120) if f in ("top", "bottom") else (255, 200, 70))
    img.save(f"{OUT}/hat_halo.png")


def horns():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    base, tip = (120, 18, 22), (30, 8, 10)
    paint_box(img, 0, 0, 2, 3, 2, lambda f, x, y, w, h: mix(base, tip, 0.1 + y / 10))
    paint_box(img, 0, 6, 2, 3, 2, lambda f, x, y, w, h: mix(base, tip, 0.4 + y / 10))
    paint_box(img, 0, 12, 1, 3, 1, lambda f, x, y, w, h: mix(base, tip, 0.75 + y / 12))
    img.save(f"{OUT}/hat_horns.png")


def catears():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    fur, fur_dark, pink = (54, 44, 40), (34, 28, 26), (240, 150, 170)
    paint_box(img, 0, 0, 3, 3, 1, lambda f, x, y, w, h: pink if f == "north" and 0 < x < w - 1 and y > 0 else mix(fur, fur_dark, y / 4))
    paint_box(img, 0, 6, 1, 2, 1, lambda f, x, y, w, h: fur_dark)
    img.save(f"{OUT}/hat_catears.png")


tophat(); crown(); halo(); horns(); catears()
print("ok")
