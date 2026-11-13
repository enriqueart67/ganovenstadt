package de.peregrine.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Zeichnet Info-Anzeige (FPS, CPS, Koordinaten …), Keystrokes und das eigene Fadenkreuz. */
public final class Hud {
    private static final int PAD = 4;
    private static final int MARGIN = 4;

    private record Line(String label, String value) {}

    // ------------------------------------------------------------------ Info-Anzeige
    public static void render(GuiGraphicsExtractor g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        Config c = Config.get();
        List<Line> lines = new ArrayList<>();
        if (c.fps) lines.add(new Line("FPS", String.valueOf(mc.getFps())));
        if (c.cps) lines.add(new Line("CPS", Clicks.left() + " | " + Clicks.right()));
        if (c.coords) lines.add(new Line("XYZ", String.format("%.0f  %.0f  %.0f", p.getX(), p.getY(), p.getZ())));
        if (c.direction) lines.add(new Line("Richtung", direction(p)));
        if (c.clock) {
            LocalTime t = LocalTime.now();
            lines.add(new Line("Uhrzeit", String.format("%02d:%02d", t.getHour(), t.getMinute())));
        }
        if (c.fullbright) lines.add(new Line("Fullbright", "an"));
        if (c.autoSprint) lines.add(new Line("Sprint", "automatisch"));
        if (!lines.isEmpty()) drawBox(g, mc.font, lines, c);
        if (c.keystrokes) Keystrokes.render(g, mc, c);
    }

    private static String direction(LocalPlayer p) {
        return switch (p.getDirection()) {
            case NORTH -> "Norden (-Z)";
            case SOUTH -> "Süden (+Z)";
            case EAST -> "Osten (+X)";
            case WEST -> "Westen (-X)";
            default -> p.getDirection().toString();
        };
    }

    private static void drawBox(GuiGraphicsExtractor g, Font font, List<Line> lines, Config c) {
        int accent = Config.color(c.accentColor, 0xFF4F8CFF);
        int text = Config.color(c.textColor, 0xFFFFFFFF);
        int lh = font.lineHeight + 2;
        int w = 0;
        for (Line l : lines) w = Math.max(w, font.width(l.label() + ": ") + font.width(l.value()));
        int boxW = w + PAD * 2, boxH = lines.size() * lh + PAD * 2 - 2;
        int[] pos = corner(g, c.hudPosition, boxW, boxH);
        int x = pos[0], y = pos[1];
        if (c.background) g.fill(x, y, x + boxW, y + boxH, 0x90000000);
        int ty = y + PAD;
        for (Line l : lines) {
            String lab = l.label() + ": ";
            g.text(font, lab, x + PAD, ty, accent, c.shadow);
            g.text(font, l.value(), x + PAD + font.width(lab), ty, text, c.shadow);
            ty += lh;
        }
    }

    /** Linke obere Ecke fuer ein Rechteck der Groesse w×h in der gewaehlten Bildschirmecke. */
    static int[] corner(GuiGraphicsExtractor g, String where, int w, int h) {
        int sw = g.guiWidth(), sh = g.guiHeight();
        return switch (where) {
            case "top_right" -> new int[]{sw - w - MARGIN, MARGIN};
            case "bottom_left" -> new int[]{MARGIN, sh - h - MARGIN - 50};
            case "bottom_right" -> new int[]{sw - w - MARGIN, sh - h - MARGIN - 50};
            default -> new int[]{MARGIN, MARGIN};
        };
    }

    // ------------------------------------------------------------------ Fadenkreuz
    public static void crosshair(GuiGraphicsExtractor g) {
        Config.Crosshair c = Config.get().crosshair;
        int color = Config.color(c.color, 0xFFFFFFFF);
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int t = Math.max(1, c.thickness), s = Math.max(1, c.size), gap = Math.max(0, c.gap);
        int lo = t / 2, hi = t - lo; // Dicke symmetrisch um die Mitte
        List<int[]> rects = new ArrayList<>();
        switch (c.style) {
            case "dot" -> rects.add(new int[]{cx - lo - s / 2, cy - lo - s / 2, cx + hi + s / 2, cy + hi + s / 2});
            case "circle" -> {
                int r = gap + s;
                for (int a = 0; a < 360; a += 4) {
                    double rad = Math.toRadians(a);
                    int px = cx + (int) Math.round(Math.cos(rad) * r), py = cy + (int) Math.round(Math.sin(rad) * r);
                    rects.add(new int[]{px - lo, py - lo, px + hi, py + hi});
                }
            }
            default -> { // "cross" und "t" (ohne oberen Arm)
                if (!"t".equals(c.style)) rects.add(new int[]{cx - lo, cy - gap - s, cx + hi, cy - gap});
                rects.add(new int[]{cx - lo, cy + gap + (hi - lo), cx + hi, cy + gap + s + (hi - lo)});
                rects.add(new int[]{cx - gap - s, cy - lo, cx - gap, cy + hi});
                rects.add(new int[]{cx + gap + (hi - lo), cy - lo, cx + gap + s + (hi - lo), cy + hi});
            }
        }
        if (c.dot && !"dot".equals(c.style)) rects.add(new int[]{cx - lo, cy - lo, cx + hi, cy + hi});
        if (c.outline) {
            for (int[] r : rects) g.fill(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, 0xC0000000);
        }
        for (int[] r : rects) g.fill(r[0], r[1], r[2], r[3], color);
    }
}
