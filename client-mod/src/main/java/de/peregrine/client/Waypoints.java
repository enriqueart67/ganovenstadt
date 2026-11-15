package de.peregrine.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.peregrine.client.gui.Type;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Waypoints: saved per world/server and dimension, drawn as markers on the HUD and on the minimap. */
public final class Waypoints {
    private Waypoints() {
    }

    public static final int[] COLORS = {0xFFFF4F00, 0xFF2DC8B4, 0xFF4F8CFF, 0xFFF5C41E, 0xFF4AC86E, 0xFFF05A5A, 0xFFA06EF0, 0xFFFFFFFF};

    public static final class Waypoint {
        public String name;
        public int x, y, z;
        public String dimension;
        public int color;
        public boolean visible = true;

        public double distance(Vec3 from) {
            return Math.sqrt((x + 0.5 - from.x) * (x + 0.5 - from.x) + (y - from.y) * (y - from.y) + (z + 0.5 - from.z) * (z + 0.5 - from.z));
        }
    }

    private static final List<Waypoint> LIST = new ArrayList<>();
    private static String loadedKey;
    private static boolean wasDead;

    private static Path file() {
        Path h = Store.home();
        return (h != null ? h : FabricLoader.getInstance().getConfigDir()).resolve("peregrine-waypoints.json");
    }

    /** One list per server address or singleplayer world. */
    private static String worldKey(Minecraft mc) {
        if (mc.getCurrentServer() != null) return "server:" + mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (mc.getSingleplayerServer() != null) return "world:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return "unknown";
    }

    public static String dimension(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    private static JsonObject readAll() {
        try {
            if (Files.exists(file())) return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Could not read waypoints: {}", e.toString());
        }
        return new JsonObject();
    }

    private static void ensureLoaded(Minecraft mc) {
        String key = worldKey(mc);
        if (key.equals(loadedKey)) return;
        loadedKey = key;
        LIST.clear();
        JsonObject all = readAll();
        if (!all.has(key)) return;
        for (var el : all.getAsJsonArray(key)) {
            JsonObject o = el.getAsJsonObject();
            Waypoint w = new Waypoint();
            w.name = o.get("name").getAsString();
            w.x = o.get("x").getAsInt();
            w.y = o.get("y").getAsInt();
            w.z = o.get("z").getAsInt();
            w.dimension = o.get("dimension").getAsString();
            w.color = o.get("color").getAsInt();
            w.visible = !o.has("visible") || o.get("visible").getAsBoolean();
            LIST.add(w);
        }
    }

    public static void save() {
        if (loadedKey == null) return;
        try {
            JsonObject all = readAll();
            JsonArray arr = new JsonArray();
            for (Waypoint w : LIST) {
                JsonObject o = new JsonObject();
                o.addProperty("name", w.name);
                o.addProperty("x", w.x);
                o.addProperty("y", w.y);
                o.addProperty("z", w.z);
                o.addProperty("dimension", w.dimension);
                o.addProperty("color", w.color);
                o.addProperty("visible", w.visible);
                arr.add(o);
            }
            all.add(loadedKey, arr);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(all));
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Could not save waypoints: {}", e.toString());
        }
    }

    /** All waypoints of the current world (every dimension). */
    public static List<Waypoint> all(Minecraft mc) {
        if (mc.level == null) return List.of();
        ensureLoaded(mc);
        return LIST;
    }

    public static Waypoint addHere(Minecraft mc, String name) {
        ensureLoaded(mc);
        Waypoint w = new Waypoint();
        w.name = name != null ? name : "Waypoint " + (LIST.size() + 1);
        w.x = mc.player.getBlockX();
        w.y = mc.player.getBlockY();
        w.z = mc.player.getBlockZ();
        w.dimension = dimension(mc);
        w.color = COLORS[LIST.size() % COLORS.length];
        LIST.add(w);
        save();
        return w;
    }

    public static void remove(Waypoint w) {
        LIST.remove(w);
        save();
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            wasDead = false;
            return;
        }
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && Modules.WAYPOINTS.enabled && Modules.WAYPOINTS.death.on()) {
            ensureLoaded(mc);
            LIST.removeIf(w -> w.name.equals("Last death"));
            Waypoint w = addHere(mc, "Last death");
            w.color = 0xFFF05A5A;
            save();
        }
        wasDead = dead;
    }

    private static java.lang.reflect.Method cameraGetter;

    /** The main camera – the getter is called mainCamera() from 26.2 and getMainCamera() in 26.1. */
    public static Camera camera(Minecraft mc) {
        try {
            if (cameraGetter == null) {
                try {
                    cameraGetter = mc.gameRenderer.getClass().getMethod("mainCamera");
                } catch (NoSuchMethodException e) {
                    cameraGetter = mc.gameRenderer.getClass().getMethod("getMainCamera");
                }
            }
            return (Camera) cameraGetter.invoke(mc.gameRenderer);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String meters(double d) {
        return d >= 1000 ? String.format(Locale.ROOT, "%.1f km", d / 1000) : Math.round(d) + " m";
    }

    /** Projects the waypoints of this dimension onto the screen. */
    public static void render(GuiGraphicsExtractor g, Minecraft mc) {
        if (!Modules.WAYPOINTS.enabled || mc.player == null || mc.level == null) return;
        Camera cam = camera(mc);
        if (cam == null || !cam.isInitialized()) return;
        List<Waypoint> list = all(mc);
        if (list.isEmpty()) return;
        String dim = dimension(mc);
        Vec3 c = cam.position();
        double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = -Math.sin(yaw) * Math.sin(pitch), uy = Math.cos(pitch), uz = Math.cos(yaw) * Math.sin(pitch);
        int w = g.guiWidth(), h = g.guiHeight();
        double focal = (h / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2.0);
        int margin = 14;
        for (Waypoint wp : list) {
            if (!wp.visible || !wp.dimension.equals(dim)) continue;
            double dx = wp.x + 0.5 - c.x, dy = wp.y + 1.0 - c.y, dz = wp.z + 0.5 - c.z;
            double dist = wp.distance(mc.player.position());
            double camX = dx * rx + dz * rz, camY = dx * ux + dy * uy + dz * uz, camZ = dx * fx + dy * fy + dz * fz;
            double sx, sy;
            boolean onScreen = camZ > 0.05;
            if (onScreen) {
                sx = w / 2.0 + camX / camZ * focal;
                sy = h / 2.0 - camY / camZ * focal;
                onScreen = sx >= margin && sx <= w - margin && sy >= margin && sy <= h - margin;
            } else {
                sx = w / 2.0 + camX * 1e6;
                sy = h / 2.0 - camY * 1e6;
            }
            if (!onScreen) {
                // pin to the screen edge in the waypoint's direction
                double ax = camZ > 0.05 ? sx - w / 2.0 : camX, ay = camZ > 0.05 ? sy - h / 2.0 : -camY;
                if (camZ <= 0.05 && Math.abs(ax) < 1e-6 && Math.abs(ay) < 1e-6) ax = 1;
                double k = Math.min((w / 2.0 - margin) / Math.max(1e-6, Math.abs(ax)), (h / 2.0 - margin) / Math.max(1e-6, Math.abs(ay)));
                sx = w / 2.0 + ax * k;
                sy = h / 2.0 + ay * k;
            }
            int x = (int) Math.round(sx), y = (int) Math.round(sy);
            // marker: small square standing on its corner
            for (int i = -3; i <= 3; i++) {
                int half = 3 - Math.abs(i);
                g.fill(x - half - 1, y + i, x + half + 2, y + i + 1, 0xFF101010);
            }
            for (int i = -2; i <= 2; i++) {
                int half = 2 - Math.abs(i);
                g.fill(x - half, y + i, x + half + 1, y + i + 1, wp.color);
            }
            // name near the crosshair or always (setting); distance always
            boolean near = Math.abs(sx - w / 2.0) < 60 && Math.abs(sy - h / 2.0) < 40;
            String label = (Modules.WAYPOINTS.names.on() || near) ? wp.name + "  " + meters(dist) : meters(dist);
            float scale = 0.8f;
            int tw = Type.width(label, Type.BOLD, scale);
            int tx = Math.max(2, Math.min(w - tw - 2, x - tw / 2));
            int ty = y + 6 > h - 12 ? y - 14 : y + 6;
            g.fill(tx - 3, ty - 1, tx + tw + 3, ty + 9, 0x90101010);
            Type.left(g, label, Type.BOLD, tx, ty, scale, 0xFFFFFFFF);
        }
    }
}
