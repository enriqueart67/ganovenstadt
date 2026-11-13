package de.peregrine.client.module;

import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Ein Modul, das etwas auf dem Bildschirm anzeigt und im HUD-Editor verschoben werden kann. */
public abstract class HudModule extends Module {
    /** Wo die Anzeige automatisch hinkommt, solange man sie nicht selbst verschoben hat. */
    public enum Anchor { LEFT, RIGHT, BOTTOM_RIGHT }

    public Anchor anchor = Anchor.LEFT;
    /** true, sobald die Anzeige im HUD-Editor verschoben wurde. */
    public boolean placed;
    /** Position der linken oberen Ecke als Anteil der Bildschirmgroesse (0..1). */
    public double x, y;
    public final double defX, defY;
    public final NumberSetting scale = add(new NumberSetting("scale", "Größe", 1.0, 0.5, 2.0, 0.1, "×"));
    /** Zuletzt gezeichnete Flaeche in GUI-Pixeln (fuer den Editor). */
    public int lastX, lastY, lastW, lastH;

    protected HudModule(String id, String name, String description, boolean enabled, double x, double y) {
        super(id, name, description, Category.HUD, enabled);
        this.x = this.defX = x;
        this.y = this.defY = y;
    }

    /** Zeichnet bei (0,0) in eigenen Koordinaten und liefert {Breite, Hoehe}. `preview` = im HUD-Editor. */
    protected abstract int[] draw(GuiGraphicsExtractor g, Minecraft mc, boolean preview);

    public final void render(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
        float s = (float) scale.d();
        int px = (int) Math.round(x * g.guiWidth()), py = (int) Math.round(y * g.guiHeight());
        // nie ueber den Bildschirmrand hinaus (z. B. bei anderer GUI-Groesse)
        px = Math.max(0, Math.min(px, g.guiWidth() - lastW));
        py = Math.max(0, Math.min(py, g.guiHeight() - lastH));
        if (lastH == 0 && !placed) py = g.guiHeight(); // erster Frame: erst Groesse messen, dann anordnen
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().scale(s, s);
        int[] size = draw(g, mc, preview);
        g.pose().popMatrix();
        lastX = px;
        lastY = py;
        lastW = Math.round(size[0] * s);
        lastH = Math.round(size[1] * s);
    }

    @Override
    public void reset() {
        super.reset();
        x = defX;
        y = defY;
        placed = false;
    }

    /** Nicht verschobene Anzeigen automatisch ohne Ueberlappung anordnen (links/rechts untereinander). */
    public static void layout(int gw, int gh, java.util.List<HudModule> list) {
        int left = 2, right = Math.max(2, gh / 5);
        for (HudModule h : list) {
            if (!h.enabled || h.placed || h.lastH == 0) continue;
            switch (h.anchor) {
                case LEFT -> {
                    h.x = 2.0 / gw;
                    h.y = (double) left / gh;
                    left += h.lastH + 3;
                }
                case RIGHT -> {
                    h.x = (double) (gw - h.lastW - 2) / gw;
                    h.y = (double) right / gh;
                    right += h.lastH + 3;
                }
                case BOTTOM_RIGHT -> {
                    h.x = (double) (gw - h.lastW - 2) / gw;
                    h.y = (double) (gh - h.lastH - 2) / gh;
                }
            }
        }
    }
}
