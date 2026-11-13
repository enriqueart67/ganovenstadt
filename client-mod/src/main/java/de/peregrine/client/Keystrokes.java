package de.peregrine.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Zeigt W A S D, Maustasten (mit CPS) und Leertaste an – gedrueckte Tasten leuchten. */
public final class Keystrokes {
    private static final int K = 20, GAP = 2;

    public static void render(GuiGraphicsExtractor g, Minecraft mc, Config c) {
        Font f = mc.font;
        int accent = Config.color(c.accentColor, 0xFF4F8CFF);
        int text = Config.color(c.textColor, 0xFFFFFFFF);
        int w = K * 3 + GAP * 2;
        int h = K * 3 + GAP * 3 + 10;
        int[] pos = Hud.corner(g, c.keysPosition, w, h);
        int x = pos[0], y = pos[1];
        var o = mc.options;
        key(g, f, o.keyUp, "W", x + K + GAP, y, K, K, accent, text, c);
        key(g, f, o.keyLeft, "A", x, y + K + GAP, K, K, accent, text, c);
        key(g, f, o.keyDown, "S", x + K + GAP, y + K + GAP, K, K, accent, text, c);
        key(g, f, o.keyRight, "D", x + (K + GAP) * 2, y + K + GAP, K, K, accent, text, c);
        int mw = (w - GAP) / 2, my = y + (K + GAP) * 2;
        key(g, f, o.keyAttack, "LMB " + Clicks.left(), x, my, mw, K, accent, text, c);
        key(g, f, o.keyUse, "RMB " + Clicks.right(), x + mw + GAP, my, w - mw - GAP, K, accent, text, c);
        key(g, f, o.keyJump, "———", x, my + K + GAP, w, 10, accent, text, c);
    }

    private static void key(GuiGraphicsExtractor g, Font f, KeyMapping km, String label, int x, int y, int w, int h,
                            int accent, int text, Config c) {
        boolean down = km.isDown();
        int bg = down ? (accent & 0x00FFFFFF) | 0xD0000000 : (c.background ? 0x90000000 : 0x00000000);
        g.fill(x, y, x + w, y + h, bg);
        int tw = f.width(label);
        g.text(f, label, x + (w - tw) / 2, y + (h - f.lineHeight) / 2 + 1, down ? 0xFFFFFFFF : text, c.shadow);
    }
}
