package de.peregrine.client.bot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Explosion damage the way the server works it out, so crystal and anchor spots are judged on what really lands:
 * distance from the feet, how much of the body the blast can see (the same ray grid as vanilla), difficulty,
 * armor and toughness, protection / blast protection and resistance. Crystal = power 6, respawn anchor = power 5.
 */
final class Blast {
    private Blast() {
    }

    static final float CRYSTAL = 6f, ANCHOR = 5f;

    /** Damage to {@code e} from an explosion at {@code c}, after everything that reduces it (absorption not counted). */
    static double damage(LivingEntity e, Vec3 c, float power) {
        return damage(e, e.position(), c, power, true);
    }

    /** As {@link #damage(LivingEntity, Vec3, float)} with the body moved to {@code feet} (where they are about to be). */
    static double damage(LivingEntity e, Vec3 feet, Vec3 c, float power, boolean exposure) {
        return damage(e, feet, c, power, exposure, null);
    }

    /**
     * {@code ignore}: a block that is gone when it goes off (a respawn anchor removes itself first), so a ray that
     * reaches it first counts as free.
     */
    static double damage(LivingEntity e, Vec3 feet, Vec3 c, float power, boolean exposure, net.minecraft.core.BlockPos ignore) {
        double radius = power * 2.0;
        double dist = feet.distanceTo(c) / radius;
        if (dist > 1) return 0;
        double seen = exposure ? seen(e.level(), c, e.getBoundingBox().move(feet.subtract(e.position())), e, ignore) : 1;
        if (seen <= 0) return 0;
        double impact = (1 - dist) * seen;
        double dmg = (impact * impact + impact) / 2 * 7 * radius + 1;
        if (e instanceof Player) {
            switch (e.level().getDifficulty()) {
                case PEACEFUL -> {
                    return 0;
                }
                case EASY -> dmg = Math.min(dmg / 2 + 1, dmg);
                case HARD -> dmg *= 1.5;
                default -> {
                }
            }
        }
        return reduce(e, dmg);
    }

    /** Vanilla's exposure: rays from a grid over the hitbox to the blast; the share that gets through. */
    static double seen(Level level, Vec3 c, AABB box, LivingEntity e, net.minecraft.core.BlockPos ignore) {
        double sx = 1.0 / ((box.maxX - box.minX) * 2 + 1), sy = 1.0 / ((box.maxY - box.minY) * 2 + 1), sz = 1.0 / ((box.maxZ - box.minZ) * 2 + 1);
        double ox = (1 - Math.floor(1 / sx) * sx) / 2, oz = (1 - Math.floor(1 / sz) * sz) / 2;
        int free = 0, all = 0;
        for (double a = 0; a <= 1; a += sx)
            for (double b = 0; b <= 1; b += sy)
                for (double d = 0; d <= 1; d += sz) {
                    Vec3 from = new Vec3(box.minX + (box.maxX - box.minX) * a + ox, box.minY + (box.maxY - box.minY) * b, box.minZ + (box.maxZ - box.minZ) * d + oz);
                    var hit = level.clip(new ClipContext(from, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
                    if (hit.getType() == HitResult.Type.MISS || ignore != null && hit instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getBlockPos().equals(ignore)) free++;
                    all++;
                }
        return all == 0 ? 0 : (double) free / all;
    }

    /** Armor + toughness, then protection enchantments (blast protection counts double), then resistance. */
    static double reduce(LivingEntity e, double dmg) {
        double[] at = armor(e);
        double armor = at[0], tough = at[1];
        double f = Math.max(armor * 0.2, Math.min(20, armor - dmg / (2 + tough / 4)));
        dmg *= 1 - f / 25;
        int epf = 0;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack it = e.getItemBySlot(s);
            epf += level(it, "protection") + 2 * level(it, "blast_protection");
        }
        dmg *= 1 - Math.min(20, epf) / 25.0;
        var res = e.getEffect(MobEffects.RESISTANCE);
        if (res != null) dmg *= Math.max(0, 1 - (res.getAmplifier() + 1) * 0.2);
        return Math.max(0, dmg);
    }

    /**
     * Armor and toughness. The attribute is right for us; for other players it can lag, so it is also counted from the
     * worn pieces and the larger value is used (better to guess them tough than to waste crystals).
     */
    static double[] armor(LivingEntity e) {
        double armor = 0, tough = 0;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            String id = BuiltInRegistries.ITEM.getKey(e.getItemBySlot(s).getItem()).getPath();
            if (id.equals("turtle_helmet")) {
                armor += 2;
                continue;
            }
            int i = s == EquipmentSlot.HEAD ? 0 : s == EquipmentSlot.CHEST ? 1 : s == EquipmentSlot.LEGS ? 2 : 3;
            int[] v = id.startsWith("netherite_") ? new int[]{3, 8, 6, 3} : id.startsWith("diamond_") ? new int[]{3, 8, 6, 3}
                    : id.startsWith("iron_") ? new int[]{2, 6, 5, 2} : id.startsWith("chainmail_") ? new int[]{2, 5, 4, 1}
                    : id.startsWith("golden_") ? new int[]{2, 5, 3, 1} : id.startsWith("copper_") ? new int[]{2, 4, 3, 1}
                    : id.startsWith("leather_") ? new int[]{1, 3, 2, 1} : null;
            if (v == null || !(id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots"))) continue;
            armor += v[i];
            tough += id.startsWith("netherite_") ? 3 : id.startsWith("diamond_") ? 2 : 0;
        }
        try {
            armor = Math.max(armor, e.getArmorValue());
        } catch (Throwable ignored) {
        }
        return new double[]{Math.min(30, armor), tough};
    }

    /** Level of an enchantment on an item (0 if not there). */
    static int level(ItemStack s, String ench) {
        if (s.isEmpty()) return 0;
        try {
            for (var en : s.getEnchantments().entrySet()) {
                String name = en.getKey().getRegisteredName();
                if (name.equals("minecraft:" + ench) || name.equals(ench)) return en.getIntValue();
            }
        } catch (Throwable t) {
            String all = String.valueOf(s.getEnchantments());
            return all.contains("minecraft:" + ench) ? 1 : 0;
        }
        return 0;
    }
}
