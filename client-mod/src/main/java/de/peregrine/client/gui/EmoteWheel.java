package de.peregrine.client.gui;

import de.peregrine.client.Emotes;
import de.peregrine.client.PeregrineClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Emote-Rad (Taste B): Emote mit der Maus auswählen und klicken. */
public final class EmoteWheel extends Screen {
    private final Theme t = Theme.load();
    private final long openedAt = System.currentTimeMillis();
    private int hovered = -1;

    public EmoteWheel() {
        super(Component.literal("Emotes"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        float a = Math.min(1f, (System.currentTimeMillis() - openedAt) / 150f);
        int cx = width / 2, cy = height / 2;
        int n = Emotes.ALL.length;
        float radius = 70 * (0.8f + 0.2f * a);
        double ang = Math.atan2(my - cy, mx - cx);
        double dist = Math.hypot(mx - cx, my - cy);
        hovered = dist > 18 ? (int) Math.floorMod(Math.round((ang + Math.PI / 2) / (2 * Math.PI / n)), n) : -1;
        // Mitte
        fillRound(g, cx - 26, cy - 12, 52, 24, t.bg);
        String mid = hovered >= 0 ? Emotes.ALL[hovered].name() : "Emotes";
        g.text(font, mid, cx - font.width(mid) / 2, cy - 4, hovered >= 0 ? t.accent : t.text, false);
        for (int i = 0; i < n; i++) {
            double an = -Math.PI / 2 + i * 2 * Math.PI / n;
            int x = cx + (int) (Math.cos(an) * radius), y = cy + (int) (Math.sin(an) * radius);
            boolean h = i == hovered;
            int w = 56, hh = 34;
            fillRound(g, x - w / 2, y - hh / 2, w, hh, h ? t.accent : t.bg);
            Emotes.Emote e = Emotes.ALL[i];
            int fg = h ? t.onAccent : t.text;
            g.pose().pushMatrix();
            g.pose().translate(x, y - 12);
            g.pose().scale(1.5f, 1.5f);
            g.text(font, e.icon(), -font.width(e.icon()) / 2, 0, fg, false);
            g.pose().popMatrix();
            g.pose().pushMatrix();
            g.pose().translate(x, y + 5);
            g.pose().scale(0.75f, 0.75f);
            g.text(font, e.name(), -font.width(e.name()) / 2, 0, h ? t.onAccent : t.muted, false);
            g.pose().popMatrix();
        }
        String hint = "Klicken zum Abspielen · Laufen bricht ab · ESC schließt";
        g.pose().pushMatrix();
        g.pose().translate(cx, cy + radius + 28);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, hint, -font.width(hint) / 2, 0, 0xFFFFFFFF, true);
        g.pose().popMatrix();
    }

    private static void fillRound(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (hovered >= 0) {
            Emotes.Emote em = Emotes.ALL[hovered];
            onClose();
            Emotes.play(em);
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (PeregrineClient.isEmoteKey(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }
}
