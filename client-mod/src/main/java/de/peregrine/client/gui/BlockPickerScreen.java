package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Store;
import de.peregrine.client.feature.BlockEsp;
import de.peregrine.client.setting.ColorSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Block ESP settings: search every block in the game (autocomplete), pick as many as you like, choose colour,
 * opacity and outline – with a live preview. Same look as the mod menu.
 */
public final class BlockPickerScreen extends Screen {
    private final Screen parent;
    private final BlockEsp esp;
    private final Theme t = Theme.load();
    private String query = "";
    private double scroll, scrollTarget;
    private NumberSetting dragging;
    private int dragX, dragW;
    private final long openedAt = System.currentTimeMillis();

    private record Entry(String id, String name, String search, Block block, ItemStack icon) {
    }

    private static List<Entry> all;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Slider(float x, float y, float w, float h, NumberSetting s) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();

    public BlockPickerScreen(Screen parent, BlockEsp esp) {
        super(Component.literal("Block ESP"));
        this.parent = parent;
        this.esp = esp;
        if (all == null) {
            List<Entry> l = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                if (b.defaultBlockState().isAir()) continue;
                String id = BuiltInRegistries.BLOCK.getKey(b).toString();
                String name = b.getName().getString();
                ItemStack icon = b.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(b.asItem());
                l.add(new Entry(id, name, (name + " " + id).toLowerCase(Locale.ROOT), b, icon));
            }
            l.sort((a, c) -> a.name.compareToIgnoreCase(c.name));
            all = l;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Store.save();
        Ui.open(parent);
    }

    private List<Entry> results() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Entry e : all) {
            if (q.isEmpty()) {
                (esp.blocks.contains(e.id) ? starts : contains).add(e);
                continue;
            }
            String n = e.name.toLowerCase(Locale.ROOT);
            if (n.startsWith(q) || e.id.startsWith("minecraft:" + q) || n.contains(" " + q)) starts.add(e);
            else if (e.search.contains(q)) contains.add(e);
        }
        starts.addAll(contains);
        return starts;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, t.light ? 0x66000000 : 0x99000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        sliders.clear();
        scroll += (scrollTarget - scroll) * 0.35;
        float a = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        int W = Math.min(600, width - 16), H = Math.min(360, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1 - a) * 8);
        Ui.glow(g, width / 2, height / 2, W + 240, H + 180, 0x20000000 | (t.accent & 0xFFFFFF));
        for (int i = 3; i >= 1; i--) Px.roundRect(g, x0 - i * 2, y0 - i * 2 + 4, W + i * 4, H + i * 4, 8 + i * 2, 0x22000000);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x1C000000 | (t.text & 0xFFFFFF));
        int ink = 0xFF000000 | (t.text & 0xFFFFFF), acc = 0xFF000000 | (t.accent & 0xFFFFFF);

        // header
        int cx = x0 + 16;
        Type.spaced(g, "RENDER", Type.MONO, cx, y0 + 12, 0.6f, t.muted, 1.6f);
        Type.left(g, "Block ESP", Type.DISPLAY, cx, y0 + 21, 2.1f, t.text);
        boolean xh = Ui.in(mx, my, x0 + W - 24, y0, 24, 22);
        Type.center(g, "×", Type.UI, x0 + W - 12, y0 + 5, 1.3f, xh ? t.accent : t.muted);
        hits.add(new Hit(x0 + W - 24, y0, 24, 22, this::onClose));
        // on/off switch
        boolean sh = Ui.in(mx, my, x0 + W - 92, y0 + 26, 60, 14);
        Type.right(g, esp.enabled ? "ON" : "OFF", Type.MONO, x0 + W - 66, y0 + 29, 0.7f, esp.enabled ? t.accent : t.muted);
        Ui.pill(g, x0 + W - 58, y0 + 28, esp.enabled ? 1f : 0f, sh, t);
        hits.add(new Hit(x0 + W - 92, y0 + 24, 66, 18, () -> {
            esp.toggle();
            Store.save();
        }));

        // ---------- left: search + results
        int lx = cx, lw = Math.round(W * 0.56f), top = y0 + 52;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        Px.roundRect(g, lx, top, lw, 18, 4, t.card);
        Px.roundStroke(g, lx, top, lw, 18, 4, query.isEmpty() ? (0x22000000 | (t.text & 0xFFFFFF)) : acc);
        Type.left(g, query.isEmpty() ? "Search all blocks – e.g. diamond, chest, spawner" : query + (blink ? "|" : ""), Type.UI, lx + 7, top + 5, 0.85f, query.isEmpty() ? t.muted2 : t.text);
        List<Entry> res = results();
        int listTop = top + 24, listBot = y0 + H - 14, rowH = 18;
        int maxScroll = Math.max(0, res.size() * rowH - (listBot - listTop));
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        g.enableScissor(lx - 2, listTop, lx + lw + 2, listBot);
        int y = listTop - (int) scroll;
        int first = Math.max(0, (int) (scroll / rowH) - 1);
        for (int i = first; i < res.size(); i++) {
            int ry = listTop + i * rowH - (int) scroll;
            if (ry > listBot) break;
            Entry e = res.get(i);
            boolean sel = esp.blocks.contains(e.id);
            boolean hov = my >= listTop && my < listBot && Ui.in(mx, my, lx, ry, lw, rowH);
            if (hov || sel) Px.roundRect(g, lx, ry + 1, lw, rowH - 2, 3, hov ? t.cardHover : t.card);
            if (sel) Px.roundRect(g, lx + 1, ry + 4, 1.6f, rowH - 8, 0.8f, acc);
            if (!e.icon.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(lx + 5, ry + 2);
                g.pose().scale(0.875f, 0.875f);
                g.item(e.icon, 0, 0);
                g.pose().popMatrix();
            }
            Type.left(g, Type.fit(e.name, Type.BOLD, 0.85f, lw - 120), Type.BOLD, lx + 24, ry + 5, 0.85f, sel ? t.text : t.muted);
            Type.right(g, Type.fit(e.id.replace("minecraft:", ""), Type.MONO, 0.55f, 90), Type.MONO, lx + lw - 22, ry + 7, 0.55f, t.muted2);
            if (sel) Ui.diamond(g, lx + lw - 10, ry + 9, 2, acc);
            else if (hov) Type.center(g, "+", Type.BOLD, lx + lw - 10, ry + 5, 0.9f, t.accent);
            if (hov) hits.add(new Hit(lx, Math.max(ry, listTop), lw, rowH, () -> {
                esp.blocks.toggle(e.id);
                Store.save();
            }));
        }
        g.disableScissor();
        if (res.isEmpty()) Type.left(g, "No block called “" + query + "”.", Type.UI, lx, listTop + 6, 0.9f, t.muted);

        // ---------- right: preview, colour, sliders, selected
        int rx = lx + lw + 18, rw = x0 + W - 16 - rx;
        Type.spaced(g, "PREVIEW", Type.MONO, rx, top, 0.6f, t.muted, 1.6f);
        float pcx = rx + rw / 2f, pcy = top + 33;
        int col = esp.color.argb();
        int glowA = (int) Math.round(esp.opacity.d() / 100.0 * 0xC0);
        if (glowA > 0) Ui.glow(g, Math.round(pcx), Math.round(pcy), 52, 52, (glowA << 24) | (col & 0xFFFFFF));
        // a small isometric cube in the chosen style
        float sz = 11, w = (float) esp.outline.d();
        float[][] v = {{0, -sz}, {sz * 0.87f, -sz / 2}, {0, 0}, {-sz * 0.87f, -sz / 2}, {0, sz}, {sz * 0.87f, sz / 2}, {-sz * 0.87f, sz / 2}};
        int lc = 0xFF000000 | (col & 0xFFFFFF);
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {2, 4}, {1, 5}, {3, 6}, {5, 4}, {6, 4}};
        for (int[] ed : edges) Px.line(g, pcx + v[ed[0]][0], pcy + v[ed[0]][1], pcx + v[ed[1]][0], pcy + v[ed[1]][1], w, lc);
        if (esp.labels.on()) {
            String lab = "Diamond Ore  12 m";
            int tw = Type.width(lab, Type.BOLD, 0.6f);
            Px.roundRect(g, pcx - tw / 2f - 3, pcy - sz - 13, tw + 6, 9, 3, 0xB0101216);
            Type.left(g, lab, Type.BOLD, pcx - tw / 2f, pcy - sz - 11.5f, 0.6f, 0xFFFFFFFF);
        }
        int yy = top + 50;
        // colour palette
        Type.spaced(g, "COLOR", Type.MONO, rx, yy, 0.6f, t.muted, 1.6f);
        yy += 10;
        float px = rx + 5;
        for (int c : ColorSetting.PALETTE) {
            if (px > rx + rw - 5) {
                px = rx + 5;
                yy += 14;
            }
            boolean on = (c & 0xFFFFFF) == (col & 0xFFFFFF);
            if (on) Px.circle(g, px, yy + 6, 6.2f, ink);
            Px.circle(g, px, yy + 6, 4.8f, c | 0xFF000000);
            final int chosen = c;
            hits.add(new Hit(px - 6, yy, 12, 12, () -> {
                esp.color.set(chosen);
                Store.save();
            }));
            px += 14;
        }
        yy += 18;
        for (NumberSetting n : new NumberSetting[]{esp.opacity, esp.outline}) {
            Type.left(g, n.name, Type.UI, rx, yy, 0.75f, t.text);
            Type.right(g, n.text(), Type.MONO, rx + rw, yy + 1, 0.6f, t.muted);
            float sx = rx, sw = rw;
            float kx = sx + (float) (n.fraction() * sw);
            Px.roundRect(g, sx, yy + 11, sw, 2, 1, 0x30000000 | (t.text & 0xFFFFFF));
            Px.roundRect(g, sx, yy + 11, kx - sx, 2, 1, acc);
            Px.circle(g, kx, yy + 12, 3.4f, ink);
            sliders.add(new Slider(sx - 3, yy + 6, sw + 6, 12, n));
            yy += 17;
        }
        // labels toggle
        boolean lh = Ui.in(mx, my, rx, yy, rw, 12);
        Type.left(g, "Name and distance", Type.UI, rx, yy + 2, 0.75f, t.text);
        Ui.pill(g, rx + rw - 20, yy + 1, esp.labels.on() ? 1f : 0f, lh, t);
        hits.add(new Hit(rx, yy, rw, 12, () -> {
            esp.labels.toggle();
            Store.save();
        }));
        yy += 16;
        // selected list
        Type.spaced(g, "SELECTED  " + esp.blocks.get().size(), Type.MONO, rx, yy, 0.6f, t.muted, 1.6f);
        Type.right(g, esp.enabled ? esp.matches() + " FOUND" : "", Type.MONO, rx + rw, yy, 0.55f, t.accent);
        yy += 10;
        for (String id : new ArrayList<>(esp.blocks.get())) {
            if (yy > y0 + H - 22) {
                Type.left(g, "…", Type.UI, rx, yy, 0.8f, t.muted);
                break;
            }
            Entry e = all.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
            String name = e == null ? id : e.name;
            boolean ch = Ui.in(mx, my, rx, yy, rw, 12);
            Px.roundRect(g, rx, yy, rw, 12, 6, ch ? t.cardHover : t.card);
            Type.left(g, Type.fit(name, Type.UI, 0.7f, rw - 20), Type.UI, rx + 6, yy + 3, 0.7f, t.text);
            Type.center(g, "×", Type.UI, rx + rw - 7, yy + 1, 0.9f, ch ? t.accent : t.muted);
            hits.add(new Hit(rx, yy, rw, 12, () -> {
                esp.blocks.toggle(id);
                Store.save();
            }));
            yy += 14;
        }
        if (!esp.allowed(minecraft)) Type.left(g, "Only in singleplayer and on servers where you are operator.", Type.UI, cx, y0 + H - 11, 0.6f, 0xFFF0B428);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Slider s : sliders) {
                if (e.x() >= s.x() && e.x() < s.x() + s.w() && e.y() >= s.y() && e.y() < s.y() + s.h()) {
                    dragging = s.s();
                    dragX = Math.round(s.x() + 3);
                    dragW = Math.round(s.w() - 6);
                    dragging.setFraction((e.x() - dragX) / dragW);
                    return true;
                }
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).in(e.x(), e.y())) {
                    hits.get(i).action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, dbl);
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
        scrollTarget = Math.max(0, scrollTarget - sy * 36);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == InputConstants.KEY_BACKSPACE && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            scrollTarget = scroll = 0;
            return true;
        }
        if (e.key() == InputConstants.KEY_RETURN && !query.isEmpty()) { // Enter adds the best match
            List<Entry> r = results();
            if (!r.isEmpty() && !esp.blocks.contains(r.get(0).id)) {
                esp.blocks.toggle(r.get(0).id);
                Store.save();
            }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (e.isAllowedChatCharacter() && query.length() < 32) {
            query += e.codepointAsString();
            scrollTarget = scroll = 0;
            return true;
        }
        return super.charTyped(e);
    }
}
