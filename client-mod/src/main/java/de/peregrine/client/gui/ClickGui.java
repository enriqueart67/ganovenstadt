package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Modules;
import de.peregrine.client.PeregrineClient;
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
import java.util.Map;

/**
 * Das Mod-Menü im Spiel: großes Fenster mit Seitenleiste (Kategorien mit Symbolen), Suchleiste,
 * Modul-Kacheln (Symbol, Name, Beschreibung, An/Aus-Leiste, Zahnrad) und eigener Einstellungsseite.
 */
public final class ClickGui extends Screen {
    private static Category lastCategory; // merkt sich die Kategorie zwischen dem Öffnen
    private Category category = lastCategory; // null = alle
    private String search = "";
    private Module open;
    private double scroll, scrollTarget;
    private NumberSetting dragging;
    private int dragX, dragW;
    private final long openedAt = System.currentTimeMillis();
    private long pageAt = System.currentTimeMillis();

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Slider(int x, int y, int w, int h, NumberSetting s) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();

    /** Symbole (Unicode) für Kategorien und Module. */
    private static final Map<Category, String> CAT_ICON = Map.of(
            Category.HUD, "▣", Category.PVP, "⚔", Category.GRAPHICS, "☀", Category.MOVEMENT, "➜", Category.OTHER, "✦");
    private static final Map<String, String> MOD_ICON = Map.ofEntries(
            Map.entry("fps", "⏱"), Map.entry("cps", "🖱"), Map.entry("coords", "⌖"), Map.entry("clock", "⌚"),
            Map.entry("ping", "📶"), Map.entry("server", "🖧"), Map.entry("speed", "»"), Map.entry("days", "☼"),
            Map.entry("memory", "▤"), Map.entry("reach", "↔"), Map.entry("combo", "✚"), Map.entry("potions", "⚗"),
            Map.entry("armor", "⛨"), Map.entry("keystrokes", "⬆"), Map.entry("crosshair", "✛"), Map.entry("nohurtcam", "♥"),
            Map.entry("zoom", "🔍"), Map.entry("fullbright", "☀"), Map.entry("noweather", "☂"), Map.entry("togglesprint", "➜"),
            Map.entry("togglesneak", "⇣"), Map.entry("style", "🎨"));

    // Farben (dunkel, Peregrine-Orange als Akzent)
    private static final int BG = 0xF2101013, SIDE = 0xFF0B0B0D, CARD = 0xFF19191E, CARD_HOVER = 0xFF222229,
            LINE = 0xFF26262E, TEXT = 0xFFF2F2F5, MUTED = 0xFF8C8C99, MUTED2 = 0xFF5E5E6A,
            ACCENT = 0xFFFF4F00, ACCENT_DARK = 0xFF7A2A08, OFF = 0xFF2A2A32;

    public ClickGui() {
        super(Component.literal("Peregrine Client"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        try {
            extractBlurredBackground(g);
        } catch (Throwable ignored) {
            // ältere/neuere Versionen ohne Unschärfe: nur abdunkeln
        }
        g.fill(0, 0, width, height, alpha(0x90000000, anim()));
    }

    /** Öffnen-Animation 0..1 (weich auslaufend). */
    private float anim() {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static int alpha(int argb, float f) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Rechteck mit abgeschrägten Ecken (wirkt abgerundet). */
    private static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    private void scaled(GuiGraphicsExtractor g, String s, int x, int y, float scale, int color, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, float scale, int color) {
        scaled(g, s, cx - (int) (font.width(s) * scale / 2f), y, scale, color, false);
    }

    // ------------------------------------------------------------------ Zeichnen
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        sliders.clear();
        scroll += (scrollTarget - scroll) * 0.35;
        if (Math.abs(scrollTarget - scroll) < 0.5) scroll = scrollTarget;

        float a = anim();
        int W = Math.min(620, width - 20), H = Math.min(370, height - 20);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + (int) ((1f - a) * 14);
        g.fill(x0 + 4, y0 + 6, x0 + W + 4, y0 + H + 6, alpha(0x70000000, a)); // Schatten
        round(g, x0, y0, W, H, BG);

        // ---------- Seitenleiste
        int sw = 128;
        round(g, x0, y0, sw, H, SIDE);
        g.fill(x0 + sw - 1, y0, x0 + sw, y0 + H, LINE);
        // Logo
        round(g, x0 + 12, y0 + 12, 22, 22, ACCENT);
        centered(g, "P", x0 + 23, y0 + 16, 1.4f, 0xFF000000 | 0xFFFFFF);
        g.text(font, "PEREGRINE", x0 + 40, y0 + 13, TEXT, false);
        scaled(g, "CLIENT", x0 + 40, y0 + 24, 0.75f, ACCENT, false);

        int sy = y0 + 50;
        scaled(g, "KATEGORIEN", x0 + 14, sy, 0.7f, MUTED2, false);
        sy += 12;
        int by = y0 + H - 50;
        int rowH = Math.max(14, Math.min(20, (by - 6 - sy) / 6 - 2));
        List<Category> cats = new ArrayList<>();
        cats.add(null);
        cats.addAll(List.of(Category.values()));
        for (Category c : cats) {
            boolean sel = c == category;
            boolean hov = Ui.in(mx, my, x0 + 8, sy, sw - 16, rowH);
            if (sel) round(g, x0 + 8, sy, sw - 16, rowH, CARD_HOVER);
            else if (hov) round(g, x0 + 8, sy, sw - 16, rowH, CARD);
            int ty = sy + (rowH - 8) / 2;
            if (sel) g.fill(x0 + 8, sy + 3, x0 + 10, sy + rowH - 3, ACCENT);
            String icon = c == null ? "▦" : CAT_ICON.getOrDefault(c, "•");
            g.text(font, icon, x0 + 16, ty, sel ? ACCENT : MUTED, false);
            g.text(font, c == null ? "Alle Module" : c.label, x0 + 30, ty, sel ? TEXT : (hov ? TEXT : MUTED), false);
            long n = Modules.ALL.stream().filter(m -> cc(c, m)).count();
            String cnt = String.valueOf(n);
            scaled(g, cnt, x0 + sw - 16 - (int) (font.width(cnt) * 0.75f), ty + 1, 0.75f, MUTED2, false);
            final Category ca = c;
            hits.add(new Hit(x0 + 8, sy, sw - 16, rowH, () -> {
                category = ca;
                lastCategory = ca;
                open = null;
                scrollTarget = scroll = 0;
                pageAt = System.currentTimeMillis();
            }));
            sy += rowH + 2;
        }
        // unten: HUD-Editor + Hinweis
        boolean hh = Ui.in(mx, my, x0 + 10, by, sw - 20, 22);
        round(g, x0 + 10, by, sw - 20, 22, hh ? 0xFFFF6A26 : ACCENT);
        centered(g, "✎  HUD bearbeiten", x0 + sw / 2, by + 7, 1f, 0xFFFFFFFF);
        hits.add(new Hit(x0 + 10, by, sw - 20, 22, () -> Ui.open(new HudEditor(this))));
        long on = Modules.ALL.stream().filter(m -> m.enabled).count();
        scaled(g, on + " von " + Modules.ALL.size() + " Modulen aktiv", x0 + 12, y0 + H - 20, 0.7f, MUTED, false);

        // ---------- Inhalt
        int cx = x0 + sw + 14, cy = y0 + 12, cw = W - sw - 26, ch = H - 24;
        // Schließen
        boolean xh = Ui.in(mx, my, x0 + W - 22, y0 + 8, 14, 14);
        g.text(font, "✕", x0 + W - 19, y0 + 11, xh ? ACCENT : MUTED, false);
        hits.add(new Hit(x0 + W - 22, y0 + 8, 14, 14, this::onClose));

        if (open == null) grid(g, mx, my, cx, cy, cw - 18, ch);
        else settings(g, mx, my, cx, cy, cw - 18, ch);
    }

    private static boolean cc(Category c, Module m) {
        return c == null || m.category == c;
    }

    private void grid(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        // Überschrift + Suche
        String title = category == null ? "Alle Module" : category.label;
        scaled(g, title, cx, cy + 2, 1.5f, TEXT, false);
        int sw = Math.min(170, cw / 2), sx = cx + cw - sw;
        boolean sh = Ui.in(mx, my, sx, cy, sw, 18);
        round(g, sx, cy, sw, 18, sh ? CARD_HOVER : CARD);
        g.text(font, "⌕", sx + 6, cy + 5, MUTED, false);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (search.isEmpty()) g.text(font, "Suchen …", sx + 18, cy + 5, MUTED2, false);
        else g.text(font, font.plainSubstrByWidth(search, sw - 26) + (blink ? "_" : ""), sx + 18, cy + 5, TEXT, false);

        List<Module> list = new ArrayList<>();
        String q = search.toLowerCase();
        for (Module m : Modules.ALL) {
            if (!cc(category, m)) continue;
            if (!q.isEmpty() && !m.name.toLowerCase().contains(q) && !m.description.toLowerCase().contains(q)) continue;
            list.add(m);
        }
        int top = cy + 28, cols = cw >= 330 ? 3 : 2, gap = 8;
        int cardW = (cw - gap * (cols - 1)) / cols, cardH = 82;
        int rows = (list.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, rows * (cardH + gap) - gap - (cy + ch - top));
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        float page = Math.min(1f, (System.currentTimeMillis() - pageAt) / 160f);
        g.enableScissor(cx, top, cx + cw, cy + ch);
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            int x = cx + (i % cols) * (cardW + gap);
            int y = top + (i / cols) * (cardH + gap) - (int) scroll + (int) ((1f - page) * 6);
            if (y + cardH < top || y > cy + ch) continue;
            boolean hov = Ui.in(mx, my, x, y, cardW, cardH) && my >= top && my < cy + ch;
            round(g, x, y, cardW, cardH, hov ? CARD_HOVER : CARD);
            if (m.enabled) {
                g.fill(x + 1, y, x + cardW - 1, y + 1, ACCENT);
            }
            // Symbol
            int ix = x + cardW / 2 - 13, iy = y + 8;
            round(g, ix, iy, 26, 26, m.enabled ? ACCENT_DARK : OFF);
            centered(g, MOD_ICON.getOrDefault(m.id, "•"), x + cardW / 2, iy + 7, 1.4f, m.enabled ? 0xFFFFB38A : MUTED);
            // Name + Beschreibung
            centered(g, font.plainSubstrByWidth(m.name, cardW - 10), x + cardW / 2, y + 39, 1f, TEXT);
            String d = font.plainSubstrByWidth(m.description, (int) ((cardW - 12) / 0.7f));
            centered(g, d, x + cardW / 2, y + 51, 0.7f, MUTED);
            // An/Aus-Leiste
            int bx = x + 6, bw = cardW - 12 - (m.settings.isEmpty() ? 0 : 20), byy = y + cardH - 20;
            boolean bh = Ui.in(mx, my, bx, byy, bw, 14);
            round(g, bx, byy, bw, 14, m.enabled ? (bh ? 0xFFFF6A26 : ACCENT) : (bh ? 0xFF34343E : OFF));
            centered(g, m.enabled ? "AKTIVIERT" : "AUS", bx + bw / 2, byy + 4, 0.75f, m.enabled ? 0xFFFFFFFF : MUTED);
            boolean visible = y + cardH > top && y < cy + ch;
            if (visible) {
                hits.add(new Hit(x, Math.max(y, top), cardW, Math.max(0, Math.min(y + cardH, cy + ch) - Math.max(y, top)), () -> {
                    m.toggle();
                    Store.save();
                }));
            }
            if (!m.settings.isEmpty()) {
                int gx = x + cardW - 20;
                boolean gh = Ui.in(mx, my, gx, byy, 14, 14);
                round(g, gx, byy, 14, 14, gh ? 0xFF34343E : OFF);
                g.text(font, "⚙", gx + 3, byy + 3, gh ? ACCENT : MUTED, false);
                if (visible && byy >= top && byy + 14 <= cy + ch) hits.add(new Hit(gx, byy, 14, 14, () -> {
                    open = m;
                    scrollTarget = scroll = 0;
                    pageAt = System.currentTimeMillis();
                }));
            }
        }
        g.disableScissor();
        if (maxScroll > 0) { // Bildlaufleiste
            int trackH = cy + ch - top, barH = Math.max(20, trackH * trackH / (trackH + maxScroll));
            int barY = top + (int) ((trackH - barH) * (scroll / maxScroll));
            g.fill(cx + cw + 6, top, cx + cw + 8, cy + ch, LINE);
            g.fill(cx + cw + 6, barY, cx + cw + 8, barY + barH, MUTED2);
        }
        if (list.isEmpty()) centered(g, "Kein Modul gefunden.", cx + cw / 2, top + 30, 1f, MUTED);
    }

    private void settings(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        Module m = open;
        float page = Math.min(1f, (System.currentTimeMillis() - pageAt) / 160f);
        cx += (int) ((1f - page) * 10);
        boolean bh = Ui.in(mx, my, cx, cy, 60, 18);
        round(g, cx, cy, 60, 18, bh ? CARD_HOVER : CARD);
        g.text(font, "‹ Zurück", cx + 8, cy + 5, bh ? TEXT : MUTED, false);
        hits.add(new Hit(cx, cy, 60, 18, () -> {
            open = null;
            scrollTarget = scroll = 0;
            pageAt = System.currentTimeMillis();
        }));
        int rw = font.width("Zurücksetzen") + 16;
        boolean rh = Ui.in(mx, my, cx + cw - rw, cy, rw, 18);
        round(g, cx + cw - rw, cy, rw, 18, rh ? CARD_HOVER : CARD);
        g.text(font, "Zurücksetzen", cx + cw - rw + 8, cy + 5, rh ? TEXT : MUTED, false);
        hits.add(new Hit(cx + cw - rw, cy, rw, 18, () -> {
            m.reset();
            Store.save();
        }));

        // Kopf: Symbol, Name, Beschreibung, Schalter
        int hy = cy + 28;
        round(g, cx, hy, cw, 44, CARD);
        round(g, cx + 8, hy + 8, 28, 28, m.enabled ? ACCENT_DARK : OFF);
        centered(g, MOD_ICON.getOrDefault(m.id, "•"), cx + 22, hy + 16, 1.4f, m.enabled ? 0xFFFFB38A : MUTED);
        scaled(g, m.name, cx + 44, hy + 10, 1.25f, TEXT, false);
        scaled(g, font.plainSubstrByWidth(m.description, (int) ((cw - 120) / 0.8f)), cx + 44, hy + 26, 0.8f, MUTED, false);
        int tx = cx + cw - 60, ty = hy + 14;
        round(g, tx, ty, 50, 16, m.enabled ? ACCENT : OFF);
        centered(g, m.enabled ? "AN" : "AUS", tx + 25, ty + 4, 0.85f, m.enabled ? 0xFFFFFFFF : MUTED);
        hits.add(new Hit(tx, ty, 50, 16, () -> {
            m.toggle();
            Store.save();
        }));

        int top = hy + 52;
        int total = m.settings.size() * 26 + (m instanceof HudModule ? 20 : 0);
        int maxScroll = Math.max(0, total - (cy + ch - top));
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        g.enableScissor(cx, top, cx + cw, cy + ch);
        int y = top - (int) scroll;
        for (Setting<?> s : m.settings) {
            boolean rowh = Ui.in(mx, my, cx, y, cw, 22) && my >= top;
            round(g, cx, y, cw, 22, rowh ? CARD_HOVER : CARD);
            g.text(font, s.name, cx + 10, y + 7, TEXT, false);
            int rx = cx + cw - 160;
            if (s instanceof BoolSetting b) {
                int sx = cx + cw - 32;
                round(g, sx, y + 6, 24, 10, b.on() ? ACCENT : OFF);
                int kx = b.on() ? sx + 15 : sx + 2;
                g.fill(kx, y + 7, kx + 7, y + 15, 0xFFFFFFFF);
                if (y >= top) hits.add(new Hit(cx, y, cw, 22, () -> {
                    b.toggle();
                    Store.save();
                }));
            } else if (s instanceof NumberSetting n) {
                int sw = 110;
                g.fill(rx, y + 10, rx + sw, y + 12, OFF);
                int kx = rx + (int) (n.fraction() * sw);
                g.fill(rx, y + 10, kx, y + 12, ACCENT);
                round(g, kx - 3, y + 6, 6, 10, 0xFFFFFFFF);
                g.text(font, n.text(), rx + sw + 10, y + 7, MUTED, false);
                if (y >= top) sliders.add(new Slider(rx - 3, y, sw + 6, 22, n));
            } else if (s instanceof ModeSetting md) {
                int w = 150;
                boolean hov = Ui.in(mx, my, rx, y + 3, w, 16);
                round(g, rx, y + 3, w, 16, hov ? 0xFF34343E : OFF);
                g.text(font, "‹", rx + 6, y + 7, MUTED, false);
                g.text(font, "›", rx + w - 10, y + 7, MUTED, false);
                g.text(font, md.get(), rx + (w - font.width(md.get())) / 2, y + 7, TEXT, false);
                if (y >= top) {
                    hits.add(new Hit(rx, y + 3, w / 2, 16, () -> {
                        md.cycle(-1);
                        Store.save();
                    }));
                    hits.add(new Hit(rx + w / 2, y + 3, w - w / 2, 16, () -> {
                        md.cycle(1);
                        Store.save();
                    }));
                }
            } else if (s instanceof ColorSetting c) {
                int px = cx + cw - 10 - ColorSetting.PALETTE.length * 14;
                for (int col : ColorSetting.PALETTE) {
                    if (col == c.argb()) round(g, px - 1, y + 4, 13, 14, 0xFFFFFFFF);
                    g.fill(px, y + 5, px + 11, y + 17, col);
                    final int ccol = col;
                    if (y >= top) hits.add(new Hit(px, y + 5, 11, 12, () -> {
                        c.set(ccol);
                        Store.save();
                    }));
                    px += 14;
                }
            }
            y += 26;
        }
        if (m instanceof HudModule) {
            scaled(g, "Position: links auf „HUD bearbeiten“ klicken.", cx + 2, y + 4, 0.75f, MUTED, false);
        }
        if (m.settings.isEmpty()) centered(g, "Dieses Modul hat keine Einstellungen.", cx + cw / 2, top + 10, 1f, MUTED);
        g.disableScissor();
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
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.in(mx, my)) {
                    h.action().run();
                    return true;
                }
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
        scrollTarget = Math.max(0, scrollTarget - sy * 30);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == InputConstants.KEY_ESCAPE && open != null) {
            open = null;
            scrollTarget = scroll = 0;
            pageAt = System.currentTimeMillis();
            return true;
        }
        // Menü-Taste (frei belegbar) schließt das Menü wieder – nicht, während man sucht und sie ein Buchstabe ist
        if (PeregrineClient.isMenuKey(e) && (search.isEmpty() || !Character.isLetterOrDigit(k))) {
            onClose();
            return true;
        }
        if (k == InputConstants.KEY_BACKSPACE && open == null && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            scrollTarget = scroll = 0;
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (open == null && e.isAllowedChatCharacter() && search.length() < 24) {
            search += e.codepointAsString();
            scrollTarget = scroll = 0;
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
