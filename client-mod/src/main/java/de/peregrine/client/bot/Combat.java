package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Random;

/**
 * Player-versus-player fighting. Picks the technique from what is in the inventory and the situation:
 * <ul>
 *   <li><b>Crystal</b> – end crystals on obsidian/bedrock next to the target (places obsidian itself if needed),
 *   chooses the spot with the most damage to the target and little to itself, breaks the crystal at once.</li>
 *   <li><b>Mace</b> – wind charge at its own feet to fly up, then a mace smash while falling onto the target.</li>
 *   <li><b>Sword / axe</b> – sprint hits with a w-tap (sprint reset) for knockback, critical hits when the target is
 *   in the air, strafing, keeping at the edge of reach, axe against a raised shield.</li>
 *   <li><b>Defence</b> – totem back into the off hand right after it pops, golden apple when low.</li>
 * </ul>
 * Allowed only where the assistant is allowed at all (singleplayer, own LAN world, servers where you are operator).
 */
public final class Combat {
    private Combat() {
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static boolean has(Minecraft mc, String item) {
        return Bot.count(mc, item) > 0;
    }

    private static boolean holding(LocalPlayer p, String item) {
        return id(p.getMainHandItem()).equals(item);
    }

    /** Explosion damage of an end crystal (power 6) at distance d, before armor – good enough to rank spots. */
    static double crystalDamage(LivingEntity e, Vec3 crystal) {
        double d = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(crystal);
        if (d >= 12) return 0;
        double impact = 1 - d / 12;
        return (impact * impact + impact) / 2 * 7 * 12 + 1;
    }

    public static final class Duel implements Bot.Task {
        private final String wanted; // player name, entity type or null = nearest player
        private LivingEntity target;
        private Navigator nav;
        private String mode = "looking", targetName = "";
        private int ticks, lost, wtap, strafeT, crystalCd, navAge, maceStage;
        private boolean strafeLeft, hadTarget;
        private final Random rnd = new Random();

        public Duel(String wanted) {
            this.wanted = wanted == null || wanted.isBlank() ? null : wanted.toLowerCase(Locale.ROOT).trim();
        }

        @Override
        public String status() {
            Minecraft mc = Minecraft.getInstance();
            String hp = target != null && target.isAlive() ? "  ·  their health " + Math.round(target.getHealth()) : "";
            return "Fighting " + (targetName.isEmpty() ? (wanted == null ? "the nearest player" : wanted) : targetName) + "  ·  " + mode + hp;
        }

        private LivingEntity find(Minecraft mc, LocalPlayer p) {
            LivingEntity best = null;
            double bd = Double.MAX_VALUE;
            for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(96), e -> e.isAlive() && e != p)) {
                boolean ok;
                if (e instanceof Player pl) {
                    if (pl.isSpectator() || pl.isCreative()) continue;
                    String n = pl.getName().getString().toLowerCase(Locale.ROOT);
                    ok = wanted == null || n.equals(wanted) || n.startsWith(wanted);
                } else {
                    ok = wanted != null && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' ').startsWith(wanted.replaceAll("s$", ""));
                }
                double d = e.distanceTo(p);
                if (ok && d < bd) {
                    bd = d;
                    best = e;
                }
            }
            return best;
        }

        @Override
        public boolean tick(Minecraft mc) {
            LocalPlayer p = mc.player;
            ticks++;
            if (target == null || !target.isAlive() || target.isRemoved()) {
                if (hadTarget && target != null && !target.isAlive()) {
                    Bot.say("Won – " + targetName + " is down.");
                    return true;
                }
                target = find(mc, p);
                if (target == null) {
                    if (++lost > 20 * 15) {
                        Bot.stop("I can't find " + (wanted == null ? "anyone to fight" : "\"" + wanted + "\"") + " nearby.");
                    }
                    Bot.release(mc);
                    return false;
                }
                lost = 0;
                hadTarget = true;
                targetName = target.getName().getString();
                Bot.say("Target: " + targetName + "." + (Bot.count(mc, "totem_of_undying") > 0 || Guard.hasTotem(p) ? " Totem in the off hand." : " No totem – careful.")
                        + (Bot.count(mc, "end_crystal") > 0 ? " Crystals ready." : "") + (Bot.count(mc, "mace") > 0 ? " Mace ready." : ""));
            }
            double d = p.distanceTo(target);

            // ---- defence first
            Guard.gear(mc); // totem back in the off hand immediately after a pop, best armor on
            float hp = p.getHealth() + p.getAbsorptionAmount();
            if ((hp < 9 || (hp < 13 && d > 5)) && Guard.eatNow(mc, true)) {
                mode = "healing";
                return false;
            }

            // ---- getting there
            if (d > 10) {
                mode = "closing in";
                if (nav == null || ++navAge > 40) {
                    nav = new Navigator(target.blockPosition(), 3.0, null);
                    navAge = 0;
                }
                if (nav.tick(mc)) nav = null;
                return false;
            }
            nav = null;

            // ---- pick a technique
            if (has(mc, "end_crystal") && d < 7.5 && crystal(mc, p)) {
                mode = "crystal";
                return false;
            }
            if (has(mc, "mace") && mace(mc, p, d)) {
                mode = "mace";
                return false;
            }
            melee(mc, p, d);
            return false;
        }

        // ------------------------------------------------------------------ sword / axe
        private void melee(Minecraft mc, LocalPlayer p, double d) {
            boolean shield = target.isBlocking() && Bot.count(mc, "wooden_axe", "stone_axe", "iron_axe", "golden_axe", "diamond_axe", "netherite_axe") > 0;
            if (shield) Bot.holdBest(mc, s -> id(s).endsWith("_axe") ? 10 : 0); // an axe hit disables the shield
            else Bot.holdBest(mc, Guard::weaponScore);
            mode = shield ? "axe vs shield" : "sword";
            Vec3 aim = target.getEyePosition().subtract(0, target.getBbHeight() * 0.25, 0);
            Bot.look(p, aim);
            boolean ready = p.getAttackStrengthScale(0f) >= 0.93f;
            // strafe in an uneven rhythm so the opponent can't time it
            if (--strafeT <= 0) {
                strafeLeft = !strafeLeft;
                strafeT = 7 + rnd.nextInt(10);
            }
            mc.options.keyLeft.setDown(strafeLeft);
            mc.options.keyRight.setDown(!strafeLeft);
            if (wtap > 0) { // sprint reset: let go of forward for a moment after a hit
                wtap--;
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(wtap > 1);
                return;
            }
            mc.options.keyDown.setDown(false);
            if (d > 3.1) { // chase
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
                mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
                return;
            }
            // in range: stay at the edge of reach, hit when the swing is fully charged
            mc.options.keyUp.setDown(d > 2.6);
            mc.options.keySprint.setDown(true);
            boolean targetAirborne = !target.onGround();
            boolean wantCrit = targetAirborne || target.getHealth() < 6; // crits finish, sprint hits push away
            if (ready && wantCrit && p.onGround() && !p.isInWater()) {
                mc.options.keyJump.setDown(true);
                return;
            }
            mc.options.keyJump.setDown(false);
            boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.08;
            if (ready && d <= 3.0 && (!wantCrit || falling || p.isInWater())) {
                mc.gameMode.attack(p, target);
                Bot.swing(p);
                if (!wantCrit) wtap = 3;
            }
        }

        // ------------------------------------------------------------------ mace
        private boolean mace(Minecraft mc, LocalPlayer p, double d) {
            boolean charges = has(mc, "wind_charge");
            // stage 1: on the ground, close enough – wind charge at our own feet to launch up
            if (maceStage == 0 && charges && p.onGround() && d < 5.5 && d > 1.2) {
                if (!Bot.holdBest(mc, s -> id(s).equals("wind_charge") ? 1 : 0)) return false;
                if (!holding(p, "wind_charge")) return true; // still swapping
                if (p.getCooldowns().isOnCooldown(p.getMainHandItem())) return false;
                p.setXRot(90f);
                mc.options.keyUp.setDown(false);
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                Bot.swing(p);
                maceStage = 1;
                return true;
            }
            // stage 2: in the air – drift over the target, mace out, smash on the way down
            if (maceStage == 1 || p.fallDistance > 1.5) {
                if (p.onGround() && maceStage == 1 && ticks % 5 == 0) {
                    maceStage = 0; // landed
                    return false;
                }
                Bot.holdBest(mc, s -> id(s).equals("mace") ? 1 : 0);
                Bot.look(p, target.getEyePosition().subtract(0, 0.5, 0));
                mc.options.keyUp.setDown(d > 1.0);
                boolean falling = p.getDeltaMovement().y < -0.1;
                if (falling && d <= 3.2 && holding(p, "mace")) {
                    mc.gameMode.attack(p, target);
                    Bot.swing(p);
                    maceStage = 0;
                }
                return true;
            }
            return false;
        }

        // ------------------------------------------------------------------ crystal
        private boolean crystal(Minecraft mc, LocalPlayer p) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            // 1) break any crystal that hurts them more than us
            EndCrystal hit = null;
            double hitScore = 0;
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(6), c -> c.isAlive())) {
                if (eye.distanceTo(c.position().add(0, 1, 0)) > 4.6) continue;
                double them = crystalDamage(target, c.position()), us = crystalDamage(p, c.position());
                if (us >= hp - 3 && !Guard.hasTotem(p)) continue; // would kill us
                double score = them - us * 0.6;
                if (them > 2 && score > hitScore) {
                    hitScore = score;
                    hit = c;
                }
            }
            if (hit != null) {
                Bot.look(p, hit.position().add(0, 0.8, 0));
                mc.gameMode.attack(p, hit);
                Bot.swing(p);
                return true;
            }
            if (crystalCd > 0) {
                crystalCd--;
                return true;
            }
            // 2) place a crystal on the best obsidian/bedrock block next to them
            BlockPos t = target.blockPosition(), best = null;
            double bestScore = 0;
            for (BlockPos b : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
                String bid = Bot.id(mc.level.getBlockState(b));
                if (!bid.equals("obsidian") && !bid.equals("bedrock")) continue;
                BlockPos a1 = b.above(), a2 = b.above(2);
                if (!mc.level.getBlockState(a1).isAir() || !mc.level.getBlockState(a2).isAir()) continue;
                if (!mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(a1)).isEmpty()) continue;
                if (eye.distanceTo(Vec3.atCenterOf(b).add(0, 0.5, 0)) > 4.5) continue;
                Vec3 c = Vec3.atBottomCenterOf(a1);
                double them = crystalDamage(target, c), us = crystalDamage(p, c);
                if (us >= hp - 3 && !Guard.hasTotem(p)) continue;
                double score = them - us * 0.6;
                if (them >= 4 && score > bestScore) {
                    bestScore = score;
                    best = b.immutable();
                }
            }
            if (best != null) {
                if (!Bot.holdBest(mc, s -> id(s).equals("end_crystal") ? 1 : 0)) return false;
                if (!holding(p, "end_crystal")) return true;
                Bot.look(p, Vec3.atCenterOf(best).add(0, 0.5, 0));
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(best).add(0, 0.5, 0), Direction.UP, best, false));
                Bot.swing(p);
                crystalCd = 1;
                return true;
            }
            // 3) no spot: put obsidian right next to them
            if (!has(mc, "obsidian")) return false;
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                BlockPos spot = t.relative(dir);
                if (!mc.level.getBlockState(spot).isAir() || !mc.level.getBlockState(spot.above()).isAir()) continue;
                if (!Bot.solid(mc, spot.below())) continue;
                if (spot.equals(p.blockPosition()) || spot.equals(p.blockPosition().above())) continue;
                if (eye.distanceTo(Vec3.atCenterOf(spot)) > 4.5) continue;
                if (!mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(spot)).isEmpty()) continue;
                if (!Bot.holdBest(mc, s -> id(s).equals("obsidian") ? 1 : 0)) return false;
                if (!holding(p, "obsidian")) return true;
                BlockPos floor = spot.below();
                Bot.look(p, Vec3.atCenterOf(floor).add(0, 0.5, 0));
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
                Bot.swing(p);
                crystalCd = 1;
                return true;
            }
            return false;
        }
    }
}
