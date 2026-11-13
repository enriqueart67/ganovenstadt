package de.peregrine.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.peregrine.client.Store;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Farben des Mod-Menüs. Kommen aus dem Design, das im Launcher unter „Aussehen“ gewählt ist
 * (theme.json im gemeinsamen Ordner); ohne Launcher: dunkles Standard-Design mit Orange.
 */
public final class Theme {
    public final int bg, side, card, cardHover, line, text, muted, muted2, accent, accentHover, accentSoft, accentSoftText, off, offHover, onAccent, shadow;
    public final boolean light;

    private Theme(int bg, int surface2, int border, int text, int muted, int accent, int onAccent, boolean light) {
        this.light = light;
        this.bg = 0xF5000000 | (bg & 0xFFFFFF);
        this.side = opaque(light ? mix(bg, text, 0.035f) : mix(bg, 0x000000, 0.3f));
        this.card = opaque(mix(bg, text, light ? 0.06f : 0.07f));
        this.cardHover = opaque(mix(bg, text, light ? 0.11f : 0.12f));
        this.line = opaque(border);
        this.text = opaque(text);
        this.muted = opaque(muted);
        this.muted2 = opaque(mix(muted, bg, 0.35f));
        this.accent = opaque(accent);
        this.accentHover = opaque(mix(accent, light ? 0x000000 : 0xFFFFFF, 0.15f));
        this.accentSoft = opaque(mix(bg, accent, light ? 0.18f : 0.35f));
        this.accentSoftText = opaque(light ? mix(accent, 0x000000, 0.25f) : mix(accent, 0xFFFFFF, 0.45f));
        this.off = opaque(mix(bg, text, light ? 0.12f : 0.13f));
        this.offHover = opaque(mix(bg, text, light ? 0.18f : 0.2f));
        this.onAccent = opaque(onAccent);
        this.shadow = light ? 0x40000000 : 0x70000000;
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int rgb(JsonObject o, String k, int def) {
        if (!o.has(k)) return def;
        JsonArray a = o.getAsJsonArray(k);
        return (a.get(0).getAsInt() << 16) | (a.get(1).getAsInt() << 8) | a.get(2).getAsInt();
    }

    public static Theme fallback() {
        return new Theme(0x101013, 0x222229, 0x26262E, 0xF2F2F5, 0x8C8C99, 0xFF4F00, 0xFFFFFF, false);
    }

    /** Aktuelles Design laden (bei jedem Öffnen des Menüs, damit Änderungen im Launcher sofort gelten). */
    public static Theme load() {
        try {
            Path h = Store.home();
            if (h == null) return fallback();
            Path p = h.resolve("theme.json");
            if (!Files.exists(p)) return fallback();
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            boolean light = o.has("light") && o.get("light").getAsBoolean();
            return new Theme(rgb(o, "bg", 0x101013), rgb(o, "surface2", 0x222229), rgb(o, "border", 0x26262E),
                    rgb(o, "text", 0xF2F2F5), rgb(o, "muted", 0x8C8C99), rgb(o, "accent", 0xFF4F00),
                    rgb(o, "onAccent", 0xFFFFFF), light);
        } catch (Exception e) {
            return fallback();
        }
    }
}
