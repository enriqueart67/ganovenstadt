package de.peregrine.client.bot;

import de.peregrine.client.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A plain text log of every fight (peregrine-combat.log next to the mod settings): launches, smashes with distance
 * and fall height, hits and misses, crystals, shields, golden apples. Started fresh with each fight, kept short.
 * Meant for tuning – it is what to send when the bot misses.
 */
public final class CombatLog {
    private CombatLog() {
    }

    private static int lines;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SS");

    public static Path file() {
        Path h = Store.home();
        return (h != null ? h : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()).resolve("peregrine-combat.log");
    }

    private static final java.util.Map<String, Integer> COUNTS = new java.util.TreeMap<>();

    /** One summary line at the end of a fight: result, time, damage both ways, and how often each move came up. */
    static void summary(String result, double seconds, double dealt, double taken) {
        StringBuilder b = new StringBuilder("RESULT " + result + "  time " + f(seconds) + " s  damage dealt " + f(dealt) + "  taken " + f(taken) + "  |");
        COUNTS.forEach((k, v) -> b.append(' ').append(k).append('=').append(v));
        log(b.toString());
        de.peregrine.client.bot.Tuning.fightEnded(result, seconds, dealt, taken, COUNTS);
    }

    static void start(String header) {
        lines = 0;
        COUNTS.clear();
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), "# Peregrine combat log – " + header + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    static void log(String line) {
        String key = line.startsWith("mode ") || line.startsWith("RESULT") ? null : line.split("[ :]", 2)[0];
        if (key != null && !key.isEmpty()) COUNTS.merge(key, 1, Integer::sum);
        if (++lines > 4000) return;
        try {
            Files.writeString(file(), LocalTime.now().format(TIME) + "  " + line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
