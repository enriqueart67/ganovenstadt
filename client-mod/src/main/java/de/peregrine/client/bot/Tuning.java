package de.peregrine.client.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the bot learned from its own fights, kept in peregrine-tuning.json next to the settings:
 * <ul>
 *   <li>insta cart: extra draw ticks per distance band – a miss adds draw (faster, flatter arrow), a hit eases it back;</li>
 *   <li>mace: how far beside the target to land – a miss that came down far away pulls the landing point in;</li>
 *   <li>elytra mace: how much air steering to count on when dropping – misses make it drop later (closer);</li>
 *   <li>per technique: tries and hits, and fights won / lost, for the overview.</li>
 * </ul>
 * Small, bounded steps, so one unlucky fight can't wreck it.
 */
public final class Tuning {
    private Tuning() {
    }

    static final class Data {
        Map<String, Double> instaBias = new TreeMap<>();
        double maceOffset = 1.1, elytraFix = 0.12, launchTilt = 4;
        Map<String, int[]> tech = new TreeMap<>(); // name -> {tries, hits}
        int won, lost;
    }

    private static Data data;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        Path h = de.peregrine.client.Store.home();
        return (h != null ? h : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()).resolve("peregrine-tuning.json");
    }

    private static Data d() {
        if (data == null) {
            try {
                if (Files.exists(file())) data = GSON.fromJson(Files.readString(file()), Data.class);
            } catch (Exception ignored) {
            }
            if (data == null) data = new Data();
            if (data.instaBias == null) data.instaBias = new TreeMap<>();
            if (data.tech == null) data.tech = new TreeMap<>();
        }
        return data;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(d()));
        } catch (Exception ignored) {
        }
    }

    private static void count(String tech, boolean hit) {
        int[] c = d().tech.computeIfAbsent(tech, k -> new int[2]);
        c[0]++;
        if (hit) c[1]++;
    }

    /** Extra draw ticks for an insta cart at this distance (whole blocks). */
    static int instaBias(double dist) {
        return (int) Math.round(d().instaBias.getOrDefault(String.valueOf((int) dist), 0.0));
    }

    static void insta(double dist, boolean hit) {
        String k = String.valueOf((int) dist);
        double b = d().instaBias.getOrDefault(k, 0.0);
        b = hit ? Math.max(0, b - 0.15) : Math.min(4, b + 0.6);
        d().instaBias.put(k, b);
        count("insta cart", hit);
        CombatLog.log("tuning insta " + k + " blocks: extra draw " + String.format(java.util.Locale.ROOT, "%.2f", b));
        save();
    }

    static double maceOffset() {
        return d().maceOffset;
    }

    /** A mace jump ended: hit, or landed missDist blocks from them. */
    static void mace(boolean hit, double missDist) {
        if (!hit && missDist > 1.8) d().maceOffset = Math.max(0.6, d().maceOffset - 0.1);
        if (hit) d().maceOffset = Math.min(1.3, d().maceOffset + 0.02);
        count("mace", hit);
        save();
    }

    /** Degrees the wind-charge launch tilts away from straight down per block of distance (more = more push towards them). */
    static double launchTilt() {
        return d().launchTilt <= 0 ? 4 : d().launchTilt;
    }

    /** A launch missed: landed about where it started (too little push) → tilt more; overshot → tilt less. */
    static void launch(double launchedFrom, double landedFrom) {
        if (landedFrom > launchedFrom * 0.7) d().launchTilt = Math.min(8, launchTilt() + 0.5);
        else if (landedFrom > 2.5) d().launchTilt = Math.max(2, launchTilt() - 0.4);
        save();
    }

    static double elytraFix() {
        return d().elytraFix;
    }

    static void elytra(boolean hit) {
        d().elytraFix = hit ? Math.min(0.14, d().elytraFix + 0.003) : Math.max(0.05, d().elytraFix - 0.01);
        count("elytra mace", hit);
        save();
    }

    static void technique(String name, boolean hit) {
        count(name, hit);
        save();
    }

    static void fightEnded(String result, double seconds, double dealt, double taken, Map<String, Integer> counts) {
        if (result.startsWith("WON")) d().won++;
        else if (result.startsWith("LOST")) d().lost++;
        save();
    }

    /** Short overview for the assistant chat. */
    public static String overview() {
        Data x = d();
        StringBuilder b = new StringBuilder("Fights: " + x.won + " won, " + x.lost + " lost.");
        x.tech.forEach((k, v) -> b.append("  ").append(k).append(": ").append(v[1]).append("/").append(v[0]).append(" hit"));
        b.append(String.format(java.util.Locale.ROOT, ".  Mace lands %.1f beside them, launch tilt %.1f°/block, elytra air steering %.3f.", x.maceOffset, launchTilt(), x.elytraFix));
        return b.toString();
    }
}
