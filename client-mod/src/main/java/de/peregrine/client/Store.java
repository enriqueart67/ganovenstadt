package de.peregrine.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.peregrine.client.module.HudModule;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Speichert Module (an/aus, Einstellungen, HUD-Positionen) – gemeinsam für alle Profile (vom Launcher) oder in config/peregrine-client.json. */
public final class Store {
    /** Gemeinsamer Ordner vom Launcher (-Dperegrine.home) – sonst null. */
    public static Path home() {
        String h = System.getProperty("peregrine.home");
        return h == null || h.isBlank() ? null : Path.of(h);
    }

    private static Path local() {
        return FabricLoader.getInstance().getConfigDir().resolve("peregrine-client.json");
    }

    /** Einstellungen gelten für alle Profile, wenn der Launcher einen gemeinsamen Ordner vorgibt. */
    public static Path path() {
        Path h = home();
        return h == null ? local() : h.resolve("peregrine-client.json");
    }

    public static void load() {
        try {
            Path p = path();
            // Erster Start mit gemeinsamem Ordner: bisherige Einstellungen dieses Profils übernehmen
            if (!Files.exists(p) && !p.equals(local()) && Files.exists(local())) {
                Files.createDirectories(p.getParent());
                Files.copy(local(), p);
            }
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!root.has("modules")) return; // altes Format – Standardwerte nehmen
            JsonObject mods = root.getAsJsonObject("modules");
            int format = root.has("format") ? root.get("format").getAsInt() : 2;
            for (Module m : Modules.ALL) {
                if (!mods.has(m.id)) continue;
                JsonObject o = mods.getAsJsonObject(m.id);
                if (o.has("enabled")) m.enabled = o.get("enabled").getAsBoolean();
                if (format < 3 && m == Modules.MAIN_MENU) m.enabled = false; // vanilla title screen is the default now
                if (format < 4 && m == Modules.EMERGENCY) m.enabled = false; // emergency logout is off by default now
                if (m instanceof HudModule h) {
                    h.placed = o.has("placed") && o.get("placed").getAsBoolean();
                    if (h.placed && o.has("x")) h.x = o.get("x").getAsDouble();
                    if (h.placed && o.has("y")) h.y = o.get("y").getAsDouble();
                }
                JsonObject s = o.has("settings") ? o.getAsJsonObject("settings") : new JsonObject();
                for (Setting<?> st : m.settings) st.load(s.get(st.id));
            }
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Could not load settings: {}", e.toString());
        }
    }

    public static void save() {
        try {
            JsonObject mods = new JsonObject();
            for (Module m : Modules.ALL) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.enabled);
                // catalogue for the launcher's Mods page (it can switch modules on and off between sessions)
                o.addProperty("name", m.name);
                o.addProperty("description", m.description);
                o.addProperty("category", m.category.label);
                if (m instanceof HudModule h) {
                    o.addProperty("placed", h.placed);
                    o.addProperty("x", h.x);
                    o.addProperty("y", h.y);
                }
                JsonObject s = new JsonObject();
                for (Setting<?> st : m.settings) s.add(st.id, st.save());
                o.add("settings", s);
                mods.add(m.id, o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("format", 4);
            root.add("modules", mods);
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Could not save settings: {}", e.toString());
        }
    }
}
