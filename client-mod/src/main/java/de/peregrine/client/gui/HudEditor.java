package de.peregrine.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.Modules;
import de.peregrine.client.Store;
import de.peregrine.client.module.HudModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** HUD-Editor: Anzeigen mit der Maus verschieben, Mausrad = Groesse aendern. */
public final class HudEditor extends Screen {
    /** Waehrend der Editor offen ist, zeichnet er die Anzeigen selbst. */
    public static boolean active;
    private final Screen parent;
    private HudModule drag;
    private double offX, offY;

    public HudEditor(Screen parent) {
        super(Component.literal("HUD bearbeiten"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        active = true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x30000000);
        for (int x = 0; x < width; x += 20) g.fill(x, 0, x + 1, height, 0x10FFFFFF);
        for (int y = 0; y < height; y += 20) g.fill(0, y, width, y + 1, 0x10FFFFFF);
        g.fill(width / 2, 0, width / 2 + 1, height, 0x30FFFFFF);
        g.fill(0, height / 2, width, height / 2 + 1, 0x30FFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        extractBackground(g, mx, my, pt);
        HudModule.layout(width, height, Modules.huds());
        for (HudModule h : Modules.huds()) {
            if (!h.enabled) continue;
            h.render(g, minecraft, true);
            boolean hov = h == drag || Ui.in(mx, my, h.lastX, h.lastY, Math.max(h.lastW, 8), Math.max(h.lastH, 8));
            Ui.border(g, h.lastX - 1, h.lastY - 1, Math.max(h.lastW, 8) + 2, Math.max(h.lastH, 8) + 2, hov ? Ui.ACCENT : 0x80FFFFFF);
            if (hov) Ui.small(g, font, h.name + " · " + Math.round(h.scale.d() * 100) + " %", h.lastX, h.lastY - 8, Ui.TEXT);
        }
        String t = "HUD-Editor – Anzeigen ziehen · Mausrad: Größe · Esc: fertig";
        int w = font.width(t) + 16;
        g.fill((width - w) / 2, 6, (width + w) / 2, 22, 0xD0141418);
        g.text(font, t, (width - font.width(t)) / 2, 10, Ui.TEXT, false);
    }

    private HudModule at(double mx, double my) {
        var list = Modules.huds();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudModule h = list.get(i);
            if (h.enabled && Ui.in(mx, my, h.lastX, h.lastY, Math.max(h.lastW, 8), Math.max(h.lastH, 8))) return h;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        drag = at(e.x(), e.y());
        if (drag != null) {
            offX = e.x() - drag.lastX;
            offY = e.y() - drag.lastY;
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (drag == null) return super.mouseDragged(e, dx, dy);
        double nx = e.x() - offX, ny = e.y() - offY;
        // an Bildschirmraender und Mitte einrasten
        int snap = 4;
        if (Math.abs(nx) < snap) nx = 0;
        if (Math.abs(ny) < snap) ny = 0;
        if (Math.abs(nx + drag.lastW - width) < snap) nx = width - drag.lastW;
        if (Math.abs(ny + drag.lastH - height) < snap) ny = height - drag.lastH;
        if (Math.abs(nx + drag.lastW / 2.0 - width / 2.0) < snap) nx = width / 2.0 - drag.lastW / 2.0;
        nx = Math.max(0, Math.min(width - drag.lastW, nx));
        ny = Math.max(0, Math.min(height - drag.lastH, ny));
        drag.x = nx / width;
        drag.y = ny / height;
        drag.placed = true;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (drag != null) {
            drag = null;
            Store.save();
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        HudModule h = at(mx, my);
        if (h != null) {
            h.scale.set(Math.max(h.scale.min, Math.min(h.scale.max, Math.round((h.scale.d() + Math.signum(sy) * 0.1) * 10) / 10.0)));
            Store.save();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        active = false;
        Store.save();
        Ui.open(parent);
    }

    @Override
    public void removed() {
        active = false;
        super.removed();
    }
}
