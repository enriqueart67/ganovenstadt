package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Store;
import de.peregrine.client.feature.Access;
import de.peregrine.client.feature.EntityEsp;
import de.peregrine.client.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Entity ESP picker: search every mob, animal and other entity in the game, plus the players around you (or any
 * name you type), and highlight exactly those in their own colour. Same look as the Block ESP picker.
 */
public final class EntityPickerScreen extends Screen {
    private final Screen parent;
    private final EntityEsp esp;
    private final Theme t = Theme.load();
    private String query = "";
    private double scroll, scrollTarget;
    private final long openedAt = System.currentTimeMillis();

    private record Entry(String key, String name, String search, ItemStack icon, boolean player) {
    }

    private static List<Entry> types;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();

    public EntityPickerScreen(Screen parent, EntityEsp esp) {
        super(Component.literal("Entity ESP"));
        this.parent = parent;
        this.esp = esp;
        if (types == null) {
            List<Entry> l = new ArrayList<>();
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (id.getPath().equals("player")) continue; // players are picked by name
                String name = type.getDescription().getString();
                Item egg = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_spawn_egg"));
                ItemStack icon = egg == null || egg == Items.AIR ? ItemStack.EMPTY : new ItemStack(egg);
                l.add(new Entry(id.toString(), name, (name + " " + id).toLowerCase(Locale.ROOT), icon, false));
            }
            l.sort((a, c) -> {
                int byIcon = Boolean.compare(c.icon.isEmpty() == false, a.icon.isEmpty() == false); // mobs first
                return byIcon != 0 ? byIcon : a.name.compareToIgnoreCase(c.name);
            });
            types = l;
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

    /** Players nearby plus players already picked (they may be offline right now). */
    private List<Entry> players() {
        Set<String> names = new LinkedHashSet<>();
        if (minecraft != null && minecraft.level != null)
            for (Player p : minecraft.level.players()) if (p != minecraft.player) names.add(p.getName().getString());
        for (String k : esp.picks.get()) if (k.startsWith("player:")) names.add(k.substring(7));
        List<Entry> out = new ArrayList<>();
        for (String n : names) out.add(new Entry("player:" + n.toLowerCase(Locale.ROOT), n, ("player " + n).toLowerCase(Locale.ROOT), new ItemStack(Items.PLAYER_HEAD), true));
        return out;
    }

    private List<Entry> results() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> starts = new ArrayList<>(), contains = new ArrayList<>();
        List<Entry> all = new ArrayList<>(players());
        all.addAll(types);
        for (Entry e : all) {
            if (q.isEmpty()) {
                (esp.picks.contains(e.key) || e.player ? starts : contains).add(e);
                continue;
            }
            String n = e.name.toLowerCase(Locale.ROOT);
            if (n.startsWith(q) || e.key.startsWith("minecraft:" + q) || n.contains(" " + q)) starts.add(e);
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
        scroll += (scrollTarget - scroll) * 0.35;
        float a = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        int W = Math.min(600, width - 16), H = Math.min(360, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1 - a) * 8);
        Ui.glow(g, width / 2, height / 2, W + 240, H + 180, 0x20000000 | (t.accent & 0xFFFFFF));
        for (int i = 3; i >= 1; i--) Px.roundRect(g, x0 - i * 2, y0 - i * 2 + 4, W + i * 4, H + i * 4, 8 + i * 2, 0x22000000);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x1C000000 | (t.text & 0xFFFFFF));
        int ink = 0xFF000000 | (t.text & 0xFFFFFF), acc = 0xFF000000 | (t.accent & 0xFFFFFF);
        int pick = 0xFF000000 | (esp.pickColor.argb() & 0xFFFFFF);

        int cx = x0 + 16;
        Type.spaced(g, "RENDER", Type.MONO, cx, y0 + 12, 0.6f, t.muted, 1.6f);
        Type.left(g, "Entity ESP", Type.DISPLAY, cx, y0 + 21, 2.1f, t.text);
        boolean xh = Ui.in(mx, my, x0 + W - 24, y0, 24, 22);
        Type.center(g, "×", Type.UI, x0 + W - 12, y0 + 5, 1.3f, xh ? t.accent : t.muted);
        hits.add(new Hit(x0 + W - 24, y0, 24, 22, this::onClose));
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
        Type.left(g, query.isEmpty() ? "Search mobs, animals or a player name – Enter adds it" : query + (blink ? "|" : ""), Type.UI, lx + 7, top + 5, 0.85f, query.isEmpty() ? t.muted2 : t.text);
        List<Entry> res = results();
        int listTop = top + 24, listBot = y0 + H - 14, rowH = 18;
        int maxScroll = Math.max(0, res.size() * rowH - (listBot - listTop));
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        g.enableScissor(lx - 2, listTop, lx + lw + 2, listBot);
        int first = Math.max(0, (int) (scroll / rowH) - 1);
        for (int i = first; i < res.size(); i++) {
            int ry = listTop + i * rowH - (int) scroll;
            if (ry > listBot) break;
            Entry e = res.get(i);
            boolean sel = esp.picks.contains(e.key);
            boolean hov = my >= listTop && my < listBot && Ui.in(mx, my, lx, ry, lw, rowH);
            if (hov || sel) Px.roundRect(g, lx, ry + 1, lw, rowH - 2, 3, hov ? t.cardHover : t.card);
            if (sel) Px.roundRect(g, lx + 1, ry + 4, 1.6f, rowH - 8, 0.8f, pick);
            if (!e.icon.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(lx + 5, ry + 2);
                g.pose().scale(0.875f, 0.875f);
                g.item(e.icon, 0, 0);
                g.pose().popMatrix();
            }
            Type.left(g, Type.fit(e.name, Type.BOLD, 0.85f, lw - 120), Type.BOLD, lx + 24, ry + 5, 0.85f, sel ? t.text : t.muted);
            Type.right(g, e.player ? "PLAYER" : Type.fit(e.key.replace("minecraft:", ""), Type.MONO, 0.55f, 90), Type.MONO, lx + lw - 22, ry + 7, 0.55f, e.player ? t.accent : t.muted2);
            if (sel) Ui.diamond(g, lx + lw - 10, ry + 9, 2, pick);
            else if (hov) Type.center(g, "+", Type.BOLD, lx + lw - 10, ry + 5, 0.9f, t.accent);
            if (hov) hits.add(new Hit(lx, Math.max(ry, listTop), lw, rowH, () -> {
                esp.picks.toggle(e.key);
                Store.save();
            }));
        }
        g.disableScissor();
        if (res.isEmpty() && !query.isBlank())
            Type.left(g, "No mob called “" + query + "” – press Enter to add it as a player name.", Type.UI, lx, listTop + 6, 0.8f, t.muted);

        // ---------- right: colour, mode, categories, picked
        int rx = lx + lw + 18, rw = x0 + W - 16 - rx;
        int yy = top;
        Type.spaced(g, "PICK COLOR", Type.MONO, rx, yy, 0.6f, t.muted, 1.6f);
        yy += 10;
        float px = rx + 5;
        for (int c : ColorSetting.PALETTE) {
            if (px > rx + rw - 5) {
                px = rx + 5;
                yy += 14;
            }
            boolean on = (c & 0xFFFFFF) == (pick & 0xFFFFFF);
            if (on) Px.circle(g, px, yy + 6, 6.2f, ink);
            Px.circle(g, px, yy + 6, 4.8f, c | 0xFF000000);
            final int chosen = c;
            hits.add(new Hit(px - 6, yy, 12, 12, () -> {
                esp.pickColor.set(chosen);
                Store.save();
            }));
            px += 14;
        }
        yy += 20;
        yy = toggle(g, mx, my, rx, yy, rw, "Only my picks", esp.show.is("Only picks"), () -> esp.show.set(esp.show.is("Only picks") ? "Categories + picks" : "Only picks"));
        if (!esp.show.is("Only picks")) {
            yy = toggle(g, mx, my, rx, yy, rw, "All monsters", esp.hostile.on(), esp.hostile::toggle);
            yy = toggle(g, mx, my, rx, yy, rw, "All animals", esp.passive.on(), esp.passive::toggle);
            yy = toggle(g, mx, my, rx, yy, rw, "All players", esp.players.on(), esp.players::toggle);
            yy = toggle(g, mx, my, rx, yy, rw, "Dropped items", esp.items.on(), esp.items::toggle);
        }
        yy += 4;
        Type.spaced(g, "PICKED  " + esp.picks.get().size(), Type.MONO, rx, yy, 0.6f, t.muted, 1.6f);
        yy += 10;
        for (String key : new ArrayList<>(esp.picks.get())) {
            if (yy > y0 + H - 22) {
                Type.left(g, "…", Type.UI, rx, yy, 0.8f, t.muted);
                break;
            }
            String name = key.startsWith("player:") ? key.substring(7) + "  (player)"
                    : types.stream().filter(x -> x.key.equals(key)).map(x -> x.name).findFirst().orElse(key);
            boolean ch = Ui.in(mx, my, rx, yy, rw, 12);
            Px.roundRect(g, rx, yy, rw, 12, 6, ch ? t.cardHover : t.card);
            Type.left(g, Type.fit(name, Type.UI, 0.7f, rw - 20), Type.UI, rx + 6, yy + 3, 0.7f, t.text);
            Type.center(g, "×", Type.UI, rx + rw - 7, yy + 1, 0.9f, ch ? t.accent : t.muted);
            hits.add(new Hit(rx, yy, rw, 12, () -> {
                esp.picks.toggle(key);
                Store.save();
            }));
            yy += 14;
        }
        if (minecraft != null && minecraft.player != null && !Access.allowed(minecraft, Access.ENTITY_ESP))
            Type.left(g, "Off on this server – only singleplayer, your LAN world, operator or a server that allows it.", Type.UI, cx, y0 + H - 11, 0.6f, 0xFFF0B428);
    }

    private int toggle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, boolean on, Runnable flip) {
        boolean h = Ui.in(mx, my, x, y, w, 12);
        Type.left(g, label, Type.UI, x, y + 2, 0.75f, t.text);
        Ui.pill(g, x + w - 20, y + 1, on ? 1f : 0f, h, t);
        hits.add(new Hit(x, y, w, 12, () -> {
            flip.run();
            Store.save();
        }));
        return y + 15;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
        if (e.key() == InputConstants.KEY_RETURN && !query.isBlank()) { // Enter adds the best match – or a player name
            List<Entry> r = results();
            String key = r.isEmpty() ? "player:" + query.trim().toLowerCase(Locale.ROOT) : r.get(0).key;
            if (!esp.picks.contains(key)) esp.picks.toggle(key);
            Store.save();
            query = "";
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
