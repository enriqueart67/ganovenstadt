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
 * The in-game mod menu, in the launcher's "Stoop" language: altitude-tape navigation with a gliding pointer,
 * the falcon mark, letter-spaced mono labels, module cards with pill switches and one icy accent colour.
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
        int W = Math.min(700, width - 16), H = Math.min(390, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1f - a) * 10);
        long now = System.currentTimeMillis();
        // atmosphere: a slowly breathing icy light behind the sheet
        float breathe = 0.5f + 0.5f * (float) Math.sin(now / 2200.0);
        Ui.glow(g, width / 2, height / 2, W + 260, H + 200, ((int) ((0x26 + 0x14 * breathe) * a) << 24) | (t.accent & 0xFFFFFF));
        // soft shadow, then the sheet with round corners and a fine top highlight
        for (int i = 3; i >= 1; i--) Px.roundRect(g, x0 - i * 2, y0 - i * 2 + 4, W + i * 4, H + i * 4, 8 + i * 2, fade(0x22000000, a));
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Ui.glow(g, x0 + W / 2, y0 + H / 2, W, H, ((int) (0x14 * a) << 24) | (t.accent & 0xFFFFFF));
        if (t.chart) Ui.chart(g, x0, y0, W, H, t.light);
        Px.roundStroke(g, x0, y0, W, H, 7, fade(0x1C000000 | (t.text & 0xFFFFFF), a));
        Px.hline(g, x0 + 10, x0 + W - 10, y0 + 0.4f, fade(0x30000000 | (t.text & 0xFFFFFF), a));

        // ---------- left rail: mark, wordmark and the altitude tape navigation
        int railW = 132, tape = x0 + 20;
        Ui.falcon(g, x0 + 20, y0 + 17, 13, 0xFF000000 | (t.text & 0xFFFFFF));
        Type.spaced(g, "PEREGRINE", Type.MONO, x0 + 32, y0 + 13, 0.72f, t.text, 1.6f);
        Px.vline(g, x0 + railW, y0 + 12, y0 + H - 12, 0x1E000000 | (t.text & 0xFFFFFF));
        int tTop = y0 + 34, tBot = y0 + H - 34;
        Px.vline(g, tape, tTop, tBot, 0x48000000 | (t.text & 0xFFFFFF));
        for (int ty = tTop, k = 0; ty <= tBot; ty += 4, k++) {
            boolean lng = k % 5 == 0;
            Px.hline(g, tape - (lng ? 5 : 2.5f), tape, ty, (lng ? 0x58000000 : 0x26000000) | (t.text & 0xFFFFFF));
        }
        List<Category> cats = new ArrayList<>();
        cats.add(null);
        cats.addAll(List.of(Category.values()));
        int rowGap = Math.max(14, Math.min(22, (tBot - tTop - 16) / cats.size()));
        int ny = tTop + 10;
        Integer pointerY = null;
        for (Category c : cats) {
            String label = c == null ? "ALL" : up(c.label);
            boolean sel = c == category;
            boolean hov = Ui.in(mx, my, x0, ny - rowGap / 2, railW, rowGap);
            if (sel) pointerY = ny;
            Px.hline(g, tape, tape + (hov ? 9 : 6), ny + 0.5f, (sel ? 0xC0000000 : hov ? 0x90000000 : 0x50000000) | (t.text & 0xFFFFFF));
            Type.spaced(g, label, Type.MONO, tape + 14 + (hov ? 2 : 0), ny - 2, 0.66f, sel ? t.text : hov ? t.text : t.muted, 1.3f);
            final Category target = c;
            hits.add(new Hit(x0, ny - rowGap / 2, railW, rowGap, () -> {
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
            ny += rowGap;
        }
        // pointer glides to the active category
        if (pointerY != null) {
            if (pointerAnim < 0) pointerAnim = pointerY;
            pointerAnim += (pointerY - pointerAnim) * 0.3f;
            int py = Math.round(pointerAnim);
            Ui.glow(g, tape + 4, py, 40, 16, 0x50000000 | (t.accent & 0xFFFFFF));
            int ac = 0xFF000000 | (t.accent & 0xFFFFFF);
            Px.hline(g, tape - 7, tape + 2, py + 0.5f, ac);
            Px.chevronLeft(g, tape + 2.5f, py + 0.5f, 5.5f, 3.2f, ac); // ◀ pointer
        }
        // bottom of the rail: count of modules on
        long on = Modules.ALL.stream().filter(m -> m.enabled).count();
        Ui.diamond(g, tape, y0 + H - 18, 2, 0xFF000000 | (t.accent & 0xFFFFFF));
        Type.left(g, on + " / " + Modules.ALL.size() + " ON", Type.MONO, tape + 8, y0 + H - 21, 0.62f, t.muted);

        // close
        boolean xh = Ui.in(mx, my, x0 + W - 24, y0, 24, 22);
        Type.center(g, "×", Type.UI, x0 + W - 12, y0 + 5, 1.3f, xh ? t.accent : t.muted);
        hits.add(new Hit(x0 + W - 24, y0, 24, 22, this::onClose));

        // ---------- footer: primary + secondary action
        int cx = x0 + railW + 18, cw = W - railW - 32;
        int fy = y0 + H - 26;
        Px.hline(g, cx, cx + cw, fy, 0x1E000000 | (t.text & 0xFFFFFF));
        String edit = "EDIT HUD LAYOUT";
        int ew = Type.spacedWidth(edit, Type.MONO, 0.7f, 1.2f) + 20;
        boolean eh = Ui.in(mx, my, cx, fy + 6, ew, 15);
        Px.roundRect(g, cx, fy + 6, ew, 15, 3, eh ? (0xFF000000 | (t.accent & 0xFFFFFF)) : (0xFF000000 | (t.text & 0xFFFFFF)));
        Type.spaced(g, edit, Type.MONO, cx + 10, fy + 10, 0.7f, 0xFF000000 | (t.bg & 0xFFFFFF), 1.2f);
        hits.add(new Hit(cx, fy + 6, ew, 15, () -> Ui.open(new HudEditor(this))));
        if (minecraft.player != null) { // the assistant also opens from here, in case its key is taken
            String ai = "AI ASSISTANT";
            int aw = Type.spacedWidth(ai, Type.MONO, 0.7f, 1.2f) + 20, ax = cx + ew + 8;
            boolean ah = Ui.in(mx, my, ax, fy + 6, aw, 15);
            int bc = ah ? (0xFF000000 | (t.accent & 0xFFFFFF)) : (0x50000000 | (t.text & 0xFFFFFF));
            Px.roundStroke(g, ax, fy + 6, aw, 15, 3, bc);
            Type.spaced(g, ai, Type.MONO, ax + 10, fy + 10, 0.7f, ah ? t.accent : t.text, 1.2f);
            hits.add(new Hit(ax, fy + 6, aw, 15, () -> Ui.open(new AssistantScreen())));
        }
        int used = ew + 8 + (minecraft.player != null ? Type.spacedWidth("AI ASSISTANT", Type.MONO, 0.7f, 1.2f) + 20 : 0);
        if (used + Type.width("ESC TO CLOSE", Type.MONO, 0.62f) + 16 < cw) Type.right(g, "ESC TO CLOSE", Type.MONO, cx + cw, fy + 10, 0.62f, t.muted2);

        int cy = y0 + 22, ch = fy - cy - 6;
        if (open == null) list(g, mx, my, cx, cy, cw, ch);
        else settings(g, mx, my, cx, cy, cw, ch);
    }

    private float pointerAnim = -1;
    private final java.util.Map<Object, Float> switchAnim = new java.util.HashMap<>();

    private static boolean inCat(Category c, Module m) {
        return c == null || m.category == c;
    }

    /** Pill switch in the launcher's style, animated per owner. */
    private void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hover) {
        toggle(g, x, y, on, hover, new Object[]{x, y});
    }

    private void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hover, Object key) {
        Object k = key instanceof Object[] arr ? java.util.Arrays.asList(arr) : key;
        float v = switchAnim.getOrDefault(k, on ? 1f : 0f);
        v += ((on ? 1f : 0f) - v) * 0.35f;
        switchAnim.put(k, v);
        Ui.pill(g, x, y, v, hover, t);
    }

    private void list(GuiGraphicsExtractor g, int mx, int my, int cx, int cy, int cw, int ch) {
        float p = page();
        // headline + search
        String title = category == null ? "All modules" : category.label;
        Type.spaced(g, category == null ? "LIBRARY" : "CATEGORY", Type.MONO, cx, cy - 4 + (1f - p) * 4, 0.6f, t.muted, 1.6f);
        Type.left(g, title, Type.DISPLAY, cx, cy + 5 + (1f - p) * 4, 2.1f, t.text);
        int sw = Math.min(150, cw / 3), sx = cx + cw - sw;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        Type.spaced(g, "SEARCH", Type.MONO, sx, cy + 2, 0.6f, t.muted, 1.4f);
        String shown = search.isEmpty() ? "type to filter" : Type.fit(search, Type.UI, 1f, sw - 8) + (blink ? "|" : "");
        Type.left(g, shown, Type.UI, sx, cy + 11, 1f, search.isEmpty() ? t.muted2 : t.text);
        Px.hline(g, sx, sx + sw, cy + 23, search.isEmpty() ? (0x30000000 | (t.text & 0xFFFFFF)) : (0xFF000000 | (t.accent & 0xFFFFFF)));

        List<Module> list = new ArrayList<>();
        String q = search.toLowerCase(Locale.ROOT);
        for (Module m : Modules.ALL) {
            if (!inCat(category, m)) continue;
            if (!q.isEmpty() && !m.name.toLowerCase(Locale.ROOT).contains(q) && !m.description.toLowerCase(Locale.ROOT).contains(q)) continue;
            list.add(m);
        }
        // module cards, like the launcher's library
        int top = cy + 36, gap = 6;
        int cols = cw >= 470 ? 3 : cw >= 300 ? 2 : 1;
        boolean compact = cols == 1;
        int cardW = (cw - gap * (cols - 1)) / cols, cardH = compact ? 30 : 46;
        int rows = (list.size() + cols - 1) / cols;
        int viewH = cy + ch - top;
        int maxScroll = Math.max(0, rows * (cardH + gap) - viewH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        g.enableScissor(cx - 2, top, cx + cw + 2, cy + ch);
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            int col = i % cols, row = i / cols;
            int x = cx + col * (cardW + gap), y = top + row * (cardH + gap) - (int) scroll;
            if (y + cardH < top || y > cy + ch) continue;
            boolean vis = my >= top && my < cy + ch;
            boolean hov = vis && Ui.in(mx, my, x, y, cardW, cardH);
            int lift = hov ? 1 : 0;
            int yy = y - lift;
            Px.roundRect(g, x, yy, cardW, cardH, 4, hov ? t.cardHover : t.card);
            Px.roundStroke(g, x, yy, cardW, cardH, 4, hov ? (0x90000000 | (t.accent & 0xFFFFFF)) : (0x16000000 | (t.text & 0xFFFFFF)));
            if (m.enabled) Px.roundRect(g, x + 1.5f, yy + 8, 1.6f, cardH - 16, 0.8f, 0xFF000000 | (t.accent & 0xFFFFFF));
            int textW = cardW - 40;
            if (compact) {
                Type.left(g, Type.fit(m.name, Type.BOLD, 1f, textW - 50), Type.BOLD, x + 9, yy + 5, 1f, m.enabled ? t.text : t.muted);
                Type.left(g, Type.fit(m.description, Type.UI, 0.7f, textW - 50), Type.UI, x + 9, yy + 17, 0.7f, t.muted2);
                toggle(g, x + cardW - 28, yy + 10, m.enabled, hov, m);
            } else {
                Type.spaced(g, Type.fit(up(m.category.label), Type.MONO, 0.55f, textW), Type.MONO, x + 9, yy + 6, 0.55f, m.enabled ? t.accent : t.muted2, 1.2f);
                Type.left(g, Type.fit(m.name, Type.BOLD, 1f, textW), Type.BOLD, x + 9, yy + 15, 1f, m.enabled ? t.text : t.muted);
                Type.left(g, Type.fit(m.description, Type.UI, 0.72f, cardW - 18 - (m.settings.isEmpty() || compact ? 0 : 40)), Type.UI, x + 9, yy + 29, 0.72f, t.muted2); // room for SETUP
                toggle(g, x + cardW - 28, yy + 7, m.enabled, hov, m);
            }
            if (vis) hits.add(new Hit(x, Math.max(yy, top), cardW, Math.max(0, Math.min(yy + cardH, cy + ch) - Math.max(yy, top)), () -> {
                m.toggle();
                Store.save();
            }));
            if (!m.settings.isEmpty()) {
                int gx = compact ? x + cardW - 76 : x + cardW - 44, gy = compact ? yy + 11 : yy + cardH - 13;
                boolean gh = vis && Ui.in(mx, my, gx - 4, gy - 3, 42, 12);
                Type.right(g, "SETUP", Type.MONO, gx + 34, gy, 0.6f, gh ? t.accent : t.muted);
                if (vis && gy >= top) hits.add(new Hit(gx - 4, gy - 3, 42, 12, () -> {
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
            Px.roundRect(g, cx + cw + 4.5f, barY, 1.4f, barH, 0.7f, 0xFF000000 | (t.accent & 0xFFFFFF));
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
        Px.hline(g, cx, cx + cw, top - 1, 0x1E000000 | (t.text & 0xFFFFFF));
        g.enableScissor(cx, top, cx + cw, cy + ch);
        int y = top - (int) scroll;
        for (Setting<?> s : m.settings) {
            boolean vis = y >= top - 2 && my >= top;
            boolean rowHov = vis && Ui.in(mx, my, cx, y, cw, rowH);
            if (rowHov) Px.roundRect(g, cx - 4, y + 1, cw + 8, rowH - 2, 3, t.card);
            Type.left(g, s.name, Type.UI, cx + 2, y + 8, 1f, t.text);
            Px.hline(g, cx, cx + cw, y + rowH - 0.5f, 0x16000000 | (t.text & 0xFFFFFF));
            int rx = cx + cw - 170;
            if (s instanceof BoolSetting b) {
                toggle(g, cx + cw - 22, y + 6, b.on(), rowHov);
                if (vis) hits.add(new Hit(cx - 4, y, cw + 8, rowH, () -> {
                    b.toggle();
                    Store.save();
                }));
            } else if (s instanceof NumberSetting n) {
                int sw = 120;
                float kx = rx + (float) (n.fraction() * sw);
                Px.roundRect(g, rx, y + 10.5f, sw, 2, 1, 0x30000000 | (t.text & 0xFFFFFF));
                Px.roundRect(g, rx, y + 10.5f, kx - rx, 2, 1, 0xFF000000 | (t.accent & 0xFFFFFF));
                Ui.glow(g, (int) kx, y + 11, 18, 14, 0x40000000 | (t.accent & 0xFFFFFF));
                Px.circle(g, kx, y + 11.5f, 3.6f, 0xFF000000 | (t.text & 0xFFFFFF));
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
            } else if (s instanceof de.peregrine.client.setting.ListSetting ls) {
                // e.g. the blocks of Block ESP: count + a button for the module's own editor
                Screen ed = m.editor(this);
                String lbl = "CHOOSE";
                int bw = Type.spacedWidth(lbl, Type.MONO, 0.65f, 1.2f) + 16, bx = cx + cw - bw;
                boolean bh2 = vis && Ui.in(mx, my, bx, y + 5, bw, 13);
                Type.right(g, ls.get().size() + " SELECTED", Type.MONO, bx - 10, y + 9, 0.7f, t.muted);
                Px.roundRect(g, bx, y + 5, bw, 13, 3, bh2 ? (0xFF000000 | (t.accent & 0xFFFFFF)) : (0xFF000000 | (t.text & 0xFFFFFF)));
                Type.spaced(g, lbl, Type.MONO, bx + 8, y + 8, 0.65f, 0xFF000000 | (t.bg & 0xFFFFFF), 1.2f);
                if (vis && ed != null) hits.add(new Hit(cx - 4, y, cw + 8, rowH, () -> Ui.open(ed)));
            } else if (s instanceof ColorSetting c) {
                int px = cx + cw - ColorSetting.PALETTE.length * 14 + 3;
                for (int col : ColorSetting.PALETTE) {
                    if (col == c.argb()) Px.circle(g, px + 5.5f, y + 11.5f, 7f, 0xFF000000 | (t.text & 0xFFFFFF));
                    Px.circle(g, px + 5.5f, y + 11.5f, 5.2f, col | 0xFF000000);
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
        // the (rebindable) menu key closes the menu again, unless it is a letter typed into the search
        if (PeregrineClient.isMenuKey(e) && (search.isEmpty() || !Character.isLetterOrDigit(k))) {
            onClose();
            return true;
        }
        if (TextInput.key(this, e)) return true;
        if (k == InputConstants.KEY_BACKSPACE && open == null && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            scrollTarget = scroll = 0;
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (TextInput.echo(e)) return true; // already typed by its key press
        if (open == null && e.isAllowedChatCharacter() && search.length() < 24) {
            search += e.codepointAsString();
            scrollTarget = scroll = 0;
            return true;
        }
        return super.charTyped(e);
    }

    @Override
    public void removed() {
        TextInput.focus(this, false);
        Store.save();
        super.removed();
    }

    @Override
    protected void init() {
        super.init();
        TextInput.focus(this, true); // a text field has focus: Minecraft switches the window to text input
    }
}
