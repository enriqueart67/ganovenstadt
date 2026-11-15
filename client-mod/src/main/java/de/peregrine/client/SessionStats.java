package de.peregrine.client;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/** Measures the FPS of the current session and leaves the numbers for the launcher's Performance page. */
public final class SessionStats {
    private SessionStats() {
    }

    private static long samples, sum, started;
    private static int min = Integer.MAX_VALUE, max, ticks;

    public static void tick(Minecraft mc) {
        if (mc.level == null) return; // only count real play, not menus
        if (started == 0) started = System.currentTimeMillis();
        if (++ticks % 20 != 0) return; // once a second
        int fps = mc.getFps();
        if (fps <= 0) return;
        samples++;
        sum += fps;
        if (samples > 5) min = Math.min(min, fps); // skip the first seconds while chunks load
        max = Math.max(max, fps);
        if (samples % 30 == 0) save();
    }

    public static void save() {
        Path home = Store.home();
        if (home == null || samples == 0) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("fps_avg", Math.round((double) sum / samples));
            o.addProperty("fps_min", min == Integer.MAX_VALUE ? 0 : min);
            o.addProperty("fps_max", max);
            o.addProperty("minutes", Math.round((System.currentTimeMillis() - started) / 60000.0));
            o.addProperty("version", SharedConstants.getCurrentVersion().name());
            o.addProperty("time", System.currentTimeMillis() / 1000);
            Files.createDirectories(home);
            Files.writeString(home.resolve("peregrine-session.json"), o.toString());
        } catch (Exception ignored) {
        }
    }
}
