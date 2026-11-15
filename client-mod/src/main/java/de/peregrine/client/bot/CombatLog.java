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

    static void start(String header) {
        lines = 0;
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), "# Peregrine combat log – " + header + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    static void log(String line) {
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
