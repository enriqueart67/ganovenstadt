package de.peregrine.client.gui;

import de.peregrine.client.PeregrineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Farben und kleine Zeichen-Helfer fuer das Menue. */
public final class Ui {
    public static final int BG = 0xF0141418;
    public static final int CARD = 0xFF202027;
    public static final int CARD_HOVER = 0xFF2C2C36;
    public static final int BORDER = 0xFF2E2E38;
    public static final int TEXT = 0xFFF0F0F5;
    public static final int MUTED = 0xFF9A9AAA;
    public static final int MUTED2 = 0xFF6A6A78;
    public static final int ACCENT = 0xFF4F8CFF;
    public static final int ACCENT_HOVER = 0xFF6A9DFF;

    private Ui() {
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x60000000); // Schatten
        g.fill(x, y, x + w, y + h, BG);
        border(g, x, y, w, h, BORDER);
    }

    /** Kleiner Text (75 %). */
    public static void small(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75f, 0.75f);
        g.text(f, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** Ein-/Aus-Schalter (18×8). */
    public static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on) {
        g.fill(x, y, x + 18, y + 8, on ? ACCENT : 0xFF3A3A46);
        int kx = on ? x + 11 : x + 1;
        g.fill(kx, y + 1, kx + 6, y + 7, TEXT);
    }

    /** The open screen, or null – Minecraft.screen in 26.1, Gui.screen() in 26.2+. */
    public static Screen current() {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object gui = mc.gui;
            return (Screen) gui.getClass().getMethod("screen").invoke(gui);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            return (Screen) Minecraft.class.getField("screen").get(mc);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Bildschirm oeffnen – funktioniert mit 26.1 (Minecraft.setScreen) und 26.2+ (Gui.setScreen). */
    public static void open(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object gui = mc.gui;
            gui.getClass().getMethod("setScreen", Screen.class).invoke(gui, s);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Minecraft.class.getMethod("setScreen", Screen.class).invoke(mc, s);
        } catch (ReflectiveOperationException e) {
            PeregrineClient.LOG.warn("Konnte Menü nicht öffnen: {}", e.toString());
        }
    }

    private static final Identifier CHART = Identifier.fromNamespaceAndPath("peregrine", "textures/gui/chart.png");

    /** Contour lines of the "flight chart" design over a panel (sepia on paper, pale green at night). */
    public static void chart(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean light) {
        try {
            double s = Minecraft.getInstance().getWindow().getGuiScale();
            int tw = (int) Math.min(1024, w * s), th = (int) Math.min(1024, h * s);
            int col = light ? 0x38967848 : 0x2E7FA090;
            g.blit(RenderPipelines.GUI_TEXTURED, CHART, x, y, 0, 0, w, h, tw, th, 1024, 1024, col);
        } catch (Throwable ignored) {
        }
    }

    private static final Identifier FALCON = Identifier.fromNamespaceAndPath("peregrine", "textures/gui/falcon.png");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("peregrine", "textures/gui/glow.png");

    private static void tinted(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int tex, int argb) {
        try {
            g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, tex, tex, tex, tex, argb);
        } catch (Throwable ignored) {
        }
    }

    /** Peregrine's mark: the falcon in a stoop (same shape as in the launcher). */
    public static void falcon(GuiGraphicsExtractor g, int cx, int cy, int size, int argb) {
        tinted(g, FALCON, cx - size / 2, cy - size / 2, size, size, 128, argb);
    }

    /** Soft round light (radial gradient), e.g. 0x30 alpha in the accent colour. */
    public static void glow(GuiGraphicsExtractor g, int cx, int cy, int w, int h, int argb) {
        tinted(g, GLOW, cx - w / 2, cy - h / 2, w, h, 256, argb);
    }

    /** Status diamond (smooth, in screen pixels). */
    public static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
        Px.diamond(g, cx + 0.5f, cy + 0.5f, r + 0.6f, argb);
    }

    /** Pill switch like the launcher's: round track, round knob, glows when on. 20 × 10. */
    public static void pill(GuiGraphicsExtractor g, int x, int y, float on, boolean hover, Theme t) {
        float w = 20, h = 10;
        int track = mix(0x22000000 | (t.text & 0xFFFFFF), 0xFF000000 | (t.accent & 0xFFFFFF), on);
        if (on > 0.3f) glow(g, x + 15, y + 5, 30, 20, ((int) (0x50 * on) << 24) | (t.accent & 0xFFFFFF));
        Px.roundRect(g, x, y, w, h, h / 2, track);
        if (hover || on < 0.5f) Px.roundStroke(g, x, y, w, h, h / 2, (hover ? 0x80000000 : 0x26000000) | (t.text & 0xFFFFFF));
        float kx = x + 5 + (w - 10) * on;
        int knob = on > 0.5f ? (0xFF000000 | (t.bg & 0xFFFFFF)) : (0xFF000000 | (t.muted & 0xFFFFFF));
        Px.circle(g, kx, y + h / 2, 3.2f + (hover ? 0.3f : 0f), knob);
    }

    private static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}
