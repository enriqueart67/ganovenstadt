package de.peregrine.client;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Einstellungen der Client-Mods. Der Launcher schreibt sie nach config/peregrine-client.json;
 * Aenderungen werden waehrend des Spiels automatisch neu geladen.
 */
public final class Config {
    public boolean fps = true;
    public boolean cps = true;
    public boolean coords = true;
    public boolean direction = true;
    public boolean clock = false;
    public boolean keystrokes = true;
    public boolean background = true;
    public boolean shadow = true;
    public String hudPosition = "top_left";
    public String keysPosition = "bottom_right";
    public String textColor = "#FFFFFF";
    public String accentColor = "#4F8CFF";
    public Crosshair crosshair = new Crosshair();
    public boolean zoom = true;
    public double zoomFactor = 4.0;
    public boolean fullbright = false;
    public boolean autoSprint = false;

    public static final class Crosshair {
        public boolean enabled = true;
        /** cross, t, dot, circle */
        public String style = "cross";
        public int size = 5;
        public int gap = 2;
        public int thickness = 1;
        public String color = "#FFFFFF";
        public boolean outline = true;
        public boolean dot = false;
    }

    // ------------------------------------------------------------------ Laden
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .create();
    private static Config current = new Config();
    private static long lastModified = -1;

    public static Config get() {
        return current;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("peregrine-client.json");
    }

    /** Neu laden, falls sich die Datei geaendert hat. Liefert true bei Aenderung. */
    public static boolean reloadIfChanged() {
        Path p = path();
        try {
            if (!Files.exists(p)) {
                if (lastModified != 0) {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, GSON.toJson(current));
                    lastModified = Files.getLastModifiedTime(p).toMillis();
                }
                return false;
            }
            long m = Files.getLastModifiedTime(p).toMillis();
            if (m == lastModified) return false;
            lastModified = m;
            Config c = GSON.fromJson(Files.readString(p), Config.class);
            if (c != null) {
                if (c.crosshair == null) c.crosshair = new Crosshair();
                current = c;
                return true;
            }
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Konnte {} nicht lesen: {}", p, e.toString());
        }
        return false;
    }

    /** "#RRGGBB" oder "#AARRGGBB" -> ARGB-int */
    public static int color(String hex, int fallback) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            long v = Long.parseLong(h, 16);
            return (int) (h.length() <= 6 ? 0xFF000000L | v : v);
        } catch (Exception e) {
            return fallback;
        }
    }
}
