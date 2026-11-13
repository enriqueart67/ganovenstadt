"""Erzeugt die Umhang-Texturen (64x32, Minecraft-Umhang-Layout inkl. Elytra) des Peregrine Client."""
import math, random, sys
from PIL import Image

OUT = sys.argv[1]

def cape(name, painter):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    for y in range(32):
        for x in range(64):
            # Umhang: x 0..22, y 0..17 ; Elytra: x 22..46, y 0..22 – alles bemalen
            if (x < 22 and y < 17) or (22 <= x < 46 and y < 22):
                # Koordinaten relativ zur Rückseite des Umhangs (10x16 bei x=1..11, y=1..17)
                u, v = (x - 1) / 10.0, (y - 1) / 16.0
                px[x, y] = painter(x, y, u, v) + (255,)
    img.save(f"{OUT}/{name}.png")

def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

ORANGE, INK, PAPER = (255, 79, 0), (17, 17, 16), (243, 241, 236)

def falke(x, y, u, v):
    # Orange mit dunklem Flügel-Winkel (Falke) und Rand
    if x in (0, 1, 10, 11, 12, 21) or y in (0, 1, 16):
        return INK
    du = abs(u - 0.5)
    if abs(v - (0.35 + du * 0.9)) < 0.08:
        return INK
    if abs(v - (0.55 + du * 0.9)) < 0.05:
        return (40, 20, 10)
    return lerp(ORANGE, (220, 60, 0), v)

random.seed(7)
STARS = {(random.randrange(64), random.randrange(32)) for _ in range(70)}

def nacht(x, y, u, v):
    if (x, y) in STARS:
        return (255, 255, 255) if (x + y) % 3 else (255, 200, 120)
    return lerp((10, 14, 40), (30, 40, 90), v)

def papier(x, y, u, v):
    if x in (0, 1, 10, 11, 12, 21) or y in (0, 1, 16):
        return INK
    if 0.42 < v < 0.52:
        return ORANGE
    return PAPER

def flamme(x, y, u, v):
    n = math.sin(x * 1.7) * 0.08 + math.sin(x * 0.6 + 1) * 0.06
    t = v + n
    if t < 0.35:
        return lerp((255, 230, 120), (255, 160, 20), t / 0.35)
    if t < 0.75:
        return lerp((255, 160, 20), (230, 40, 0), (t - 0.35) / 0.4)
    return lerp((230, 40, 0), (90, 10, 0), (t - 0.75) / 0.25)

def ozean(x, y, u, v):
    w = math.sin(x * 0.9 + y * 0.5) * 0.5 + 0.5
    base = lerp((10, 120, 200), (5, 30, 90), v)
    return lerp(base, (90, 200, 255), w * 0.35 if (y % 4) == 0 else w * 0.12)

def galaxie(x, y, u, v):
    if (x, y) in STARS:
        return (255, 255, 255)
    n = math.sin(x * 0.5) * math.cos(y * 0.7) * 0.5 + 0.5
    return lerp(lerp((25, 5, 45), (90, 20, 120), n), (220, 60, 160), max(0, n - 0.7) * 2)

def regenbogen(x, y, u, v):
    cols = [(230, 40, 40), (255, 140, 0), (255, 220, 0), (40, 180, 60), (30, 120, 230), (130, 60, 200)]
    return cols[min(5, max(0, int(v * 6)))]

def kristall(x, y, u, v):
    d = (x + y) % 6 == 0 or (x - y) % 6 == 0
    return (220, 255, 255) if d else lerp((40, 200, 210), (20, 90, 140), v)

for n, f in [("falke", falke), ("nacht", nacht), ("papier", papier), ("flamme", flamme),
             ("ozean", ozean), ("galaxie", galaxie), ("regenbogen", regenbogen), ("kristall", kristall)]:
    cape(n, f)
print("ok")
