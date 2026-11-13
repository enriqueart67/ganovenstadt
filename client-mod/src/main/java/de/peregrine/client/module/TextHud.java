package de.peregrine.client.module;

import de.peregrine.client.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** HUD-Modul aus Textzeilen ("Beschriftung", "Wert") im einheitlichen HUD-Stil. */
public abstract class TextHud extends HudModule {
    protected TextHud(String id, String name, String description, boolean enabled, double x, double y) {
        super(id, name, description, enabled, x, y);
    }

    /** Zeilen als {Beschriftung, Wert}; leere Liste = nichts anzeigen. */
    protected abstract List<String[]> lines(Minecraft mc, boolean preview);

    @Override
    protected int[] draw(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
        List<String[]> lines = lines(mc, preview);
        if (lines.isEmpty()) return new int[]{0, 0};
        return box(g, mc.font, lines);
    }

    public static int[] box(GuiGraphicsExtractor g, Font font, List<String[]> lines) {
        var st = Modules.STYLE;
        int pad = 4, lh = font.lineHeight + 2, w = 0;
        for (String[] l : lines) w = Math.max(w, font.width(label(l)) + font.width(l[1]));
        int bw = w + pad * 2, bh = lines.size() * lh + pad * 2 - 2;
        if (st.background.on()) g.fill(0, 0, bw, bh, (int) (st.opacity.d() * 255) << 24);
        int ty = pad;
        for (String[] l : lines) {
            String lab = label(l);
            g.text(font, lab, pad, ty, st.labelColor.argb(), st.shadow.on());
            g.text(font, l[1], pad + font.width(lab), ty, st.valueColor.argb(), st.shadow.on());
            ty += lh;
        }
        return new int[]{bw, bh};
    }

    private static String label(String[] l) {
        return l[0].isEmpty() ? "" : l[0] + ": ";
    }
}
