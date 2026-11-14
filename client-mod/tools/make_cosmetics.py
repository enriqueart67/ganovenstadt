"""Texture atlases for the 3D cosmetics. Run: python3 make_cosmetics.py <out dir>

Every model is built from boxes. Each box samples a rectangle of its atlas (texOffs u,v and size
(2d+2w) x (d+h)), so the atlases are laid out as horizontal *material bands* that are large enough
for any box pointing into them – bone, membrane, feather, gold, felt …
"""
import math, random, sys
from PIL import Image

OUT = sys.argv[1]
random.seed(5)


def clamp(c):
    return tuple(max(0, min(255, int(round(x)))) for x in c)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def band(img, v0, v1, fn):
    px = img.load()
    for y in range(v0, v1):
        for x in range(img.width):
            c = fn(x, y - v0, v1 - v0)
            if c is not None:
                px[x, y] = clamp(c[:3]) + ((c[3],) if len(c) == 4 else (255,))


def noise(c, amount=8):
    n = random.uniform(-amount, amount)
    return tuple(ch + n for ch in c)


# ------------------------------------------------------------------ membrane wings (dragon, demon)
def membrane_wings(name, bone, bone_hi, mem_top, mem_bottom, vein):
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    # 0-15 bone: rounded look, light top row, darker bottom
    band(img, 0, 16, lambda x, y, h: noise(mix(bone_hi, bone, y / (h - 1)), 6))
    # 16-63 membrane: vertical gradient, faint veins, scalloped bottom edge (alpha) on the last rows
    def mem(x, y, h):
        bottom = h - 1 - (1 + math.sin(x * 0.9) * 1.5 + 1.5)  # wavy trailing edge
        if y > bottom:
            return None
        c = mix(mem_top, mem_bottom, y / h)
        if x % 7 == 0 or (x + y) % 11 == 0 and y > 4:
            c = mix(c, vein, 0.35)
        if y > bottom - 1.2:
            c = mix(c, bone, 0.55)  # darker rim
        return noise(c, 5)
    band(img, 16, 64, mem)
    # 64-79 claws / spikes
    band(img, 64, 80, lambda x, y, h: mix((235, 230, 220), (150, 140, 128), y / h))
    img.save(f"{OUT}/wings_{name}.png")


membrane_wings("dragon", (34, 32, 44), (78, 74, 96), (70, 96, 150), (26, 34, 64), (120, 150, 210))
membrane_wings("demon", (16, 12, 14), (60, 40, 44), (168, 26, 36), (60, 6, 14), (230, 70, 60))


# ------------------------------------------------------------------ feathered wings (angel)
def angel():
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    band(img, 0, 16, lambda x, y, h: noise(mix((250, 250, 255), (214, 220, 234), y / h), 3))
    # 16-63 long flight feathers (they lie along x): white at the base, grey towards the tip,
    # a darker shaft along the middle row and a rounded tip (alpha) at the far end
    def feather(x, y, h):
        c = mix((255, 255, 255), (178, 188, 210), (x % 32) / 31)
        if y % 3 == 1:
            c = mix(c, (190, 198, 218), 0.35)
        return noise(c, 3)
    band(img, 16, 64, feather)
    band(img, 64, 96, lambda x, y, h: noise(mix((255, 253, 248), (222, 226, 238), (x % 24) / 23), 3))
    img.save(f"{OUT}/wings_angel.png")


angel()


# ------------------------------------------------------------------ butterfly (shaped lobes)
def butterfly():
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    px = img.load()

    def lobe(u, v, w, h, cx, cy, rx, ry, inner, outer):
        # front face at (u+1, v+1) and back face at (u+1+w+1, v+1), depth 1
        for face_x in (u + 1, u + 2 + w):
            for y in range(h):
                for x in range(w):
                    xx = x if face_x == u + 1 else w - 1 - x  # mirror the back so both sides line up
                    e = ((xx - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
                    if e > 1:
                        continue
                    if e > 0.72:
                        c = (14, 14, 22) if (xx + y) % 4 else (240, 240, 248)
                    elif (xx % 4 == 0 and e > 0.15) or (abs(y - cy) < 0.6 and e > 0.1):
                        c = (22, 26, 48)
                    else:
                        c = mix(inner, outer, e / 0.72)
                    px[face_x + x, v + 1 + y] = clamp(noise(c, 4)) + (255,)
        # rims (top/bottom/side faces) dark
        for x in range(2 * w + 2):
            for y in range(1):
                px[u + x, v + y] = (14, 14, 22, 255)
        for y in range(h):
            px[u, v + 1 + y] = (14, 14, 22, 255)
            px[u + 1 + w, v + 1 + y] = (14, 14, 22, 255)
    lobe(0, 0, 13, 11, 7.5, 5.0, 6.6, 5.6, (60, 220, 255), (24, 70, 220))
    lobe(0, 16, 10, 9, 4.0, 3.5, 5.6, 4.6, (255, 170, 60), (220, 60, 120))
    # body / antenna material
    band(img, 40, 48, lambda x, y, h: (24, 22, 30))
    img.save(f"{OUT}/wings_butterfly.png")


butterfly()


# ------------------------------------------------------------------ headwear atlases
def tophat():
    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    band(img, 0, 32, lambda x, y, h: noise(mix((30, 30, 34), (16, 16, 18), y / h), 4))       # felt
    band(img, 32, 40, lambda x, y, h: noise((178, 30, 34) if y > 0 else (220, 70, 64), 4))      # ribbon
    band(img, 40, 48, lambda x, y, h: (196, 160, 70) if (x + y) % 3 else (240, 210, 120))      # buckle
    img.save(f"{OUT}/hat_tophat.png")


def crown():
    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    band(img, 0, 24, lambda x, y, h: noise(mix((255, 222, 110), (186, 128, 26), y / h), 6))    # gold
    band(img, 24, 32, lambda x, y, h: noise((150, 22, 40), 6))                                  # velvet
    band(img, 32, 36, lambda x, y, h: mix((255, 90, 120), (190, 10, 50), y / h))               # ruby
    band(img, 36, 40, lambda x, y, h: mix((110, 180, 255), (20, 70, 200), y / h))              # sapphire
    band(img, 40, 44, lambda x, y, h: mix((150, 255, 190), (20, 160, 80), y / h))              # emerald
    band(img, 44, 48, lambda x, y, h: (255, 248, 236))                                          # pearl
    img.save(f"{OUT}/hat_crown.png")


def halo():
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    band(img, 0, 16, lambda x, y, h: mix((255, 246, 190), (255, 196, 60), y / h))
    img.save(f"{OUT}/hat_halo.png")


def horns():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    # ridged horn: rings every 2 px, darkening towards the tip band
    def horn(x, y, h, tip):
        c = mix((150, 30, 34), (28, 8, 10), tip)
        if y % 2 == 0:
            c = mix(c, (0, 0, 0), 0.25)
        return noise(c, 5)
    for i in range(5):
        band(img, i * 8, i * 8 + 8, lambda x, y, h, i=i: horn(x, y, h, i / 4))
    img.save(f"{OUT}/hat_horns.png")


def catears():
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    band(img, 0, 8, lambda x, y, h: noise(mix((70, 58, 52), (40, 32, 30), y / h), 6))   # fur
    band(img, 8, 16, lambda x, y, h: noise((246, 160, 180), 6))                          # inner ear
    band(img, 16, 24, lambda x, y, h: noise((240, 236, 230), 4))                         # tuft
    img.save(f"{OUT}/hat_catears.png")


tophat(); crown(); halo(); horns(); catears()
print("ok")
