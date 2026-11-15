package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Shown after an emergency logout: what happened, and the screenshot one click away. */
public final class EmergencyScreen extends Screen {
    private final List<String> report;
    private final File shot;
    private final float hearts;
    private final Theme t = Theme.load();
    private final long openedAt = System.currentTimeMillis();

    private record Hit(float x, float y, float w, float h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();

    public EmergencyScreen(List<String> report, File shot, float hearts) {
        this.hearts = hearts;
        super(Component.literal("Emergency logout"));
        this.report = report == null ? List.of() : report;
        this.shot = shot;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private static void open(File f) {
        try {
            Object os = net.minecraft.util.Util.class.getMethod("getPlatform").invoke(null);
            for (String m : new String[]{"openFile", "openPath"}) {
                try {
                    if (m.equals("openFile")) os.getClass().getMethod(m, File.class).invoke(os, f);
                    else os.getClass().getMethod(m, java.nio.file.Path.class).invoke(os, f.toPath());
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            java.awt.Desktop.getDesktop().open(f);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xFF000000 | (t.bg & 0xFFFFFF));
        hits.clear();
        float a = Math.min(1f, (System.currentTimeMillis() - openedAt) / 200f);
        int W = Math.min(520, width - 16), H = Math.min(300, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1 - a) * 8);
        int red = 0xFFE5484D;
        Ui.glow(g, width / 2, y0 + 30, W + 120, 140, 0x30E5484D);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x60E5484D);
        int cx = x0 + 18;
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 250.0);
        Px.diamond(g, cx + 4, y0 + 22, 3.5f + pulse, red);
        Type.spaced(g, "EMERGENCY LOGOUT", Type.MONO, cx + 14, y0 + 19, 0.65f, red, 1.6f);
        String title = hearts <= 0.5f ? "You were at half a heart" : String.format(java.util.Locale.ROOT, "You were down to %.1f hearts", hearts);
        Type.left(g, title, Type.DISPLAY, cx, y0 + 32, 1.8f, t.text);
        Type.left(g, "The assistant took a screenshot and logged you out. Here is what was going on:", Type.UI, cx, y0 + 52, 0.8f, t.muted);
        int y = y0 + 68;
        Px.hline(g, cx, x0 + W - 18, y - 4, 0x20000000 | (t.text & 0xFFFFFF));
        for (String line : report) {
            if (y > y0 + H - 40) break;
            int i = line.indexOf(':');
            if (i > 0 && i < 18) {
                Type.spaced(g, line.substring(0, i).toUpperCase(), Type.MONO, cx, y + 1, 0.55f, t.muted, 1.2f);
                Type.left(g, Type.fit(line.substring(i + 1).trim(), Type.UI, 0.8f, W - 140), Type.UI, cx + 100, y, 0.8f, line.startsWith("What hit") ? red : t.text);
            } else {
                Type.left(g, Type.fit(line, Type.UI, 0.8f, W - 36), Type.UI, cx, y, 0.8f, t.text);
            }
            y += 13;
        }
        // buttons
        int by = y0 + H - 30;
        String[] labels = {"OPEN SCREENSHOT", "SCREENSHOT FOLDER", "MAIN MENU"};
        Runnable[] acts = {() -> open(shot.exists() ? shot : shot.getParentFile()), () -> open(shot.getParentFile()), () -> Ui.open(new TitleScreen())};
        int bx = cx;
        for (int i = 0; i < 3; i++) {
            int bw = Type.spacedWidth(labels[i], Type.MONO, 0.65f, 1.2f) + 18;
            boolean h = Ui.in(mx, my, bx, by, bw, 16);
            boolean primary = i == 0;
            if (primary) Px.roundRect(g, bx, by, bw, 16, 3, h ? (0xFF000000 | (t.accent & 0xFFFFFF)) : (0xFF000000 | (t.text & 0xFFFFFF)));
            else Px.roundStroke(g, bx, by, bw, 16, 3, h ? (0xFF000000 | (t.accent & 0xFFFFFF)) : (0x50000000 | (t.text & 0xFFFFFF)));
            Type.spaced(g, labels[i], Type.MONO, bx + 9, by + 5, 0.65f, primary ? (0xFF000000 | (t.bg & 0xFFFFFF)) : (h ? t.accent : t.text), 1.2f);
            hits.add(new Hit(bx, by, bw, 16, acts[i]));
            bx += bw + 8;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hit h : hits) {
                if (e.x() >= h.x() && e.x() < h.x() + h.w() && e.y() >= h.y() && e.y() < h.y() + h.h()) {
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, dbl);
    }
}
