package de.peregrine.client.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.peregrine.client.Store;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the assistant's Claude mode – kept apart from the SDK so the mod still works when the
 * "peregrine-ai-libs" mod is missing. The API key stays in a file on this computer (peregrine-claude.json)
 * and is only ever sent to api.anthropic.com.
 */
public final class Claude {
    private Claude() {
    }

    private static String key = "";
    private static boolean on = true, loaded;
    private static Boolean libs;

    private static Path file() {
        Path h = Store.home();
        return h != null ? h.resolve("peregrine-claude.json")
                : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("peregrine-claude.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(file())) {
                JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                if (o.has("apiKey")) key = o.get("apiKey").getAsString();
                if (o.has("enabled")) on = o.get("enabled").getAsBoolean();
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("apiKey", key);
            o.addProperty("enabled", on);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {
        }
    }

    /** Is the Anthropic SDK there (the peregrine-ai-libs mod)? */
    public static boolean libsPresent() {
        if (libs == null) {
            try {
                Class.forName("com.anthropic.client.okhttp.AnthropicOkHttpClient", false, Claude.class.getClassLoader());
                libs = true;
            } catch (Throwable t) {
                libs = false;
            }
        }
        return libs;
    }

    /** Your key, or ANTHROPIC_API_KEY from the environment. */
    public static String key() {
        load();
        if (!key.isBlank()) return key.trim();
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null ? "" : env.trim();
    }

    public static boolean hasKey() {
        return !key().isEmpty();
    }

    public static void setKey(String k) {
        load();
        key = k == null ? "" : k.trim();
        save();
        if (libsPresent()) ClaudeBrain.reset();
    }

    public static boolean enabled() {
        load();
        return on;
    }

    public static void setEnabled(boolean v) {
        load();
        on = v;
        save();
    }

    /** Claude answers when it is switched on, has a key and the SDK is installed. */
    public static boolean active() {
        return enabled() && hasKey() && libsPresent();
    }

    public static boolean thinking() {
        return libsPresent() && ClaudeBrain.busy();
    }

    /** Sends what you typed to Claude (in the background). */
    public static void ask(String text) {
        ClaudeBrain.ask(text);
    }

    public static void forgetConversation() {
        if (libsPresent()) ClaudeBrain.reset();
    }
}
