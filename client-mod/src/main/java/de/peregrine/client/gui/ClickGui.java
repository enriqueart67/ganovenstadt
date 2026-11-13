package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Modules;
import de.peregrine.client.Store;
import de.peregrine.client.module.Category;
import de.peregrine.client.module.HudModule;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.ColorSetting;
import de.peregrine.client.setting.ModeSetting;
import de.peregrine.client.setting.NumberSetting;
import de.peregrine.client.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Das Mod-Menue im Spiel (Rechts-Shift): Kategorien, Suche, Modul-Kacheln und Einstellungen. */
public final class ClickGui extends Screen {
    private Category category; // null = alle
    private String search = "";
    private Module open;
    private double scroll;
    private NumberSetting dragging;
    private int dragX, dragW;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Slider(int x, int y, int w, int h, NumberSetting s) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();

    public ClickGui() {
        super(Component.literal("Peregrine Client"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
    }

    // ------------------------------------------------------------------ Zeichnen
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        sliders.clear();
        int W = Math.min(460, width - 24), H = Math.min(300, height - 24);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        Ui.panel(g, x0, y0, W, H);

        // Kopfzeile
        g.text(font, "PEREGRINE", x0 + 10, y0 + 9, Ui.ACCENT, false);
        g.text(font, "CLIENT", x0 + 12 + font.width("PEREGRINE"), y0 + 9, Ui.MUTED, false);
        button(g, mx, my, x0 + W - 22, y0 + 5, 16, 16, "✕", false, this::onClose);
        g.fill(x0, y0 + 26, x0 + W, y0 + 27, Ui.BORDER);

        // Kategorien
        int sx = x0 + 6, sy = y0 + 34;
        List<Category> cats = new ArrayList<>();
        cats.add(null);
        cats.addAll(List.of(Category.values()));
        for (Category c : cats) {
            boolean sel = c == category && open == null;
            boolean hov = Ui.in(mx, my, sx, sy, 84, 16);
            if (sel) g.fill(sx, sy, sx + 84, sy + 16, Ui.CARD_HOVER);
            else if (hov) g.fill(sx, sy, sx + 84, sy + 16, Ui.CARD);
            if (sel) g.fill(sx, sy + 3, sx + 2, sy + 13, Ui.ACCENT);
            g.text(font, c == null ? "Alle" : c.label, sx + 8, sy + 4, sel ? Ui.TEXT : Ui.MUTED, false);
            final Category cc = c;
            hits.add(new Hit(sx, sy, 84, 16, () -> {
                category = cc;
                open = null;
                scroll = 0;
            }));
            sy += 18;
        }
        button(g, mx, my, sx, y0 + H - 44, 84, 18, "HUD bearbeiten", true, () -> Ui.open(new HudEditor(this)));
        Ui.small(g, font, "Rechts-Shift: Menü", sx + 2, y0 + H - 20, Ui.MUTED);
        Ui.small(g, font, "C Zoom · G Hell · V Sprint", sx + 2, y0 + H - 11, Ui.MUTED2);
        g.fill(x0 + 96, y0 + 27, x0 + 97, y0 + H, Ui.BORDER);

        int cx = x0 + 104, cy = y0 + 34, cw = W - 112, ch = H - 42;
        if (open == null) grid(g, mx, my, cx, cy, cw, ch);
        else settings(g, mx, my, cx, cy, cw, ch);
    }

    private void grid(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        // Suchfeld
        g.fill(cx, cy, cx + cw, cy + 16, Ui.CARD);
        Ui.border(g, cx, cy, cw, 16, Ui.BORDER);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (search.isEmpty()) g.text(font, "Module suchen …", cx + 6, cy + 4, Ui.MUTED, false);
        else g.text(font, search + (blink ? "_" : ""), cx + 6, cy + 4, Ui.TEXT, false);

        List<Module> list = new ArrayList<>();
        String q = search.toLowerCase();
        for (Module m : Modules.ALL) {
            if (category != null && m.category != category) continue;
            if (!q.isEmpty() && !m.name.toLowerCase().contains(q) && !m.description.toLowerCase().contains(q)) continue;
            list.add(m);
        }
        int top = cy + 22, cols = 2, gap = 6, cardW = (cw - gap) / cols, cardH = 40;
        int rows = (list.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, rows * (cardH + gap) - gap - (ch - 22));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        g.enableScissor(cx, top, cx + cw, cy + ch);
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            int x = cx + (i % cols) * (cardW + gap), y = top + (i / cols) * (cardH + gap) - (int) scroll;
            if (y + cardH < top || y > cy + ch) continue;
            boolean hov = Ui.in(mx, my, x, y, cardW, cardH) && my >= top;
            g.fill(x, y, x + cardW, y + cardH, hov ? Ui.CARD_HOVER : Ui.CARD);
            if (m.enabled) g.fill(x, y, x + 2, y + cardH, Ui.ACCENT);
            g.text(font, m.name, x + 8, y + 7, Ui.TEXT, false);
            Ui.small(g, font, font.plainSubstrByWidth(m.description, (int) ((cardW - 50) / 0.75)), x + 8, y + 20, Ui.MUTED);
            Ui.small(g, font, m.category.label, x + 8, y + 30, Ui.MUTED2);
            Ui.toggle(g, x + cardW - 26, y + cardH - 14, m.enabled);
            boolean hasSettings = !m.settings.isEmpty();
            if (hasSettings) {
                boolean gh = Ui.in(mx, my, x + cardW - 20, y + 4, 14, 14);
                g.text(font, "⚙", x + cardW - 17, y + 6, gh ? Ui.ACCENT : Ui.MUTED, false);
            }
            if (y >= top - cardH) {
                // Reihenfolge wichtig: spaeter hinzugefuegte Flaechen werden zuerst geprueft (Zahnrad vor Kachel)
                int vy = Math.max(y, top);
                hits.add(new Hit(x, vy, cardW, Math.max(0, y + cardH - vy), () -> {
                    m.toggle();
                    Store.save();
                }));
                if (hasSettings && y + 4 >= top) hits.add(new Hit(x + cardW - 22, y + 2, 20, 18, () -> {
                    open = m;
                    scroll = 0;
                }));
            }
        }
        g.disableScissor();
        if (list.isEmpty()) g.text(font, "Kein Modul gefunden.", cx + 4, top + 4, Ui.MUTED, false);
    }

    private void settings(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        Module m = open;
        int bw = font.width("‹ Zurück") + 12;
        button(g, mx, my, cx, cy, bw, 16, "‹ Zurück", false, () -> {
            open = null;
            scroll = 0;
        });
        int rw = font.width("Zurücksetzen") + 12;
        button(g, mx, my, cx + cw - rw, cy, rw, 16, "Zurücksetzen", false, () -> {
            m.reset();
            Store.save();
        });
        g.text(font, m.name, cx, cy + 24, Ui.TEXT, true);
        Ui.small(g, font, m.description, cx, cy + 36, Ui.MUTED);
        Ui.toggle(g, cx + cw - 24, cy + 25, m.enabled);
        hits.add(new Hit(cx + cw - 30, cy + 22, 30, 14, () -> {
            m.toggle();
            Store.save();
        }));
        int y = cy + 52;
        g.fill(cx, y - 4, cx + cw, y - 3, Ui.BORDER);
        g.enableScissor(cx, y - 2, cx + cw, cy + ch);
        y -= (int) scroll;
        for (Setting<?> s : m.settings) {
            g.text(font, s.name, cx + 2, y + 5, Ui.TEXT, false);
            int rx = cx + cw - 150;
            if (s instanceof BoolSetting b) {
                Ui.toggle(g, cx + cw - 24, y + 4, b.on());
                hits.add(new Hit(cx + cw - 30, y, 30, 16, () -> {
                    b.toggle();
                    Store.save();
                }));
            } else if (s instanceof NumberSetting n) {
                int sw = 110;
                g.fill(rx, y + 7, rx + sw, y + 9, Ui.CARD_HOVER);
                int kx = rx + (int) (n.fraction() * sw);
                g.fill(rx, y + 7, kx, y + 9, Ui.ACCENT);
                g.fill(kx - 2, y + 3, kx + 2, y + 13, Ui.TEXT);
                g.text(font, n.text(), rx + sw + 8, y + 5, Ui.MUTED, false);
                sliders.add(new Slider(rx - 3, y, sw + 6, 16, n));
            } else if (s instanceof ModeSetting md) {
                int w = 150;
                boolean hov = Ui.in(mx, my, rx, y, w, 16);
                g.fill(rx, y, rx + w, y + 16, hov ? Ui.CARD_HOVER : Ui.CARD);
                g.text(font, "‹", rx + 5, y + 4, Ui.MUTED, false);
                g.text(font, "›", rx + w - 9, y + 4, Ui.MUTED, false);
                g.text(font, md.get(), rx + (w - font.width(md.get())) / 2, y + 4, Ui.TEXT, false);
                hits.add(new Hit(rx, y, w / 2, 16, () -> {
                    md.cycle(-1);
                    Store.save();
                }));
                hits.add(new Hit(rx + w / 2, y, w - w / 2, 16, () -> {
                    md.cycle(1);
                    Store.save();
                }));
            } else if (s instanceof ColorSetting c) {
                int px = rx - 30;
                for (int col : ColorSetting.PALETTE) {
                    if (col == c.argb()) g.fill(px - 1, y + 1, px + 12, y + 14, Ui.TEXT);
                    g.fill(px, y + 2, px + 11, y + 13, col);
                    final int cc = col;
                    hits.add(new Hit(px, y + 2, 11, 11, () -> {
                        c.set(cc);
                        Store.save();
                    }));
                    px += 15;
                }
            }
            y += 22;
        }
        if (m instanceof HudModule) {
            Ui.small(g, font, "Position: oben auf „HUD bearbeiten“ klicken und die Anzeige mit der Maus verschieben.", cx + 2, y + 4, Ui.MUTED);
        }
        g.disableScissor();
    }

    private void button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String label, boolean accent, Runnable action) {
        boolean hov = Ui.in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, accent ? (hov ? Ui.ACCENT_HOVER : Ui.ACCENT) : (hov ? Ui.CARD_HOVER : Ui.CARD));
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, Ui.TEXT, false);
        hits.add(new Hit(x, y, w, h, action));
    }

    // ------------------------------------------------------------------ Eingaben
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (e.button() == 0) {
            for (Slider s : sliders) {
                if (mx >= s.x() && mx < s.x() + s.w() && my >= s.y() && my < s.y() + s.h()) {
                    dragging = s.s();
                    dragX = s.x() + 3;
                    dragW = s.w() - 6;
                    dragging.setFraction((mx - dragX) / dragW);
                    return true;
                }
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.in(mx, my)) {
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging != null) {
            dragging.setFraction((e.x() - dragX) / dragW);
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging != null) {
            dragging = null;
            Store.save();
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, scroll - sy * 20);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == InputConstants.KEY_ESCAPE && open != null) {
            open = null;
            scroll = 0;
            return true;
        }
        if (k == InputConstants.KEY_RSHIFT) {
            onClose();
            return true;
        }
        if (k == InputConstants.KEY_BACKSPACE && open == null && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            scroll = 0;
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (open == null && e.isAllowedChatCharacter() && search.length() < 24) {
            search += e.codepointAsString();
            scroll = 0;
            return true;
        }
        return super.charTyped(e);
    }

    @Override
    public void removed() {
        Store.save();
        super.removed();
    }
}
