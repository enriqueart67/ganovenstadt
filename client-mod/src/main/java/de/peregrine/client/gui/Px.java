package de.peregrine.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small vector painter for the menus: draws in real screen pixels instead of GUI pixels (which are 2–4 screen
 * pixels big), with anti-aliased edges. Rounded rectangles, outlines, circles, diamonds and 1-pixel hairlines –
 * the same clean, round look as the launcher. Coordinates are normal GUI coordinates (floats allowed).
 */
public final class Px {
    private Px() {
    }

    private static float scale() {
        try {
            return (float) Minecraft.getInstance().getWindow().getGuiScale();
        } catch (Throwable e) {
            return 2f;
        }
    }

    static int withAlpha(int argb, float f) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static void begin(GuiGraphicsExtractor g, float s) {
        g.pose().pushMatrix();
        g.pose().scale(1f / s, 1f / s);
    }

    private static void end(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
    }

    /** One row [l, r) at device row y, with partial-coverage pixels at both ends. */
    private static void span(GuiGraphicsExtractor g, float l, float r, int y, int argb, float rowCov) {
        if (r <= l) return;
        int li = (int) Math.ceil(l), ri = (int) Math.floor(r);
        if (ri > li) g.fill(li, y, ri, y + 1, withAlpha(argb, rowCov));
        if (li - l > 0.02f) g.fill(li - 1, y, li, y + 1, withAlpha(argb, (li - l) * rowCov));
        if (r - ri > 0.02f && ri >= li) g.fill(ri, y, ri + 1, y + 1, withAlpha(argb, (r - ri) * rowCov));
    }

    // ------------------------------------------------------------------ corner textures
    // A rounded box used to be drawn row by row in screen pixels: at 4K with a big GUI scale that was thousands of
    // fills per box and every frame – the menus lagged. Now each corner radius (in screen pixels) gets one small
    // anti-aliased circle texture, made once; a box is 4 corner blits + 3 fills, a frame is 9 draws per box no matter
    // how high the resolution is, and the edges stay sharp because everything lands on whole screen pixels.
    private static final java.util.Map<Integer, net.minecraft.resources.Identifier> FILLS = new java.util.HashMap<>(), RINGS = new java.util.HashMap<>();

    private static net.minecraft.resources.Identifier corner(int r, boolean ring) {
        var cache = ring ? RINGS : FILLS;
        var id = cache.get(r);
        if (id != null) return id;
        int n = r * 2;
        var img = new com.mojang.blaze3d.platform.NativeImage(n, n, false);
        float outer = r, inner = r - 1f;
        for (int py = 0; py < n; py++)
            for (int px = 0; px < n; px++) {
                int hits = 0;
                for (int sy = 0; sy < 4; sy++)
                    for (int sx = 0; sx < 4; sx++) { // 4×4 samples per pixel
                        float dx = px + (sx + 0.5f) / 4f - r, dy = py + (sy + 0.5f) / 4f - r;
                        float d = (float) Math.sqrt(dx * dx + dy * dy);
                        if (d <= outer && (!ring || d >= inner)) hits++;
                    }
                img.setPixel(px, py, ((hits * 255 / 16) << 24) | 0xFFFFFF);
            }
        id = net.minecraft.resources.Identifier.fromNamespaceAndPath("peregrine", "px/" + (ring ? "ring" : "fill") + "_" + r);
        var tex = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "peregrine corner", img);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        tex.upload();
        cache.put(r, id);
        return id;
    }

    /** The four corners of a circle texture of radius r at the corners of the box (X, Y, W, H) in screen pixels. */
    private static void corners(GuiGraphicsExtractor g, net.minecraft.resources.Identifier id, int X, int Y, int W, int H, int r, int argb) {
        int n = r * 2;
        var pipe = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
        g.blit(pipe, id, X, Y, 0, 0, r, r, r, r, n, n, argb);
        g.blit(pipe, id, X + W - r, Y, r, 0, r, r, r, r, n, n, argb);
        g.blit(pipe, id, X, Y + H - r, 0, r, r, r, r, r, n, n, argb);
        g.blit(pipe, id, X + W - r, Y + H - r, r, r, r, r, r, r, n, n, argb);
    }

    /** Filled rectangle with round corners. */
    public static void roundRect(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        float s = scale();
        int X = Math.round(x * s), Y = Math.round(y * s), W = Math.round((x + w) * s) - X, H = Math.round((y + h) * s) - Y;
        if (W <= 0 || H <= 0) return;
        int r = Math.min(Math.round(radius * s), Math.min(Math.min(W, H) / 2, 256));
        begin(g, s);
        if (r <= 0) {
            g.fill(X, Y, X + W, Y + H, argb);
        } else {
            corners(g, corner(r, false), X, Y, W, H, r, argb);
            if (W > 2 * r) {
                g.fill(X + r, Y, X + W - r, Y + r, argb);
                g.fill(X + r, Y + H - r, X + W - r, Y + H, argb);
            }
            if (H > 2 * r) g.fill(X, Y + r, X + W, Y + H - r, argb);
        }
        end(g);
    }

    /** Rounded outline, one screen pixel thick. */
    public static void roundStroke(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        float s = scale();
        int X = Math.round(x * s), Y = Math.round(y * s), W = Math.round((x + w) * s) - X, H = Math.round((y + h) * s) - Y;
        if (W <= 1 || H <= 1) return;
        int r = Math.min(Math.round(radius * s), Math.min(Math.min(W, H) / 2, 256));
        begin(g, s);
        if (r <= 1) {
            g.fill(X, Y, X + W, Y + 1, argb);
            g.fill(X, Y + H - 1, X + W, Y + H, argb);
            g.fill(X, Y + 1, X + 1, Y + H - 1, argb);
            g.fill(X + W - 1, Y + 1, X + W, Y + H - 1, argb);
        } else {
            corners(g, corner(r, true), X, Y, W, H, r, argb);
            if (W > 2 * r) {
                g.fill(X + r, Y, X + W - r, Y + 1, argb);
                g.fill(X + r, Y + H - 1, X + W - r, Y + H, argb);
            }
            if (H > 2 * r) {
                g.fill(X, Y + r, X + 1, Y + H - r, argb);
                g.fill(X + W - 1, Y + r, X + W, Y + H - r, argb);
            }
        }
        end(g);
    }

    public static void circle(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
        roundRect(g, cx - r, cy - r, r * 2, r * 2, r, argb);
    }

    /** Diamond (status indicator), anti-aliased. */
    public static void diamond(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
        float s = scale();
        float CX = cx * s, CY = cy * s, Rr = r * s;
        begin(g, s);
        for (int row = (int) Math.floor(CY - Rr); row < (int) Math.ceil(CY + Rr); row++) {
            float half = Rr - Math.abs(row + 0.5f - CY);
            if (half <= 0) continue;
            span(g, CX - half, CX + half, row, argb, Math.min(1f, half));
        }
        end(g);
    }

    /** Triangle pointing left (the navigation pointer). */
    public static void chevronLeft(GuiGraphicsExtractor g, float tipX, float cy, float len, float halfH, int argb) {
        float s = scale();
        float TX = tipX * s, CY = cy * s, L = len * s, HH = halfH * s;
        begin(g, s);
        for (int row = (int) Math.floor(CY - HH); row < (int) Math.ceil(CY + HH); row++) {
            float f = 1f - Math.abs(row + 0.5f - CY) / HH;
            if (f <= 0) continue;
            span(g, TX + L * (1f - f), TX + L, row, argb, 1f);
        }
        end(g);
    }

    /** Horizontal hairline, one screen pixel high. */
    public static void hline(GuiGraphicsExtractor g, float x1, float x2, float y, int argb) {
        float s = scale();
        begin(g, s);
        g.fill(Math.round(x1 * s), Math.round(y * s), Math.round(x2 * s), Math.round(y * s) + 1, argb);
        end(g);
    }

    /** Vertical hairline, one screen pixel wide. */
    public static void vline(GuiGraphicsExtractor g, float x, float y1, float y2, int argb) {
        float s = scale();
        begin(g, s);
        g.fill(Math.round(x * s), Math.round(y1 * s), Math.round(x * s) + 1, Math.round(y2 * s), argb);
        end(g);
    }

    /** Straight line at any angle, width in screen pixels (one rotated rectangle – cheap). */
    public static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float width, int argb) {
        float s = scale();
        float X1 = x1 * s, Y1 = y1 * s, X2 = x2 * s, Y2 = y2 * s;
        float dx = X2 - X1, dy = Y2 - Y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f || len > 20000f) return;
        g.pose().pushMatrix();
        g.pose().scale(1f / s, 1f / s);
        g.pose().translate(X1, Y1);
        g.pose().rotate((float) Math.atan2(dy, dx));
        int wpx = Math.max(1, Math.round(width));
        g.fill(0, -wpx / 2, Math.round(len), -wpx / 2 + wpx, argb);
        g.pose().popMatrix();
    }
}
