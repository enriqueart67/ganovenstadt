package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Waypoints;
import de.peregrine.client.Waypoints.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Waypoint list: add the current position, rename, recolour, hide and delete. */
public final class WaypointScreen extends Screen {
    private final Theme t = Theme.load();
    private Waypoint editing;
    private double scroll;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();

    public WaypointScreen() {
        super(Component.literal("Waypoints"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, t.light ? 0x66000000 : 0x99000000);
    }

    private boolean link(GuiGraphicsExtractor g, int mx, int my, String label, int xRight, int y, Runnable action) {
        int w = Type.width(label, Type.MONO, 0.7f);
        boolean h = Ui.in(mx, my, xRight - w - 3, y - 4, w + 6, 14);
        Type.right(g, label, Type.MONO, xRight, y, 0.7f, h ? t.accent : t.muted);
        hits.add(new Hit(xRight - w - 3, y - 4, w + 6, 14, action));
        return h;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        hits.clear();
        int W = Math.min(420, width - 16), H = Math.min(300, height - 16);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        for (int si = 3; si >= 1; si--) Px.roundRect(g, x0 - si * 2, y0 - si * 2 + 4, W + si * 4, H + si * 4, 8 + si * 2, 0x22000000);
        Px.roundRect(g, x0, y0, W, H, 7, 0xFF000000 | (t.bg & 0xFFFFFF));
        Px.roundStroke(g, x0, y0, W, H, 7, 0x1C000000 | (t.text & 0xFFFFFF));
        if (t.chart) Ui.chart(g, x0, y0, W, H, t.light);
        int cx = x0 + 14, cw = W - 28;
        Type.left(g, "Waypoints", Type.DISPLAY, cx, y0 + 10, 2.1f, t.text);
        String dim = Waypoints.dimension(minecraft);
        Type.right(g, dim.replace("minecraft:", "").replace('_', ' ').toUpperCase(), Type.MONO, cx + cw, y0 + 14, 0.7f, t.muted);

        // primary action
        String add = "ADD WAYPOINT HERE";
        int aw = Type.width(add, Type.MONO, 0.8f) + 18, ay = y0 + H - 24;
        boolean ah = Ui.in(mx, my, cx, ay, aw, 15);
        Px.roundRect(g, cx, ay, aw, 15, 3, ah ? t.accent : t.text);
        Type.left(g, add, Type.MONO, cx + 9, ay + 4, 0.8f, ah ? t.onAccent : (t.bg | 0xFF000000));
        hits.add(new Hit(cx, ay, aw, 15, () -> editing = Waypoints.addHere(minecraft, null)));
        Type.right(g, "CLICK A NAME TO RENAME   ·   ESC TO CLOSE", Type.MONO, cx + cw, ay + 4, 0.7f, t.muted);
        g.fill(x0, ay - 8, x0 + W, ay - 7, t.line);

        int top = y0 + 38, rowH = 22, bottom = ay - 9;
        Px.hline(g, cx, cx + cw, top - 1, t.line);
        List<Waypoint> list = Waypoints.all(minecraft);
        int maxScroll = Math.max(0, list.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (list.isEmpty()) Type.left(g, "No waypoints yet. Stand somewhere worth remembering and add one.", Type.UI, cx, top + 10, 0.9f, t.muted);
        g.enableScissor(x0, top, x0 + W, bottom);
        int y = top - (int) scroll;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        for (Waypoint w : new ArrayList<>(list)) {
            if (y + rowH > top && y < bottom) {
                boolean here = w.dimension.equals(dim);
                boolean rowHov = my >= top && my < bottom && Ui.in(mx, my, cx, y, cw, rowH);
                if (rowHov) g.fill(cx, y, cx + cw, y + rowH - 1, t.card);
                // colour swatch – click cycles
                g.fill(cx + 4, y + 6, cx + 14, y + 16, w.color);
                hits.add(new Hit(cx, y, 18, rowH, () -> {
                    int i = 0;
                    for (int k = 0; k < Waypoints.COLORS.length; k++) if (Waypoints.COLORS[k] == w.color) i = k;
                    w.color = Waypoints.COLORS[(i + 1) % Waypoints.COLORS.length];
                    Waypoints.save();
                }));
                String name = w == editing ? w.name + (blink ? "|" : "") : w.name;
                Type.left(g, Type.fit(name, Type.BOLD, 1f, cw - 230), Type.BOLD, cx + 22, y + 7, 1f, w.visible && here ? t.text : t.muted);
                hits.add(new Hit(cx + 20, y, cw - 230, rowH, () -> editing = w == editing ? null : w));
                String pos = w.x + "  " + w.y + "  " + w.z + (here ? "   " + Math.round(w.distance(minecraft.player.position())) + " M" : "   OTHER DIMENSION");
                Type.right(g, pos, Type.MONO, cx + cw - 96, y + 8, 0.7f, t.muted);
                link(g, mx, my, w.visible ? "HIDE" : "SHOW", cx + cw - 50, y + 8, () -> {
                    w.visible = !w.visible;
                    Waypoints.save();
                });
                link(g, mx, my, "DELETE", cx + cw - 4, y + 8, () -> {
                    if (editing == w) editing = null;
                    Waypoints.remove(w);
                });
                g.fill(cx, y + rowH - 1, cx + cw, y + rowH, t.line);
            }
            y += rowH;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in(e.x(), e.y(), h.x(), h.y(), h.w(), h.h())) {
                    h.action().run();
                    return true;
                }
            }
            editing = null;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 22;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (TextInput.key(this, e)) return true;
        if (editing != null) {
            if (e.key() == InputConstants.KEY_BACKSPACE && !editing.name.isEmpty()) {
                editing.name = editing.name.substring(0, editing.name.length() - 1);
                return true;
            }
            if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_ESCAPE) {
                if (editing.name.isBlank()) editing.name = "Waypoint";
                editing = null;
                Waypoints.save();
                return true;
            }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (TextInput.echo(e)) return true; // already typed by its key press
        if (editing != null && e.isAllowedChatCharacter() && editing.name.length() < 28) {
            editing.name += e.codepointAsString();
            return true;
        }
        return super.charTyped(e);
    }

    @Override
    public void removed() {
        TextInput.focus(this, false);
        Waypoints.save();
        super.removed();
    }

    @Override
    protected void init() {
        super.init();
        TextInput.focus(this, true); // a text field has focus: Minecraft switches the window to text input
    }
}
