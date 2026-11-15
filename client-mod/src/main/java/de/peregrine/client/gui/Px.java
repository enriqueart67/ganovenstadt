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

    /** Filled rectangle with round corners. */
    public static void roundRect(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        float s = scale();
        float X = x * s, Y = y * s, W = w * s, H = h * s, R = Math.min(radius * s, Math.min(W, H) / 2f);
        begin(g, s);
        int y0 = (int) Math.floor(Y), y1 = (int) Math.ceil(Y + H);
        int midTop = (int) Math.ceil(Y + R), midBot = (int) Math.floor(Y + H - R);
        if (midBot > midTop) {
            int li = (int) Math.ceil(X), ri = (int) Math.floor(X + W);
            g.fill(li, midTop, ri, midBot, argb);
            if (li - X > 0.02f) g.fill(li - 1, midTop, li, midBot, withAlpha(argb, li - X));
            if (X + W - ri > 0.02f) g.fill(ri, midTop, ri + 1, midBot, withAlpha(argb, X + W - ri));
        }
        for (int row = y0; row < y1; row++) {
            if (row >= midTop && row < midBot) continue;
            float cy = row + 0.5f;
            // vertical coverage of this row (top and bottom edge)
            float cov = Math.min(1f, Math.min(row + 1 - Y, Y + H - row));
            if (cov <= 0) continue;
            float dy = cy < Y + R ? (Y + R) - cy : cy > Y + H - R ? cy - (Y + H - R) : 0;
            float inset = dy > 0 ? R - (float) Math.sqrt(Math.max(0, R * R - dy * dy)) : 0;
            span(g, X + inset, X + W - inset, row, argb, cov);
        }
        end(g);
    }

    /** Rounded outline, one screen pixel thick. */
    public static void roundStroke(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        float s = scale();
        float X = x * s, Y = y * s, W = w * s, H = h * s, R = Math.min(radius * s, Math.min(W, H) / 2f);
        float Ri = Math.max(0, R - 1);
        begin(g, s);
        int y0 = (int) Math.floor(Y), y1 = (int) Math.ceil(Y + H);
        for (int row = y0; row < y1; row++) {
            float cy = row + 0.5f;
            float dy = cy < Y + R ? (Y + R) - cy : cy > Y + H - R ? cy - (Y + H - R) : 0;
            float outer = dy > 0 ? R - (float) Math.sqrt(Math.max(0, R * R - dy * dy)) : 0;
            boolean cap = row == y0 || row == y1 - 1 || (dy > 0 && dy >= Ri);
            if (cap) {
                span(g, X + outer, X + W - outer, row, argb, 1f);
                continue;
            }
            float inner = 1f + (dy > 0 ? Ri - (float) Math.sqrt(Math.max(0, Ri * Ri - dy * dy)) : 0);
            inner = Math.max(inner, outer + 1f);
            span(g, X + outer, X + inner, row, argb, 1f);
            span(g, X + W - inner, X + W - outer, row, argb, 1f);
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
