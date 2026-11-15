package de.peregrine.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** Typography of the in-game UI: the launcher's IBM Plex faces, shipped with the mod as TTF fonts. */
public final class Type {
    private Type() {
    }

    public static final FontDescription UI = face("ui");
    public static final FontDescription BOLD = face("bold");
    public static final FontDescription DISPLAY = face("display");
    public static final FontDescription MONO = face("mono");

    private static FontDescription face(String name) {
        return new FontDescription.Resource(Identifier.fromNamespaceAndPath("peregrine", name));
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static Component styled(String s, FontDescription f) {
        return Component.literal(s).withStyle(Style.EMPTY.withFont(f));
    }

    public static int width(String s, FontDescription f, float scale) {
        return Math.round(font().width(styled(s, f)) * scale);
    }

    /** Left-aligned text; y is the top of the line box (9 px high at scale 1). */
    public static void left(GuiGraphicsExtractor g, String s, FontDescription f, float x, float y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font(), styled(s, f), 0, 0, color, false);
        g.pose().popMatrix();
    }

    public static void right(GuiGraphicsExtractor g, String s, FontDescription f, float xRight, float y, float scale, int color) {
        left(g, s, f, xRight - width(s, f, scale), y, scale, color);
    }

    public static void center(GuiGraphicsExtractor g, String s, FontDescription f, float cx, float y, float scale, int color) {
        left(g, s, f, cx - width(s, f, scale) / 2f, y, scale, color);
    }

    /** Shortens the text with an ellipsis so it fits into maxWidth. */
    public static String fit(String s, FontDescription f, float scale, int maxWidth) {
        if (width(s, f, scale) <= maxWidth) return s;
        String t = s;
        while (t.length() > 1 && width(t + "…", f, scale) > maxWidth) t = t.substring(0, t.length() - 1);
        return t.stripTrailing() + "…";
    }

    /** Letter-spaced caps label (like the launcher's mono labels). */
    public static int spaced(GuiGraphicsExtractor g, String s, FontDescription f, float x, float y, float scale, int color, float sp) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String c = s.substring(i, i + 1);
            left(g, c, f, cx, y, scale, color);
            cx += width(c, f, scale) + sp;
        }
        return Math.round(cx - x - sp);
    }

    public static int spacedWidth(String s, FontDescription f, float scale, float sp) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += width(s.substring(i, i + 1), f, scale) + sp;
        return Math.round(w - sp);
    }
}
