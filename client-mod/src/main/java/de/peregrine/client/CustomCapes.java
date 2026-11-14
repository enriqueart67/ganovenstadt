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

    static Path dir() {
        Path h = Store.home();
        return h != null ? h.resolve("capes") : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("peregrine-capes");
    }

    /**
     * Turns a picture into an HD cape texture (1024×512, the cape layout ×16 plus the elytra area) –
     * the same conversion the launcher does – and stores it. Returns the new design name ("Photo: …").
     */
    public static String importPicture(Path src) throws java.io.IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(src.toFile());
        if (img == null) throw new java.io.IOException("not a supported picture (PNG, JPG, BMP or GIF)");
        final int S = 16;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(64 * S, 32 * S, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        java.awt.image.BufferedImage face = fit(img, 10 * S, 16 * S);
        // average colour for the edges
        long r = 0, gg = 0, b = 0, n = 0;
        for (int y = 0; y < face.getHeight(); y += 4) {
            for (int x = 0; x < face.getWidth(); x += 4) {
                int c = face.getRGB(x, y);
                r += (c >> 16) & 255;
                gg += (c >> 8) & 255;
                b += c & 255;
                n++;
            }
        }
        java.awt.Color avg = new java.awt.Color((int) (r / n), (int) (gg / n), (int) (b / n));
        g.setColor(avg);
        g.fillRect(S, 0, 20 * S, S);
        g.fillRect(0, S, S, 16 * S);
        g.fillRect(11 * S, S, S, 16 * S);
        g.drawImage(face, S, S, null);
        g.drawImage(face, 12 * S + 10 * S, S, 12 * S, 17 * S, 0, 0, face.getWidth(), face.getHeight(), null); // inside, mirrored
        g.drawImage(fit(img, 24 * S, 22 * S), 22 * S, 0, null);
        g.dispose();

        String stem = src.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String base = stem.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (base.length() > 24) base = base.substring(0, 24).trim();
        if (base.isEmpty()) base = "Cape";
        Path d = dir();
        Files.createDirectories(d);
        String name = base;
        for (int i = 2; Files.exists(d.resolve(name + ".png")); i++) name = base + " " + i;
        javax.imageio.ImageIO.write(out, "png", d.resolve(name + ".png").toFile());
        return "Photo: " + name;
    }

    /** Crops the picture to the aspect w:h (centred) and scales it. */
    private static java.awt.image.BufferedImage fit(java.awt.image.BufferedImage img, int w, int h) {
        int iw = img.getWidth(), ih = img.getHeight();
        double target = w / (double) h;
        int cw = iw, ch = ih;
        if (iw / (double) ih > target) cw = Math.max(1, (int) Math.round(ih * target));
        else ch = Math.max(1, (int) Math.round(iw / target));
        int cx = (iw - cw) / 2, cy = (ih - ch) / 2;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, w, h, cx, cy, cx + cw, cy + ch, null);
        g.dispose();
        return out;
    }

    /** Namen der hochgeladenen Umhänge (Dateiname ohne .png), alphabetisch. */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        Path d = dir();
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String[] allDesigns() {
        List<String> all = new ArrayList<>(List.of(Modules.Cape.DESIGNS));
        for (String n : names()) all.add("Photo: " + n);
        return all.toArray(String[]::new);
    }

    public static boolean isCustom(String design) {
        return design.startsWith("Photo: ");
    }

    /** Textur eines hochgeladenen Umhangs (bei Änderung der Datei neu geladen); null wenn nicht ladbar. */
    public static Identifier texture(String design) {
        String name = design.substring("Photo: ".length());
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
            PeregrineClient.LOG.warn("Could not load cape {}: {}", name, e.toString());
            LOADED.put(name, new Loaded(null, Long.MIN_VALUE, now));
            return null;
        }
    }
}
