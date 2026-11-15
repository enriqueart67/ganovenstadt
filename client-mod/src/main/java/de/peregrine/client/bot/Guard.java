package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Keeps you alive while the assistant works: fights monsters that come close, runs from creepers,
 * eats when hungry or hurt, rests when health is low and lights up dark tunnels with torches.
 * Runs before the job every tick; while it is busy the job waits.
 */
final class Guard {
    private Guard() {
    }

    private static int eatTicks, fleeTicks;
    private static boolean resting;
    private static BlockPos lastTorch;
    private static final Set<String> BAD_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chorus_fruit", "suspicious_stew");

    static void reset() {
        eatTicks = fleeTicks = 0;
        resting = false;
    }

    /** Returns true when it handled this tick (the job should wait). */
    static boolean tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        // 1) finish eating
        if (eatTicks > 0) {
            eatTicks--;
            mc.options.keyUse.setDown(eatTicks > 0);
            if (eatTicks > 0 && !p.isUsingItem() && eatTicks < 28) eatTicks = 0; // done (or it didn't start)
            if (eatTicks == 0) mc.options.keyUse.setDown(false);
            return true;
        }
        // 2) running from a creeper
        LivingEntity enemy = null, creeper = null;
        double ed = Double.MAX_VALUE;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(12),
                e -> e instanceof Enemy && e.isAlive() && e != p)) {
            double d = e.distanceTo(p);
            if (!p.hasLineOfSight(e)) continue;
            if (e instanceof Creeper && d < 6) creeper = e;
            else if (d < ed && d < 9 && Math.abs(e.getY() - p.getY()) < 4) {
                ed = d;
                enemy = e;
            }
        }
        if (creeper != null || fleeTicks > 0) {
            if (creeper != null) fleeTicks = 40;
            fleeTicks--;
            if (creeper != null) {
                Vec3 away = p.position().add(p.position().subtract(creeper.position()).normalize().scale(5));
                Bot.look(p, new Vec3(away.x, p.getEyeY(), away.z));
            }
            safeWalk(mc, true);
            Bot.note("Creeper! Getting away.");
            return true;
        }
        // 3) fighting
        if (enemy != null) {
            mc.options.keyUse.setDown(false);
            Bot.holdBest(mc, Guard::weaponScore);
            Bot.look(p, enemy.getEyePosition().subtract(0, 0.4, 0));
            if (ed <= 2.9) {
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                if (p.getAttackStrengthScale(0f) >= 0.92f) {
                    mc.gameMode.attack(p, enemy);
                    Bot.swing(p);
                }
            } else safeWalk(mc, false);
            Bot.note("Fighting a " + BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).getPath().replace('_', ' ') + ".");
            return true;
        }
        // 4) eating
        int food = p.getFoodData().getFoodLevel();
        float hp = p.getHealth();
        if ((food <= 14 || (hp < 16 && food < 20)) && Bot.holdBest(mc, Guard::foodScore)) {
            Bot.release(mc);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            mc.options.keyUse.setDown(true);
            eatTicks = 40;
            Bot.note("Eating.");
            return true;
        }
        // 5) resting when badly hurt (health comes back by itself with a full food bar)
        if (hp < 10 || (resting && hp < 18)) {
            if (food < 18) {
                Bot.stop("Your health is low and I have nothing to eat – I stopped. Eat something, then ask me again.");
                return true;
            }
            if (!resting) Bot.say("I'm hurt – resting until I'm healthy again.");
            resting = true;
            Bot.release(mc);
            return true;
        }
        resting = false;
        // 6) light up dark tunnels (doesn't take the tick)
        torch(mc, p);
        return false;
    }

    /** Walks forward unless that leads into lava or off a deep edge. */
    private static void safeWalk(Minecraft mc, boolean sprint) {
        LocalPlayer p = mc.player;
        BlockPos ahead = p.blockPosition().relative(p.getDirection());
        boolean danger = Bot.isLava(mc.level.getBlockState(ahead)) || Bot.isLava(mc.level.getBlockState(ahead.below()));
        int drop = 0;
        BlockPos f = ahead.below();
        while (drop < 4 && !Bot.solid(mc, f) && !Bot.isWater(mc.level.getBlockState(f))) {
            f = f.below();
            drop++;
        }
        if (drop >= 4) danger = true;
        mc.options.keyUp.setDown(!danger);
        mc.options.keySprint.setDown(sprint && !danger);
        mc.options.keyJump.setDown(!danger && p.horizontalCollision && p.onGround());
    }

    private static double weaponScore(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        double tier = id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3 : id.startsWith("stone") ? 2 : 1;
        if (id.endsWith("_sword")) return 10 + tier;
        if (id.endsWith("_axe")) return 5 + tier;
        return 0;
    }

    private static double foodScore(ItemStack s) {
        if (!s.has(DataComponents.FOOD)) return 0;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (BAD_FOOD.contains(id) || id.contains("golden_apple")) return 0; // keep golden apples for emergencies you choose
        return 1 + s.getCount() * 0.01;
    }

    private static void torch(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        if (!p.onGround() || mc.level.getBrightness(LightLayer.SKY, feet) > 0 || mc.level.getBrightness(LightLayer.BLOCK, feet) > 3) return;
        if (lastTorch != null && lastTorch.distManhattan(feet) < 7) return;
        if (!Bot.passable(mc, feet) || !Bot.solid(mc, feet.below())) return;
        if (Bot.count(mc, "torch") == 0) return;
        if (!Bot.holdBest(mc, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("torch") ? 1 : 0)) return;
        if (!BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath().equals("torch")) return; // still swapping in
        BlockPos floor = feet.below();
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
        lastTorch = feet;
    }
}
