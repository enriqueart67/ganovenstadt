package de.peregrine.client;

import com.mojang.blaze3d.platform.NativeImage;
import de.peregrine.client.gui.Type;
import de.peregrine.client.module.HudModule;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Minimap: a north-up surface map around the player (one pixel per block), shaded by height like
 * vanilla maps, with the player arrow and the waypoints of the current dimension.
 */
public final class Minimap extends HudModule {
    private static final int TEX = 128; // texture covers 128×128 blocks
    private static final Identifier ID = Identifier.fromNamespaceAndPath("peregrine", "minimap");

    public final NumberSetting size = add(new NumberSetting("mapsize", "Map size", 80, 48, 128, 8, " px"));
    public final BoolSetting coords = add(new BoolSetting("coords", "Coordinates below the map", true));
    public final BoolSetting waypoints = add(new BoolSetting("waypoints", "Show waypoints", true));

    private DynamicTexture texture;
    private NativeImage image;
    private int centerX, centerZ, age = Integer.MAX_VALUE;
    private ClientLevel mappedLevel;

    public Minimap() {
        super("minimap", "Minimap", "Top-down map of your surroundings with waypoints", false, 0.99, 0.01);
        anchor = Anchor.RIGHT;
    }

    @Override
    public void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (++age < 5 && mappedLevel == mc.level) return; // four refreshes per second
        age = 0;
        mappedLevel = mc.level;
        try {
            refresh(mc);
        } catch (Throwable e) {
            PeregrineClient.LOG.warn("Minimap refresh failed: {}", e.toString());
            enabled = false;
        }
    }

    private void refresh(Minecraft mc) {
        if (image == null) {
            image = new NativeImage(TEX, TEX, false);
            texture = new DynamicTexture(() -> "peregrine minimap", image);
            mc.getTextureManager().register(ID, texture);
        }
        ClientLevel level = mc.level;
        centerX = mc.player.getBlockX();
        centerZ = mc.player.getBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] north = new int[TEX]; // surface height of the row above, for shading
        for (int row = -1; row < TEX; row++) {
            int z = centerZ - TEX / 2 + row;
            for (int col = 0; col < TEX; col++) {
                int x = centerX - TEX / 2 + col;
                int argb = 0xFF14161A;
                int top = Integer.MIN_VALUE;
                if (level.hasChunk(x >> 4, z >> 4)) {
                    top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    MapColor color = MapColor.NONE;
                    // skip things without a map colour (glass, torches …)
                    for (int i = 0; i < 6 && top > level.getMinY(); i++, top--) {
                        pos.set(x, top, z);
                        BlockState st = level.getBlockState(pos);
                        color = st.getMapColor(level, pos);
                        if (color != MapColor.NONE) break;
                    }
                    if (color != MapColor.NONE) {
                        int n = north[col];
                        MapColor.Brightness b = n == Integer.MIN_VALUE || top == n ? MapColor.Brightness.NORMAL
                                : top > n ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
                        argb = color.calculateARGBColor(b);
                    }
                }
                north[col] = top;
                if (row >= 0) image.setPixel(col, row, argb);
            }
        }
        texture.upload();
    }

    @Override
    protected int[] draw(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
        int s = size.i();
        int total = s + (coords.on() ? 11 : 0);
        g.fill(-1, -1, s + 1, total + 1, 0xFF101010);
        if (image == null || mc.player == null) {
            g.fill(0, 0, s, s, 0xFF1C1F24);
            Type.center(g, "MINIMAP", Type.MONO, s / 2f, s / 2f - 3, 0.7f, 0xFF8C8C99);
            return new int[]{s, total};
        }
        // the texture is centred on the last refresh position – scroll by the distance moved since
        float offX = (float) (mc.player.getX() - centerX - 0.5), offZ = (float) (mc.player.getZ() - centerZ - 0.5);
        float u = (TEX - s) / 2f + offX, v = (TEX - s) / 2f + offZ;
        g.blit(RenderPipelines.GUI_TEXTURED, ID, 0, 0, u, v, s, s, TEX, TEX);

        if (waypoints.on()) {
            String dim = Waypoints.dimension(mc);
            for (Waypoints.Waypoint w : Waypoints.all(mc)) {
                if (!w.visible || !w.dimension.equals(dim)) continue;
                double dx = w.x + 0.5 - mc.player.getX(), dz = w.z + 0.5 - mc.player.getZ();
                // keep far waypoints on the rim
                double k = Math.max(Math.abs(dx), Math.abs(dz)) / (s / 2.0 - 3);
                if (k > 1) {
                    dx /= k;
                    dz /= k;
                }
                int px = (int) Math.round(s / 2.0 + dx), pz = (int) Math.round(s / 2.0 + dz);
                g.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFF101010);
                g.fill(px - 1, pz - 1, px + 2, pz + 2, w.color);
            }
        }
        arrow(g, s / 2f, s / 2f, mc.player.getYRot());
        Type.center(g, "N", Type.MONO, s / 2f, 1, 0.65f, 0xFFFFFFFF);
        if (coords.on()) {
            String c = mc.player.getBlockX() + "  " + mc.player.getBlockY() + "  " + mc.player.getBlockZ();
            Type.center(g, c, Type.MONO, s / 2f, s + 3, 0.7f, 0xFFFFFFFF);
        }
        return new int[]{s, total};
    }

    /** Player arrow: a small filled triangle pointing where the player looks. */
    private static void arrow(GuiGraphicsExtractor g, float cx, float cy, float yawDeg) {
        double a = Math.toRadians(yawDeg);
        // yaw 0 looks south (+Z = down on the map), 90 looks west
        double fx = -Math.sin(a), fz = Math.cos(a);
        double[][] tri = {{cx + fx * 5, cy + fz * 5}, {cx - fx * 3 - fz * 3.2, cy - fz * 3 + fx * 3.2}, {cx - fx * 3 + fz * 3.2, cy - fz * 3 - fx * 3.2}};
        fillTriangle(g, grow(tri, cx, cy, 1.5), 0xFF101010);
        fillTriangle(g, tri, 0xFFFF4F00);
    }

    private static double[][] grow(double[][] t, double cx, double cy, double by) {
        double[][] o = new double[3][2];
        for (int i = 0; i < 3; i++) {
            double dx = t[i][0] - cx, dy = t[i][1] - cy, len = Math.max(0.001, Math.hypot(dx, dy));
            o[i][0] = t[i][0] + dx / len * by;
            o[i][1] = t[i][1] + dy / len * by;
        }
        return o;
    }

    private static void fillTriangle(GuiGraphicsExtractor g, double[][] t, int color) {
        int minY = (int) Math.floor(Math.min(t[0][1], Math.min(t[1][1], t[2][1])));
        int maxY = (int) Math.ceil(Math.max(t[0][1], Math.max(t[1][1], t[2][1])));
        for (int y = minY; y <= maxY; y++) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, sy = y + 0.5;
            for (int i = 0; i < 3; i++) {
                double[] p = t[i], q = t[(i + 1) % 3];
                if ((p[1] <= sy && q[1] > sy) || (q[1] <= sy && p[1] > sy)) {
                    double x = p[0] + (sy - p[1]) / (q[1] - p[1]) * (q[0] - p[0]);
                    lo = Math.min(lo, x);
                    hi = Math.max(hi, x);
                }
            }
            if (hi > lo) g.fill((int) Math.round(lo), y, Math.max((int) Math.round(lo) + 1, (int) Math.round(hi)), y + 1, color);
        }
    }
}
