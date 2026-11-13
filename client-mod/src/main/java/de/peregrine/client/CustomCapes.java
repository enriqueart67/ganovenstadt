package de.peregrine.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Eigene Umhänge aus Fotos: Der Launcher wandelt Bilder in Umhang-Texturen um und legt sie in
 * &lt;peregrine.home&gt;/capes/*.png ab. Hier werden sie gelistet und als Textur registriert.
 */
public final class CustomCapes {
    private CustomCapes() {
    }

    private record Loaded(Identifier id, long modified, long checkedAt) {
    }

    private static final Map<String, Loaded> LOADED = new HashMap<>();

    private static Path dir() {
        Path h = Store.home();
        return h == null ? null : h.resolve("capes");
    }

    /** Namen der hochgeladenen Umhänge (Dateiname ohne .png), alphabetisch. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        Path d = dir();
        if (d == null || !Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String[] allDesigns() {
        List<String> all = new ArrayList<>(List.of(Modules.Cape.DESIGNS));
        for (String n : names()) all.add("Foto: " + n);
        return all.toArray(String[]::new);
    }

    public static boolean isCustom(String design) {
        return design.startsWith("Foto: ");
    }

    /** Textur eines hochgeladenen Umhangs (bei Änderung der Datei neu geladen); null wenn nicht ladbar. */
    public static Identifier texture(String design) {
        String name = design.substring("Foto: ".length());
        Path d = dir();
        if (d == null) return null;
        Path file = d.resolve(name + ".png");
        Loaded l = LOADED.get(name);
        long now = System.currentTimeMillis();
        if (l != null && now - l.checkedAt() < 2000) return l.id(); // Datei höchstens alle 2 s prüfen
        try {
            long mod = Files.getLastModifiedTime(file).toMillis();
            if (l != null && l.modified() == mod) {
                LOADED.put(name, new Loaded(l.id(), mod, now));
                return l.id();
            }
            String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
            Identifier id = Identifier.fromNamespaceAndPath("peregrine", "custom_cape/" + key + "_" + Integer.toHexString(name.hashCode()));
            NativeImage img;
            try (InputStream in = Files.newInputStream(file)) {
                img = NativeImage.read(in);
            }
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "peregrine cape " + name, img));
            LOADED.put(name, new Loaded(id, mod, now));
            return id;
        } catch (Exception e) {
            PeregrineClient.LOG.warn("Umhang „{}“ konnte nicht geladen werden: {}", name, e.toString());
            LOADED.put(name, new Loaded(null, Long.MIN_VALUE, now));
            return null;
        }
    }
}
