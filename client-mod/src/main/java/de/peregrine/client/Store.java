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

/** Speichert Module (an/aus, Einstellungen, HUD-Positionen) in config/peregrine-client.json. */
public final class Store {
    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("peregrine-client.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!root.has("modules")) return; // altes Format – Standardwerte nehmen
            JsonObject mods = root.getAsJsonObject("modules");
            for (Module m : Modules.ALL) {
                if (!mods.has(m.id)) continue;
                JsonObject o = mods.getAsJsonObject(m.id);
                if (o.has("enabled")) m.enabled = o.get("enabled").getAsBoolean();
                if (m instanceof HudModule h) {
                    h.placed = o.has("placed") && o.get("placed").getAsBoolean();
                    if (h.placed && o.has("x")) h.x = o.get("x").getAsDouble();
                    if (h.placed && o.has("y")) h.y = o.get("y").getAsDouble();
                }
                JsonObject s = o.has("settings") ? o.getAsJsonObject("settings") : new JsonObject();
                for (Setting<?> st : m.settings) st.load(s.get(st.id));
            }
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Einstellungen konnten nicht geladen werden: {}", e.toString());
        }
    }

    public static void save() {
        try {
            JsonObject mods = new JsonObject();
            for (Module m : Modules.ALL) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.enabled);
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
            root.addProperty("format", 2);
            root.add("modules", mods);
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Einstellungen konnten nicht gespeichert werden: {}", e.toString());
        }
    }
}
