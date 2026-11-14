package de.peregrine.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Particle cosmetics around your own character: wings, halo, aura and trail. Spawned client side,
 * so only you see them (best in third person, F5).
 */
public final class Effects {
    private Effects() {
    }

    private static int tick;
    private static Vec3 lastPos;

    /** Colour of a setting; "Rainbow" cycles through the hues over time. */
    private static int color(String mode, double phase) {
        return switch (mode) {
            case "Orange" -> 0xFF4F00;
            case "Gold" -> 0xFFC832;
            case "White" -> 0xF4F4F4;
            case "Ice" -> 0x8FE3FF;
            case "Violet" -> 0xA06EF0;
            case "Crimson" -> 0xE0283C;
            case "Emerald" -> 0x2DD27A;
            case "Shadow" -> 0x1A1A22;
            default -> hsv((float) ((tick * 0.01 + phase) % 1.0)); // Rainbow
        };
    }

    private static int hsv(float h) {
        float r = Math.abs(h * 6 - 3) - 1, g = 2 - Math.abs(h * 6 - 2), b = 2 - Math.abs(h * 6 - 4);
        int ri = (int) (Math.max(0, Math.min(1, r)) * 255), gi = (int) (Math.max(0, Math.min(1, g)) * 255), bi = (int) (Math.max(0, Math.min(1, b)) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static void dust(Minecraft mc, double x, double y, double z, int rgb, float size) {
        mc.level.addParticle(new DustParticleOptions(rgb, size), x, y, z, 0, 0, 0);
    }

    public static void tick(Minecraft mc) {
        var m = Modules.EFFECTS;
        LocalPlayer p = mc.player;
        if (!m.enabled || p == null || mc.level == null || p.isInvisible() || p.isSpectator()) {
            lastPos = null;
            return;
        }
        tick++;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        boolean show = !(firstPerson && m.hideFirstPerson.on());
        double x = p.getX(), y = p.getY(), z = p.getZ();
        double yaw = Math.toRadians(p.yBodyRot);
        // body axes: forward (where the body faces), right
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -fz, rz = fx;
        boolean sneak = p.isShiftKeyDown();
        double shoulder = y + (sneak ? 1.15 : 1.35);

        if (show && !m.wings.is("Off")) wings(mc, m, x, shoulder, z, fx, fz, rx, rz);
        if (show && !m.halo.is("Off")) halo(mc, m, x, y + p.getBbHeight() + 0.25, z);
        if (show && !m.aura.is("Off") && tick % 3 == 0) aura(mc, m.aura.get(), x, y, z);
        if (!m.trail.is("Off")) trail(mc, m, p, x, y, z);
    }

    private static void wings(Minecraft mc, Modules.EffectsModule m, double x, double y, double z, double fx, double fz, double rx, double rz) {
        if (tick % 2 != 0) return; // dust lives long enough – every other tick keeps it light
        float flap = (float) Math.sin(tick * 0.18) * 0.22f;
        double back = -0.32; // behind the back
        String style = m.wings.get();
        int rows = style.equals("Large") ? 9 : 6;
        double span = style.equals("Large") ? 1.6 : 1.1;
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < rows; i++) {
                double u = (i + 1) / (double) rows; // 0 near the spine → 1 at the tip
                // wing outline: rises towards the tip, feathers hang down
                double out = 0.15 + u * span;
                double lift = Math.sin(u * Math.PI * 0.85) * 0.55 + u * 0.15;
                double sweep = back - u * (0.35 + flap); // tips sweep backwards and flap
                int feathers = Math.max(1, (int) Math.round((1 - u * 0.6) * 4));
                for (int k = 0; k < feathers; k++) {
                    double drop = k * 0.16 * (1.1 - u * 0.5);
                    double px = x + rx * out * side + fx * sweep;
                    double pz = z + rz * out * side + fz * sweep;
                    double py = y + lift - drop;
                    int c = color(m.wingColor.get(), u * 0.35 + k * 0.05);
                    dust(mc, px, py, pz, c, 0.75f);
                }
            }
        }
    }

    private static void halo(Minecraft mc, Modules.EffectsModule m, double x, double y, double z) {
        int n = 14;
        double r = 0.32, spin = tick * 0.06;
        for (int i = 0; i < n; i++) {
            if ((i + tick) % 2 != 0) continue;
            double a = spin + i * Math.PI * 2 / n;
            int c = color(m.haloColor.get(), i / (double) n);
            dust(mc, x + Math.cos(a) * r, y + Math.sin(tick * 0.08) * 0.03, z + Math.sin(a) * r, c, 0.6f);
        }
    }

    private static void aura(Minecraft mc, String mode, double x, double y, double z) {
        ParticleOptions type = switch (mode) {
            case "Flames" -> ParticleTypes.FLAME;
            case "Soul" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "Enchant" -> ParticleTypes.ENCHANT;
            case "Portal" -> ParticleTypes.PORTAL;
            case "Snow" -> ParticleTypes.SNOWFLAKE;
            case "Sparks" -> ParticleTypes.ELECTRIC_SPARK;
            default -> ParticleTypes.END_ROD; // Stars
        };
        // two points spiralling upwards around the body
        for (int i = 0; i < 2; i++) {
            if (i == 1 && tick % 6 != 0) continue;
            double a = tick * 0.25 + i * Math.PI;
            double h = (tick % 40) / 40.0 * 2.0;
            mc.level.addParticle(type, x + Math.cos(a) * 0.7, y + h, z + Math.sin(a) * 0.7, 0, 0.01, 0);
        }
    }

    private static void trail(Minecraft mc, Modules.EffectsModule m, LocalPlayer p, double x, double y, double z) {
        Vec3 now = p.position();
        boolean moved = lastPos != null && now.distanceToSqr(lastPos) > 0.0025;
        lastPos = now;
        if (!moved || tick % 2 != 0) return;
        double jx = (Math.random() - 0.5) * 0.3, jz = (Math.random() - 0.5) * 0.3;
        switch (m.trail.get()) {
            case "Hearts" -> mc.level.addParticle(ParticleTypes.HEART, x + jx, y + 0.3, z + jz, 0, 0, 0);
            case "Cherry" -> mc.level.addParticle(ParticleTypes.CHERRY_LEAVES, x + jx, y + 0.6, z + jz, 0, 0, 0);
            case "Notes" -> mc.level.addParticle(ParticleTypes.NOTE, x + jx, y + 0.4, z + jz, (tick % 24) / 24.0, 0, 0);
            case "Flames" -> mc.level.addParticle(ParticleTypes.FLAME, x + jx, y + 0.05, z + jz, 0, 0.01, 0);
            case "Stars" -> mc.level.addParticle(ParticleTypes.END_ROD, x + jx, y + 0.2, z + jz, 0, 0.02, 0);
            default -> { // Rainbow dust ribbon
                for (int i = 0; i < 3; i++) dust(mc, x + jx * 0.5, y + 0.1 + i * 0.12, z + jz * 0.5, hsv((float) ((tick * 0.02 + i * 0.08) % 1.0)), 1.0f);
            }
        }
    }
}
