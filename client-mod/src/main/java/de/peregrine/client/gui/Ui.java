package de.peregrine.client.gui;

import de.peregrine.client.PeregrineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

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
}
