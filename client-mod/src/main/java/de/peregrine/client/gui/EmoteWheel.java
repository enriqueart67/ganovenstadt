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
 * Emote wheel: a ring with one segment per emote. Hold the key, move the mouse towards a segment and
 * release – or click – or press 1–8. Scroll to switch pages. The hub stops a running emote.
 */
public final class EmoteWheel extends Screen {
    private static int page; // remembered between openings
    private final Theme t = Theme.load();
    private final long openedAt = System.currentTimeMillis();
    private int hovered = -1;
    private boolean hubHovered;
    private boolean keyHeld = true; // opened by key: releasing it picks the hovered emote

    private static final int R_IN = 30, R_OUT = 92, POP = 6;

    public EmoteWheel() {
        super(Component.literal("Emotes"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int pages() {
        return (Emotes.ALL.length + Emotes.PAGE - 1) / Emotes.PAGE;
    }

    private static int count() {
        return Math.min(Emotes.PAGE, Emotes.ALL.length - page * Emotes.PAGE);
    }

    private static Emotes.Emote at(int segment) {
        return Emotes.ALL[page * Emotes.PAGE + segment];
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, (int) (0x70 * anim()) << 24);
    }

    private float anim() {
        float x = Math.min(1f, (System.currentTimeMillis() - openedAt) / 150f);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private static int fade(int argb, float a) {
        return ((int) (((argb >>> 24) & 0xFF) * a) << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        if (page >= pages()) page = 0;
        float a = anim();
        int n = count();
        int cx = width / 2, cy = height / 2;
        float scale = 0.85f + 0.15f * a;
        int rIn = (int) (R_IN * scale), rOut = (int) (R_OUT * scale);
        double seg = 2 * Math.PI / n;

        double dx = mx - cx, dy = my - cy, dist = Math.hypot(dx, dy);
        hubHovered = dist < rIn;
        hovered = dist >= rIn ? segmentAt(dx, dy, n) : -1;

        // ---------- ring, filled scanline by scanline with hairline gaps between segments
        int paper = 0xFF000000 | (t.bg & 0xFFFFFF);
        int maxR = rOut + POP;
        for (int yy = -maxR; yy <= maxR; yy++) {
            int spanStart = 0, spanColor = 0;
            for (int xx = -maxR; xx <= maxR + 1; xx++) {
                int color = 0;
                if (xx <= maxR) {
                    double d = Math.hypot(xx, yy);
                    if (d >= rIn + 2) {
                        int s = segmentAt(xx, yy, n);
                        if (d <= (s == hovered ? rOut + POP : rOut)) {
                            double ang = Math.atan2(yy, xx) + Math.PI / 2 + seg / 2;
                            double within = ang / seg - Math.floor(ang / seg);
                            if (Math.min(within, 1 - within) * seg * d > 0.9) color = s == hovered ? t.accent : paper;
                        }
                    } else if (d <= rIn - 1) {
                        color = hubHovered && Emotes.active() != null ? t.accent : paper;
                    }
                }
                if (color != spanColor) {
                    if (spanColor != 0) g.fill(cx + spanStart, cy + yy, cx + xx, cy + yy + 1, fade(spanColor, a));
                    spanStart = xx;
                    spanColor = color;
                }
            }
        }

        // ---------- labels
        for (int i = 0; i < n; i++) {
            boolean h = i == hovered;
            double an = -Math.PI / 2 + i * seg;
            float r = (rIn + rOut) / 2f + (h ? POP / 2f : 0);
            float x = cx + (float) Math.cos(an) * r, y = cy + (float) Math.sin(an) * r;
            Type.center(g, String.valueOf(i + 1), Type.MONO, x, y - 11, 0.7f, fade(h ? t.onAccent : t.muted, a));
            Type.center(g, at(i).name(), Type.BOLD, x, y - 3, 1f, fade(h ? t.onAccent : t.text, a));
        }

        // ---------- hub
        if (hovered >= 0) {
            Type.center(g, "RELEASE", Type.MONO, cx, cy - 3, 0.7f, fade(t.muted, a));
        } else if (Emotes.active() != null) {
            Type.center(g, "STOP", Type.MONO, cx, cy - 3, 0.8f, fade(hubHovered ? t.onAccent : t.accent, a));
        } else {
            Type.center(g, "EMOTES", Type.MONO, cx, cy - 3, 0.7f, fade(t.text, a));
        }
        String hint = "HOLD · AIM · RELEASE      1–" + n + "      SCROLL: PAGE " + (page + 1) + "/" + pages();
        Type.center(g, hint, Type.MONO, cx, Math.min(height - 12, cy + rOut + POP + 10), 0.7f, fade(0xFFFFFFFF, a * 0.9f));
    }

    /** Segment (0 = top, clockwise) for a direction. */
    private static int segmentAt(double dx, double dy, int n) {
        double seg = 2 * Math.PI / n;
        double ang = Math.atan2(dy, dx) + Math.PI / 2 + seg / 2;
        return Math.floorMod((int) Math.floor(ang / seg), n);
    }

    private void choose() {
        if (hovered >= 0 && hovered < count()) {
            Emotes.Emote em = at(hovered);
            onClose();
            Emotes.play(em);
        } else if (hubHovered && Emotes.active() != null) {
            Emotes.stop();
            onClose();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (hovered >= 0 || hubHovered) choose();
        else onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        page = Math.floorMod(page + (sy < 0 ? 1 : -1), pages());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_9 && k - InputConstants.KEY_1 < count()) {
            Emotes.Emote em = at(k - InputConstants.KEY_1);
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
            // a quick tap without aiming leaves the wheel open for clicking
            if (hovered >= 0 || (hubHovered && Emotes.active() != null && System.currentTimeMillis() - openedAt > 250)) choose();
            return true;
        }
        return super.keyReleased(e);
    }
}
