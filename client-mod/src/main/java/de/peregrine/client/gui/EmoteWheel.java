package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Emotes;
import de.peregrine.client.PeregrineClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Emote-Rad: runder Ring mit einem Segment pro Emote. Taste (B) halten, Maus in die Richtung bewegen und
 * loslassen – oder klicken – oder Zifferntaste 1–8. Die Mitte stoppt ein laufendes Emote.
 */
public final class EmoteWheel extends Screen {
    private final Theme t = Theme.load();
    private final long openedAt = System.currentTimeMillis();
    private int hovered = -1;
    private boolean centerHovered;
    private boolean keyHeld = true; // mit der Taste geöffnet: Loslassen wählt aus

    private static final int R_IN = 30, R_OUT = 92, POP = 7;

    public EmoteWheel() {
        super(Component.literal("Emotes"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, (int) (0x70 * anim()) << 24);
    }

    private float anim() {
        float x = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private static int withAlpha(int argb, float a) {
        return ((int) (((argb >>> 24) & 0xFF) * a) << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        float a = anim();
        int n = Emotes.ALL.length;
        int cx = width / 2, cy = height / 2;
        float scale = 0.82f + 0.18f * a;
        int rIn = (int) (R_IN * scale), rOut = (int) (R_OUT * scale);
        double seg = 2 * Math.PI / n;

        // Auswahl aus Mausrichtung
        double dx = mx - cx, dy = my - cy, dist = Math.hypot(dx, dy);
        centerHovered = dist < rIn;
        hovered = dist >= rIn ? segmentAt(dx, dy, n) : -1;

        // ---------- Ring (zeilenweise gefüllt, Lücken zwischen den Segmenten)
        int ringA = 0xF0000000 | (t.bg & 0xFFFFFF);
        int ringB = 0xF0000000 | (t.card & 0xFFFFFF);
        int maxR = rOut + POP;
        for (int yy = -maxR; yy <= maxR; yy++) {
            int spanStart = Integer.MIN_VALUE, spanColor = 0;
            for (int xx = -maxR; xx <= maxR + 1; xx++) {
                int color = 0;
                if (xx <= maxR) {
                    double d = Math.hypot(xx, yy);
                    if (d >= rIn + 2) {
                        int s = segmentAt(xx, yy, n);
                        int outer = s == hovered ? rOut + POP : rOut;
                        if (d <= outer) {
                            // schmale Fuge zwischen den Segmenten
                            double ang = Math.atan2(yy, xx) + Math.PI / 2 + seg / 2;
                            double within = ang / seg - Math.floor(ang / seg);
                            double gapPx = Math.min(within, 1 - within) * seg * d;
                            if (gapPx > 1.2) {
                                color = s == hovered ? t.accent : (s % 2 == 0 ? ringA : ringB);
                            }
                        }
                    } else if (d <= rIn - 1) {
                        color = centerHovered && Emotes.active() != null ? t.accent : (0xF8000000 | (t.bg & 0xFFFFFF));
                    }
                }
                if (color != spanColor) {
                    if (spanColor != 0) g.fill(cx + spanStart, cy + yy, cx + xx, cy + yy + 1, withAlpha(spanColor, a));
                    spanStart = xx;
                    spanColor = color;
                }
            }
        }

        // ---------- Symbole und Namen
        for (int i = 0; i < n; i++) {
            Emotes.Emote e = Emotes.ALL[i];
            boolean h = i == hovered;
            double an = -Math.PI / 2 + i * seg;
            float r = (rIn + rOut) / 2f + (h ? POP / 2f : 0);
            int x = cx + (int) (Math.cos(an) * r), y = cy + (int) (Math.sin(an) * r);
            int fg = h ? t.onAccent : t.text;
            text(g, e.icon(), x, y - 11, h ? 2.0f : 1.7f, withAlpha(fg, a));
            text(g, e.name(), x, y + 6, 0.72f, withAlpha(h ? t.onAccent : t.muted, a));
            // Zifferntaste am Rand
            int kx = cx + (int) (Math.cos(an) * (rOut + (h ? POP : 0) + 9)), ky = cy + (int) (Math.sin(an) * (rOut + (h ? POP : 0) + 9));
            text(g, String.valueOf(i + 1), kx, ky - 3, 0.7f, withAlpha(0xFFFFFFFF, a * 0.75f));
        }

        // ---------- Mitte
        if (hovered >= 0) {
            text(g, Emotes.ALL[hovered].name(), cx, cy - 7, 0.85f, withAlpha(t.accent, a));
            text(g, "loslassen", cx, cy + 4, 0.6f, withAlpha(t.muted, a));
        } else if (Emotes.active() != null) {
            text(g, "■", cx, cy - 9, 1.4f, withAlpha(centerHovered ? t.onAccent : t.accent, a));
            text(g, "Stopp", cx, cy + 4, 0.7f, withAlpha(centerHovered ? t.onAccent : t.muted, a));
        } else {
            text(g, "EMOTES", cx, cy - 4, 0.75f, withAlpha(t.text, a));
        }
        text(g, "Taste halten, Richtung wählen, loslassen  ·  Klick  ·  1–8  ·  Laufen bricht ab", cx, 8, 0.7f,
                withAlpha(0xFFFFFFFF, a * 0.85f));
    }

    /** Segment (0 = oben, im Uhrzeigersinn) zu einer Richtung. */
    private static int segmentAt(double dx, double dy, int n) {
        double ang = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / n;
        double seg = 2 * Math.PI / n;
        return Math.floorMod((int) Math.floor(ang / seg), n);
    }

    private void text(GuiGraphicsExtractor g, String s, int cx, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(cx - font.width(s) * scale / 2f, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private void choose() {
        if (hovered >= 0) {
            Emotes.Emote em = Emotes.ALL[hovered];
            onClose();
            Emotes.play(em);
        } else if (centerHovered && Emotes.active() != null) {
            Emotes.stop();
            onClose();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (hovered >= 0 || centerHovered) {
            choose();
            return true;
        }
        onClose();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_9 && k - InputConstants.KEY_1 < Emotes.ALL.length) {
            Emotes.Emote em = Emotes.ALL[k - InputConstants.KEY_1];
            onClose();
            Emotes.play(em);
            return true;
        }
        if (PeregrineClient.isEmoteKey(e) && !keyHeld) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (PeregrineClient.isEmoteKey(e) && keyHeld) {
            keyHeld = false;
            // kurz angetippt (ohne Richtung): Rad bleibt offen zum Klicken
            if (hovered >= 0 || (centerHovered && Emotes.active() != null && System.currentTimeMillis() - openedAt > 250)) choose();
            return true;
        }
        return super.keyReleased(e);
    }
}
