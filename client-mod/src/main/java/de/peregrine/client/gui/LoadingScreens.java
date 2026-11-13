package de.peregrine.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Peregrine-Ladebildschirme (Start des Spiels und Welt laden) – nur aus Rechtecken gezeichnet. */
public final class LoadingScreens {
    public static final int BG = 0xFF0E0E0D;
    public static final int INK = 0xFFEDEBE6;
    public static final int MUTED = 0xFF8C8880;
    public static final int LINE = 0xFF2E2D2A;
    public static final int SIGNAL = 0xFFFF4F00;

    private LoadingScreens() {
    }

    static int a(int argb, float alpha) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    /** Wortmarke „PEREGRINE“ + „CLIENT“, zentriert um (cx, y). Liefert die Unterkante. */
    static int wordmark(GuiGraphicsExtractor g, int cx, int y, int scale, float alpha) {
        String word = "PEREGRINE";
        int sp = scale;
        int w = PixelFont.width(word, scale, sp);
        int x = cx - w / 2;
        PixelFont.draw(g, word, x, y, scale, sp, a(INK, alpha));
        // Signal-Quadrat wie im Launcher
        long t = System.currentTimeMillis();
        float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(t / 420.0));
        g.fill(x + w + scale * 2, y, x + w + scale * 4, y + scale * 2, a(SIGNAL, alpha * pulse));
        int sub = Math.max(1, scale / 2);
        String client = "CLIENT";
        int cw = PixelFont.width(client, sub, sub * 2);
        PixelFont.draw(g, client, cx - cw / 2, y + scale * 7 + scale * 3, sub, sub * 2, a(SIGNAL, alpha));
        return y + scale * 7 + scale * 3 + sub * 7;
    }

    /** Ersetzt Mojangs Startbildschirm. `progress` 0..1, `alpha` fuer das Ausblenden am Ende. */
    public static void startup(GuiGraphicsExtractor g, float progress, float alpha) {
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, a(BG, alpha));
        int scale = Math.max(2, Math.min(6, w / 110));
        int bottom = wordmark(g, w / 2, h / 2 - scale * 9, scale, alpha);
        int bw = Math.min(w / 2, 260), bx = (w - bw) / 2, by = bottom + scale * 8;
        g.fill(bx, by, bx + bw, by + 2, a(LINE, alpha));
        g.fill(bx, by, bx + Math.round(bw * Math.max(0f, Math.min(1f, progress))), by + 2, a(SIGNAL, alpha));
        int ps = Math.max(1, scale / 3);
        String pct = Math.round(progress * 100) + "%";
        PixelFont.draw(g, "LADE SPIEL", bx, by + 8, ps, ps, a(MUTED, alpha));
        PixelFont.draw(g, pct, bx + bw - PixelFont.width(pct, ps, ps), by + 8, ps, ps, a(MUTED, alpha));
    }

    /** „Welt wird geladen“: Wortmarke, Text und Fortschrittsbalken. */
    public static void world(GuiGraphicsExtractor g, float progress) {
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, BG);
        int scale = Math.max(2, Math.min(5, w / 130));
        int bottom = wordmark(g, w / 2, h / 2 - scale * 10, scale, 1f);
        int bw = Math.min(w / 2, 260), bx = (w - bw) / 2, by = bottom + scale * 8;
        float p = Math.max(0f, Math.min(1f, progress));
        g.fill(bx, by, bx + bw, by + 2, LINE);
        g.fill(bx, by, bx + Math.round(bw * p), by + 2, SIGNAL);
        int ps = Math.max(1, scale / 3);
        String pct = Math.round(p * 100) + "%";
        PixelFont.draw(g, "WELT WIRD GELADEN", bx, by + 8, ps, ps, MUTED);
        PixelFont.draw(g, pct, bx + bw - PixelFont.width(pct, ps, ps), by + 8, ps, ps, MUTED);
    }

    /** Hintergrund fuer „Welt wird geladen“, „Verbinde“, „Speichern“ usw. (Text kommt von Minecraft). */
    public static void background(GuiGraphicsExtractor g) {
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, BG);
        int scale = Math.max(1, Math.min(3, w / 220));
        wordmark(g, w / 2, Math.max(12, h / 7), scale, 1f);
        // unbestimmter Ladebalken: oranges Stueck gleitet hin und her
        int bw = Math.min(w / 2, 240), bx = (w - bw) / 2, by = h - Math.max(24, h / 8);
        g.fill(bx, by, bx + bw, by + 2, LINE);
        double t = (System.currentTimeMillis() % 1600L) / 1600.0;
        double pos = 0.5 - 0.5 * Math.cos(t * Math.PI * 2);
        int seg = bw / 4, sx = bx + (int) Math.round((bw - seg) * pos);
        g.fill(sx, by, sx + seg, by + 2, SIGNAL);
    }
}
