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
import java.util.Locale;

/**
 * The in-game mod menu. Same visual language as the launcher: one sheet of "paper", hairlines instead
 * of boxes, text navigation, IBM Plex type, a single accent colour for everything that is switched on.
 */
public final class ClickGui extends Screen {
    private static Category lastCategory; // remembered between openings
    private Category category = lastCategory; // null = all
    private String search = "";
    private Module open;
    private double scroll, scrollTarget;
    private NumberSetting dragging;
    private int dragX, dragW;
    private final long openedAt = System.currentTimeMillis();
    private long pageAt = System.currentTimeMillis();
    private final Theme t = Theme.load();

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
        Modules.CAPE.refresh(); // pick up capes uploaded in the launcher
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
            // no blur available: the dim below is enough
        }
        g.fill(0, 0, width, height, fade(t.light ? 0x66000000 : 0x99000000, anim()));
    }

    private float anim() {
        float x = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private float page() {
        return Math.min(1f, (System.currentTimeMillis() - pageAt) / 140f);
    }

    private static int fade(int argb, float f) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private void hair(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, t.line);
    }

    private static String up(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        sliders.clear();
        scroll += (scrollTarget - scroll) * 0.35;
        if (Math.abs(scrollTarget - scroll) < 0.5) scroll = scrollTarget;

        float a = anim();
        int W = Math.min(600, width - 16), H = Math.min(350, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1f - a) * 10);
        g.fill(x0 + 3, y0 + 4, x0 + W + 3, y0 + H + 4, fade(t.shadow, a));
        g.fill(x0, y0, x0 + W, y0 + H, t.bg | 0xFF000000);

        // ---------- top bar: brand, text navigation, close
        int bar = 24;
        int nx = x0 + 14;
        List<Category> cats = new ArrayList<>();
        cats.add(null);
        cats.addAll(List.of(Category.values()));
        float ns = 0.8f;
        int navW = 0;
        for (Category c : cats) navW += Type.width(c == null ? "ALL" : up(c.label), Type.MONO, ns) + 12;
        // the wordmark only when there is room for it next to the navigation
        if (14 + Type.width("PEREGRINE", Type.BOLD, 1f) + 16 + navW + 24 <= W) {
            Type.left(g, "PEREGRINE", Type.BOLD, nx, y0 + 8, 1f, t.text);
            nx += Type.width("PEREGRINE", Type.BOLD, 1f) + 16;
        }
        for (Category c : cats) {
            String label = c == null ? "ALL" : up(c.label);
            int w = Type.width(label, Type.MONO, ns);
            boolean sel = c == category && open == null || (open != null && c == category);
            boolean hov = Ui.in(mx, my, nx - 4, y0, w + 8, bar);
            Type.left(g, label, Type.MONO, nx, y0 + 9, ns, sel || hov ? t.text : t.muted);
            if (sel) g.fill(nx, y0 + bar - 2, nx + w, y0 + bar, t.accent);
            final Category target = c;
            hits.add(new Hit(nx - 4, y0, w + 8, bar, () -> {
                if (target == Category.COSMETICS && minecraft.player != null) { // the wardrobe has its own screen with a preview
                    Ui.open(new WardrobeScreen(this));
                    return;
                }
                category = target;
                lastCategory = target;
                open = null;
                scrollTarget = scroll = 0;
                pageAt = System.currentTimeMillis();
            }));
            nx += w + 12;
        }
        boolean xh = Ui.in(mx, my, x0 + W - 24, y0, 24, bar);
        Type.center(g, "×", Type.UI, x0 + W - 12, y0 + 6, 1.3f, xh ? t.accent : t.muted);
        hits.add(new Hit(x0 + W - 24, y0, 24, bar, this::onClose));
        hair(g, x0, y0 + bar, W);

        // ---------- footer: primary action + key hints
        int foot = 24, fy = y0 + H - foot;
        hair(g, x0, fy, W);
        String edit = "EDIT HUD LAYOUT";
        int ew = Type.width(edit, Type.MONO, 0.8f) + 18;
        boolean eh = Ui.in(mx, my, x0 + 14, fy + 5, ew, 15);
        g.fill(x0 + 14, fy + 5, x0 + 14 + ew, fy + 20, eh ? t.accent : t.text);
        Type.left(g, edit, Type.MONO, x0 + 23, fy + 9, 0.8f, eh ? t.onAccent : (t.bg | 0xFF000000));
        hits.add(new Hit(x0 + 14, fy + 5, ew, 15, () -> Ui.open(new HudEditor(this))));
        long on = Modules.ALL.stream().filter(m -> m.enabled).count();
        Type.right(g, on + " OF " + Modules.ALL.size() + " ON   ·   ESC TO CLOSE", Type.MONO, x0 + W - 14, fy + 9, 0.8f, t.muted);

        int cx = x0 + 14, cy = y0 + bar + 12, cw = W - 28, ch = fy - cy - 6;
        if (open == null) list(g, mx, my, cx, cy, cw, ch);
        else settings(g, mx, my, cx, cy, cw, ch);
    }

    private static boolean inCat(Category c, Module m) {
        return c == null || m.category == c;
    }

    /** Square switch in the launcher's style. */
    private void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hover) {
        if (on) {
            g.fill(x, y, x + 22, y + 11, t.accent);
            g.fill(x + 13, y + 2, x + 20, y + 9, t.onAccent);
        } else {
            g.fill(x, y, x + 22, y + 11, hover ? t.offHover : t.off);
            g.fill(x + 2, y + 2, x + 9, y + 9, t.muted);
        }
    }

    private void list(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        float p = page();
        // headline + search
        String title = category == null ? "All modules" : category.label;
        Type.left(g, title, Type.DISPLAY, cx, cy - 2 + (1f - p) * 4, 2.1f, t.text);
        int sw = Math.min(150, cw / 3), sx = cx + cw - sw;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        Type.left(g, "SEARCH", Type.MONO, sx, cy - 1, 0.7f, t.muted);
        String shown = search.isEmpty() ? "type to filter" : Type.fit(search, Type.UI, 1f, sw - 8) + (blink ? "|" : "");
        Type.left(g, shown, Type.UI, sx, cy + 7, 1f, search.isEmpty() ? t.muted2 : t.text);
        g.fill(sx, cy + 19, sx + sw, cy + 20, search.isEmpty() ? t.line : t.text);

        List<Module> list = new ArrayList<>();
        String q = search.toLowerCase(Locale.ROOT);
        for (Module m : Modules.ALL) {
            if (!inCat(category, m)) continue;
            if (!q.isEmpty() && !m.name.toLowerCase(Locale.ROOT).contains(q) && !m.description.toLowerCase(Locale.ROOT).contains(q)) continue;
            list.add(m);
        }
        int top = cy + 30, cols = cw >= 380 ? 2 : 1, gap = 22, rowH = 27;
        int colW = (cw - gap * (cols - 1)) / cols;
        int rows = (list.size() + cols - 1) / cols;
        int viewH = cy + ch - top;
        int maxScroll = Math.max(0, rows * rowH - viewH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        hair(g, cx, top - 1, cw);
        g.enableScissor(cx, top, cx + cw, cy + ch);
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            // fill column by column so related modules stay together when reading down
            int col = i / rows, row = i % rows;
            int x = cx + col * (colW + gap), y = top + row * rowH - (int) scroll;
            if (y + rowH < top || y > cy + ch) continue;
            boolean vis = my >= top && my < cy + ch;
            boolean hov = vis && Ui.in(mx, my, x, y, colW, rowH);
            if (hov) g.fill(x, y, x + colW, y + rowH - 1, t.card);
            int textW = colW - 34 - (m.settings.isEmpty() ? 0 : 42);
            Type.left(g, Type.fit(m.name, Type.BOLD, 1f, textW), Type.BOLD, x + 4, y + 4, 1f, m.enabled ? t.text : t.muted);
            Type.left(g, Type.fit(m.description, Type.UI, 0.8f, textW), Type.UI, x + 4, y + 15, 0.8f, m.enabled ? t.muted : t.muted2);
            g.fill(x, y + rowH - 1, x + colW, y + rowH, t.line);
            // row toggles the module
            if (vis) hits.add(new Hit(x - 4, Math.max(y, top), colW + 8, Math.max(0, Math.min(y + rowH, cy + ch) - Math.max(y, top)), () -> {
                m.toggle();
                Store.save();
            }));
            toggle(g, x + colW - 26, y + 8, m.enabled, hov);
            if (!m.settings.isEmpty()) {
                int gx = x + colW - 24 - 44;
                boolean gh = vis && Ui.in(mx, my, gx, y + 5, 40, 17);
                Type.right(g, "SETUP", Type.MONO, gx + 38, y + 10, 0.7f, gh ? t.accent : t.muted);
                if (gh) g.fill(gx + 38 - Type.width("SETUP", Type.MONO, 0.7f), y + 18, gx + 38, y + 19, t.accent);
                if (vis && y + 5 >= top) hits.add(new Hit(gx, y + 5, 40, 17, () -> {
                    open = m;
                    scrollTarget = scroll = 0;
                    pageAt = System.currentTimeMillis();
                }));
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int barH = Math.max(16, viewH * viewH / (viewH + maxScroll));
            int barY = top + (int) ((viewH - barH) * (scroll / maxScroll));
            g.fill(cx + cw + 5, barY, cx + cw + 7, barY + barH, t.muted2);
        }
        if (list.isEmpty()) Type.left(g, "Nothing matches “" + search + "”.", Type.UI, cx, top + 10, 1f, t.muted);
    }

    private void settings(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        Module m = open;
        float p = page();
        int shift = Math.round((1f - p) * 8);
        // back link + reset
        boolean bh = Ui.in(mx, my, cx, cy - 4, 60, 12);
        Type.left(g, "← BACK", Type.MONO, cx, cy - 2, 0.75f, bh ? t.accent : t.muted);
        hits.add(new Hit(cx, cy - 6, 60, 14, () -> {
            open = null;
            scrollTarget = scroll = 0;
            pageAt = System.currentTimeMillis();
        }));
        int rw = Type.width("RESET TO DEFAULTS", Type.MONO, 0.75f);
        boolean rh = Ui.in(mx, my, cx + cw - rw, cy - 6, rw, 14);
        Type.right(g, "RESET TO DEFAULTS", Type.MONO, cx + cw, cy - 2, 0.75f, rh ? t.accent : t.muted);
        hits.add(new Hit(cx + cw - rw, cy - 6, rw, 14, () -> {
            m.reset();
            Store.save();
        }));

        // headline with the main switch
        int hy = cy + 10;
        Type.left(g, m.name, Type.DISPLAY, cx + shift, hy, 2.1f, t.text);
        Type.left(g, Type.fit(m.description, Type.UI, 1f, cw - 70), Type.UI, cx + shift, hy + 22, 1f, t.muted);
        boolean th = Ui.in(mx, my, cx + cw - 60, hy + 2, 60, 18);
        Type.right(g, m.enabled ? "ON" : "OFF", Type.MONO, cx + cw - 28, hy + 8, 0.8f, m.enabled ? t.accent : t.muted);
        toggle(g, cx + cw - 22, hy + 6, m.enabled, th);
        hits.add(new Hit(cx + cw - 60, hy + 2, 60, 18, () -> {
            m.toggle();
            Store.save();
        }));

        int top = hy + 40, rowH = 24;
        int total = m.settings.size() * rowH + 16;
        int maxScroll = Math.max(0, total - (cy + ch - top));
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        hair(g, cx, top - 1, cw);
        g.enableScissor(cx, top, cx + cw, cy + ch);
        int y = top - (int) scroll;
        for (Setting<?> s : m.settings) {
            boolean vis = y >= top - 2 && my >= top;
            boolean rowHov = vis && Ui.in(mx, my, cx, y, cw, rowH);
            if (rowHov) g.fill(cx - 4, y, cx + cw + 4, y + rowH - 1, t.card);
            Type.left(g, s.name, Type.UI, cx + 2, y + 8, 1f, t.text);
            g.fill(cx, y + rowH - 1, cx + cw, y + rowH, t.line);
            int rx = cx + cw - 170;
            if (s instanceof BoolSetting b) {
                toggle(g, cx + cw - 22, y + 6, b.on(), rowHov);
                if (vis) hits.add(new Hit(cx - 4, y, cw + 8, rowH, () -> {
                    b.toggle();
                    Store.save();
                }));
            } else if (s instanceof NumberSetting n) {
                int sw = 120;
                g.fill(rx, y + 11, rx + sw, y + 12, t.muted2);
                int kx = rx + (int) (n.fraction() * sw);
                g.fill(rx, y + 10, kx, y + 13, t.accent);
                g.fill(kx - 2, y + 6, kx + 2, y + 17, t.text);
                Type.right(g, n.text(), Type.MONO, cx + cw, y + 9, 0.8f, t.text);
                if (vis) sliders.add(new Slider(rx - 3, y, sw + 6, rowH, n));
            } else if (s instanceof ModeSetting md) {
                int w = 170;
                boolean lh = vis && Ui.in(mx, my, rx, y, 16, rowH), rh2 = vis && Ui.in(mx, my, rx + w - 16, y, 16, rowH);
                Type.center(g, "‹", Type.UI, rx + 6, y + 6, 1.2f, lh ? t.accent : t.muted);
                Type.center(g, "›", Type.UI, rx + w - 6, y + 6, 1.2f, rh2 ? t.accent : t.muted);
                Type.center(g, Type.fit(md.get(), Type.BOLD, 1f, w - 34), Type.BOLD, rx + w / 2f, y + 8, 1f, t.text);
                if (vis) {
                    hits.add(new Hit(rx, y, w / 2, rowH, () -> {
                        md.cycle(-1);
                        Store.save();
                    }));
                    hits.add(new Hit(rx + w / 2, y, w - w / 2, rowH, () -> {
                        md.cycle(1);
                        Store.save();
                    }));
                }
            } else if (s instanceof ColorSetting c) {
                int px = cx + cw - ColorSetting.PALETTE.length * 14 + 3;
                for (int col : ColorSetting.PALETTE) {
                    if (col == c.argb()) g.fill(px - 2, y + 4, px + 13, y + 19, t.text);
                    g.fill(px, y + 6, px + 11, y + 17, col);
                    final int chosen = col;
                    if (vis) hits.add(new Hit(px - 1, y + 5, 13, 13, () -> {
                        c.set(chosen);
                        Store.save();
                    }));
                    px += 14;
                }
            }
            y += rowH;
        }
        if (m instanceof HudModule) {
            Type.left(g, "Move it on screen with “Edit HUD layout” below.", Type.UI, cx + 2, y + 6, 0.8f, t.muted);
        } else if (m.settings.isEmpty()) {
            Type.left(g, "This module has no options.", Type.UI, cx + 2, y + 6, 0.8f, t.muted);
        }
        g.disableScissor();
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) { // 0 up to 26.2, 1 from 26.3 (SDL)
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
        scrollTarget = Math.max(0, scrollTarget - sy * 27);
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
        // the (rebindable) menu key closes the menu again, unless it is a letter typed into the search
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
