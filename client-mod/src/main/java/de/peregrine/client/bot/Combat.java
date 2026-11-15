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

    /**
     * Explosion damage of an end crystal (power 6) like vanilla: (impact² + impact) / 2 · 7 · 12 + 1 with
     * impact = 1 − distance / 12, distance to the feet (vanilla) or the body centre, whichever is closer, so it never
     * guesses low. Full exposure (no blocks in between), no armor, no resistance – and for players the difficulty
     * scaling (Hard ×1.5). For our own safety that is the worst case on purpose.
     */
    static double crystalDamage(LivingEntity e, Vec3 crystal) {
        double d = Math.min(e.position().distanceTo(crystal), e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(crystal));
        if (d >= 12) return 0;
        double impact = 1 - d / 12;
        double dmg = (impact * impact + impact) / 2 * 7 * 12 + 1;
        if (e instanceof Player) {
            switch (e.level().getDifficulty()) {
                case PEACEFUL -> dmg = 0;
                case EASY -> dmg = Math.min(dmg / 2 + 1, dmg);
                case HARD -> dmg *= 1.5;
                default -> {
                }
            }
        }
        return dmg;
    }

    public static final class Duel implements Bot.Task {
        @Override
        public boolean idle() { // circling and waiting for a charged hit is not being stuck
            return target != null;
        }

        private final String wanted; // player name, entity type or null = nearest player
        private LivingEntity target;
        private Navigator nav;
        private String mode = "looking", targetName = "";
        private int ticks, lost, wtap, strafeT, crystalCd, navAge, maceStage;
        private int restoreSlot = -1, catchIn = -1, lungeCd, pearlCd;
        private float catchYaw;
        private int flyStage, flyTicks, rocketCd, flyCd;
        private boolean strafeLeft, hadTarget;
        private int lastHurt = -1000, obiPlaced, obiWindow, backOff, axeCd;
        private final Random rnd = new Random();

        public LivingEntity target() {
            return target;
        }

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
            sinceSwordHit++;
            if (target == null || !target.isAlive() || target.isRemoved()) {
                if (hadTarget && target != null && !target.isAlive()) {
                    Bot.say("Won – " + targetName + " is down.");
                    return true;
                }
                target = find(mc, p);
                if (target == null) {
                    if (++lost > 20 * 15) {
                        Bot.fail("I can't find " + (wanted == null ? "anyone to fight" : "\"" + wanted + "\"") + " nearby.");
                    }
                    Bot.release(mc);
                    return false;
                }
                lost = 0;
                hadTarget = true;
                targetName = target.getName().getString();
                CombatLog.start("target " + targetName + ", mace " + has(mc, "mace") + ", wind charges " + Bot.count(mc, "wind_charge")
                        + ", crystals " + Bot.count(mc, "end_crystal") + ", obsidian " + Bot.count(mc, "obsidian") + ", totems " + Bot.count(mc, "totem_of_undying")
                        + ", golden apples " + Bot.count(mc, "golden_apple", "enchanted_golden_apple") + ", elytra " + has(mc, "elytra") + ", rockets " + Bot.count(mc, "firework_rocket"));
                Bot.say("Target: " + targetName + "." + (Bot.count(mc, "totem_of_undying") > 0 || Guard.hasTotem(p) ? " Totem in the off hand." : " No totem – careful.")
                        + (Bot.count(mc, "end_crystal") > 0 ? " Crystals ready." : "") + (Bot.count(mc, "mace") > 0 ? " Mace ready." : ""));
            }
            double d = p.distanceTo(target);
            if (restoreSlot >= 0) { // back to the weapon after a lunge
                p.getInventory().setSelectedSlot(restoreSlot);
                restoreSlot = -1;
            }

            // ---- defence first
            Guard.gear(mc); // totem back in the off hand immediately after a pop, best armor on
            float hp = p.getHealth() + p.getAbsorptionAmount();
            // healing: only a golden apple heals in time. First get room (eating next to them = dying), then eat;
            // cornered, it keeps fighting. Normal food only when hungry and nobody is close.
            // Being hit = keep fighting (no snack under fire). With a totem left it stays in the fight and only eats in
            // a quiet moment; with no totem left and low health it gets away first, then heals.
                        boolean totems = Guard.hasTotem(p) || Bot.count(mc, "totem_of_undying") > 0;
            boolean needHeal = hp < 9 || (hp < 13 && d > 6);
            if (healTrip(mc, p, hp, totems, d)) return false;
            if (!totems && hp < 6 && d < 6 && backOff < 40) { // no totem left and nearly dead: a short dash away (eats on the run)
                backOff++;
                mode = "no totem – getting away";
                Guard.backOff(mc, p, target.position());
                if (Guard.canGapple(mc, p)) Guard.eatGapple(mc);
                return false;
            }
            if (needHeal && !Guard.canGapple(mc, p) && Guard.healPotion(mc)) { // no golden apple: healing / regeneration potion
                mode = "potion";
                return false;
            }
            if (ticks < 200 && d > 5 && Guard.strengthPotion(mc)) { // strength before it starts
                mode = "strength potion";
                return false;
            }
            if (needHeal && Guard.canGapple(mc, p) && Guard.eatGapple(mc)) { // eats right in the fight, hit or not – no pause
                mode = "golden apple";
                return false;
            }
            if (hp >= 9 || d >= 8) backOff = 0;
            if (p.getFoodData().getFoodLevel() <= 6 && d > 8 && Guard.eatNow(mc, false)) {
                mode = "eating";
                return false;
            }

            if (p.hurtTime > 0) lastHurt = ticks;

            // ---- they walled themselves in: break the block between us
            if (d < 7 && breakIn(mc, p)) return false;

            // ---- they come down on us with a mace: shield up
            if (shieldUp(mc, p)) return false;

            // ---- they raise a shield: axe in the same tick, shield gone (no need to wait for a full charge)
            if (axeCd > 0) axeCd--;
            if (target.isBlocking() && axeCd == 0 && p.getEyePosition().distanceTo(target.getBoundingBox().getCenter()) <= 3.6) {
                int axe = hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0);
                if (axe >= 0) {
                    var inv = p.getInventory();
                    int hand = inv.getSelectedSlot();
                    Bot.look(p, target.getEyePosition().subtract(0, 0.3, 0));
                    inv.setSelectedSlot(axe);
                    mc.gameMode.attack(p, target);
                    Bot.swing(p);
                    inv.setSelectedSlot(hand);
                    axeCd = 5;
                    mode = "axe · shield down";
                    CombatLog.log("axe vs raised shield");
                    return false;
                }
            }

            // ---- special moves (pearls, wind charges, lunge)
            if (techniques(mc, p, d)) return false;

            // ---- from a distance: elytra mace (flies there itself)
            if (elytraMace(mc, p, d)) return false;

            // ---- they are up high (on a pillar, a ledge): build up under ourselves
            if (pillar(mc, p)) return false;

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

            // ---- pick what hurts most right now: crystals, a mace jump, or the sword
            double cp = has(mc, "end_crystal") && d < 7.5 ? crystalPotential(mc, p) : 0;
            double mp = maceStage == 0 && has(mc, "mace") && has(mc, "wind_charge") ? 14 : 0; // a smash from a wind-charge jump, rough
            if (maceStage == 0 && cp >= 6 && cp >= mp * 0.8) { // crystal fight: sword hits them up, crystals follow
                if (crystal(mc, p)) {
                    if (!mode.startsWith("crystal")) mode = "crystal";
                    return false;
                }
                melee(mc, p, d);
                mode = "crystal · " + mode;
                return false;
            }
            if (has(mc, "mace") && mace(mc, p, d)) {
                mode = "mace";
                return false;
            }
            if (cp > 0 && crystal(mc, p)) {
                if (!mode.startsWith("crystal")) mode = "crystal";
                return false;
            }
            melee(mc, p, d);
            return false;
        }

        /** A calm moment: not hit in the last 1.5 s and enough health – only then risky moves (pearls, jumps, flying). */
        private boolean calm(LocalPlayer p, float minHealth) {
            return ticks - lastHurt > 30 && p.getHealth() + p.getAbsorptionAmount() >= minHealth;
        }

        /**
         * Target out of sight behind blocks (built in, in a hole, behind a wall): mine the first block on the line
         * between our eyes and theirs. Walks closer first when that block is out of reach.
         */
        private boolean breakIn(Minecraft mc, LocalPlayer p) {
            Vec3 eye = p.getEyePosition();
            Vec3 to = target.getEyePosition();
            if (p.hasLineOfSight(target)) return false;
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                to = target.position().add(0, 0.4, 0); // try their feet
                hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
            }
            BlockPos b = hit.getBlockPos();
            if (Bot.unsafeToMine(mc, b) || Navigator.AVOID.contains(b)) return false;
            mode = "breaking in";
            if (eye.distanceTo(Vec3.atCenterOf(b)) > 4.3) {
                if (nav == null || ++navAge > 40) {
                    nav = new Navigator(b, 3.5, null);
                    navAge = 0;
                }
                if (nav.tick(mc)) nav = null;
                return true;
            }
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            Bot.mine(mc, b);
            return true;
        }

        private int shieldTicks, shieldSlot = -1;
        private boolean boosted;

        /**
         * A player in the air above us with a mace in hand is about to smash: raise a shield (off hand, or swap one in
         * from the hotbar) and hold it until they land, then switch back. A shield needs a moment to come up, so it
         * starts as soon as they are airborne and above us, not only when they are already falling.
         */
        private boolean shieldUp(Minecraft mc, LocalPlayer p) {
            boolean threat = false;
            if (target instanceof Player && id(target.getMainHandItem()).equals("mace") && !target.onGround()) {
                double hd = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
                threat = target.getY() - p.getY() > 1.5 && hd < 5;
            }
            boolean inOff = id(p.getOffhandItem()).equals("shield");
            int slot = inOff ? -1 : hotbar(mc, s -> id(s).equals("shield") ? 1 : 0);
            if (threat && !inOff && slot < 0 && has(mc, "wind_charge") && pearlCd == 0) {
                // no shield: blow them off course with a wind charge into their path
                Vec3 at = target.position().add(target.getDeltaMovement().scale(3));
                Vec3 eye = p.getEyePosition();
                float pitch = (float) -Math.toDegrees(Math.atan2(at.y - eye.y, Math.hypot(at.x - eye.x, at.z - eye.z)));
                if (quickUse(mc, p, "wind_charge", yawTo(eye, at), pitch)) {
                    pearlCd = 10; // short pause before the next throw
                    mode = "wind charge vs. their dive";
                    return true;
                }
            }
            if (threat && (inOff || slot >= 0)) shieldTicks = 8;
            var inv = p.getInventory();
            if (shieldTicks > 0) {
                shieldTicks--;
                if (!inOff) {
                    if (shieldSlot < 0) shieldSlot = inv.getSelectedSlot();
                    inv.setSelectedSlot(slot);
                }
                Bot.look(p, target.getEyePosition());
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                if (!p.isUsingItem()) mc.gameMode.useItem(p, inOff ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                mc.options.keyUse.setDown(true);
                mode = "shield up";
                return true;
            }
            if (shieldSlot >= 0 || (inOff && mode.equals("shield up"))) { // they landed: shield down, weapon back
                mc.options.keyUse.setDown(false);
                if (p.isUsingItem()) mc.gameMode.releaseUsingItem(p);
                if (shieldSlot >= 0) inv.setSelectedSlot(shieldSlot);
                shieldSlot = -1;
                mode = "fighting";
            }
            return false;
        }

        // ------------------------------------------------------------------ fly away, heal, come back
        private int tripStage, tripTicks;
        private Vec3 tripFrom;

        /** Distance to the nearest other player (Double.MAX_VALUE when nobody is around). */
        private static double nearestPlayer(Minecraft mc, LocalPlayer p) {
            double best = Double.MAX_VALUE;
            for (Player o : mc.level.players()) if (o != p && !o.isSpectator()) best = Math.min(best, o.distanceTo(p));
            return best;
        }

        /**
         * Only when it really has to: 2 hearts or less and no totem left, and only with elytra and rockets. It flies
         * away from everyone until no player is within 100 blocks, lands, and only there heals (golden apple, food,
         * regeneration) up to 8 hearts. If a player comes within 100 blocks while it heals, it takes off again. Then the
         * duel goes on – it walks or flies back on its own. Without elytra there is no heal stop: it keeps fighting.
         */
        private boolean healTrip(Minecraft mc, LocalPlayer p, float hp, boolean totems, double d) {
            boolean wings = has(mc, "firework_rocket") && (wearingElytra(p) || has(mc, "elytra"));
            if (tripStage == 0) {
                if (hp > 4 || totems || !wings) return false;
                tripStage = 1;
                tripTicks = 0;
                tripFrom = target.position();
                Bot.say("Two hearts and no totem – flying off to heal, back in a moment.");
            }
            tripTicks++;
            double far = nearestPlayer(mc, p);
            Vec3 away = p.position().subtract(tripFrom).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-3) away = new Vec3(1, 0, 0);
            float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
            switch (tripStage) {
                case 1 -> { // get away
                    mode = "flying off to heal";
                    if (wings) {
                        Guard.keepElytra = true;
                        if (!wearingElytra(p)) {
                            var inv = p.getInventory();
                            for (int i = 0; i < 36; i++)
                                if (id(inv.getItem(i)).equals("elytra")) {
                                    Inv.equip(mc, i, net.minecraft.world.entity.EquipmentSlot.CHEST);
                                    break;
                                }
                            return true;
                        }
                        if (p.onGround()) mc.options.keyJump.setDown(tripTicks % 4 < 2);
                        else if (!p.isFallFlying()) mc.options.keyJump.setDown(tripTicks % 2 == 0);
                        else {
                            mc.options.keyJump.setDown(false);
                            float[] aim = steerClear(mc, p, yaw, -25f);
                            p.setYRot(aim[0]);
                            p.setXRot(aim[1]);
                            if (--rocketCd <= 0 && quickUse(mc, p, "firework_rocket", aim[0], aim[1])) rocketCd = 25;
                        }
                        if (far >= 110 || tripTicks > 20 * 30) tripStage = 2; // a bit over 100, so landing still leaves 100
                    } else {
                        tripStage = 0; // out of rockets: no heal stop, back to fighting
                        Guard.keepElytra = false;
                        return false;
                    }
                }
                case 2 -> { // land (glide down, look ahead, nothing in the way)
                    mode = "landing to heal";
                    float[] aim = steerClear(mc, p, yaw, 20f);
                    p.setYRot(aim[0]);
                    p.setXRot(aim[1]);
                    if (p.onGround() || p.isInWater()) {
                        Guard.keepElytra = false;
                        Guard.gear(mc);
                        Bot.release(mc);
                        tripStage = 3;
                        tripTicks = 0;
                    }
                }
                case 3 -> { // heal – only with nobody within 100 blocks
                    if (far < 100) { // someone came after us: off again
                        tripStage = 1;
                        tripTicks = 0;
                        tripFrom = p.position().subtract(1, 0, 0);
                        for (Player o : mc.level.players()) if (o != p && o.distanceTo(p) < 100) tripFrom = o.position();
                        return true;
                    }
                    mode = "eating far away";
                    // no resting: eat what there is, then straight back into the fight
                    if (Guard.canGapple(mc, p) && Guard.eatGapple(mc)) return true;
                    if (p.getHealth() < 16 && p.getFoodData().getFoodLevel() < 20 && Guard.eatNow(mc, false)) return true;
                    if (p.isUsingItem()) return true;
                    tripStage = 0;
                    Bot.say("Back into the fight.");
                    return false;
                }
                default -> tripStage = 0;
            }
            return true;
        }

        private int pillarY = Integer.MIN_VALUE;

        /** Pillars straight up (jump, block under the feet) while the target is more than 3 blocks above and close by. */
        private boolean pillar(Minecraft mc, LocalPlayer p) {
            double dy = target.getY() - p.getY(), hd = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
            if (dy < 3 || hd > 4 || wearingElytra(p) && p.isFallFlying()) {
                pillarY = Integer.MIN_VALUE;
                return false;
            }
            java.util.function.ToDoubleFunction<ItemStack> block = s -> switch (id(s)) {
                case "cobblestone", "cobbled_deepslate", "dirt", "netherrack", "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "blackstone" -> 2 + s.getCount() * 0.01;
                default -> id(s).endsWith("_planks") ? 1 : 0;
            };
            if (!Bot.holdBest(mc, block)) return false;
            if (block.applyAsDouble(p.getMainHandItem()) <= 0) return true; // still swapping
            mode = "building up to them";
            BlockPos feet = p.blockPosition();
            if (!Bot.passable(mc, feet.above(2))) { // ceiling: dig it first
                Bot.mine(mc, feet.above(2));
                return true;
            }
            mc.options.keyUp.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            p.setXRot(90f);
            if (p.onGround()) {
                pillarY = feet.getY();
                mc.options.keyJump.setDown(true);
                return true;
            }
            mc.options.keyJump.setDown(false);
            if (pillarY != Integer.MIN_VALUE && p.getY() > pillarY + 1.05) { // high enough: block into the space we left
                BlockPos floor = new BlockPos(feet.getX(), pillarY - 1, feet.getZ());
                Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floor, false));
                Bot.swing(p);
                pillarY = Integer.MIN_VALUE;
            }
            return true;
        }

        // ------------------------------------------------------------------ hotbar swaps
        /** Hotbar slot of the best item matching the score (> 0), or -1. Items still in the main inventory are pulled in. */
        private int hotbar(Minecraft mc, java.util.function.ToDoubleFunction<ItemStack> score) {
            var inv = mc.player.getInventory();
            int best = -1, bestMain = -1;
            double bs = 0, bm = 0;
            for (int i = 0; i < 36; i++) {
                double v = score.applyAsDouble(inv.getItem(i));
                if (v <= 0) continue;
                if (i < 9 && v > bs) {
                    bs = v;
                    best = i;
                } else if (i >= 9 && v > bm) {
                    bm = v;
                    bestMain = i;
                }
            }
            if (best < 0 && bestMain >= 0) {
                // free a hotbar slot that is not the weapon in hand: the last one
                int spare = inv.getSelectedSlot() == 8 ? 7 : 8;
                Inv.toHotbar(mc, bestMain, spare);
            }
            return best;
        }

        private static boolean enchanted(ItemStack s, String ench) {
            try {
                return s.getEnchantments().toString().contains("minecraft:" + ench);
            } catch (Throwable e) {
                return false;
            }
        }

        /** Swap to a hotbar slot, use the item with the given look, swap straight back (one tick, like a player's hotkey). */
        private boolean quickUse(Minecraft mc, LocalPlayer p, String item, float yaw, float pitch) {
            int slot = hotbar(mc, s -> id(s).equals(item) ? 1 : 0);
            if (slot < 0) return false;
            var inv = p.getInventory();
            if (p.getCooldowns().isOnCooldown(inv.getItem(slot))) return false;
            int prev = inv.getSelectedSlot();
            float oy = p.getYRot(), op = p.getXRot();
            p.setYRot(yaw);
            p.setXRot(pitch);
            inv.setSelectedSlot(slot);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            Bot.swing(p);
            inv.setSelectedSlot(prev);
            p.setYRot(oy);
            p.setXRot(op);
            return true;
        }

        private static float yawTo(Vec3 from, Vec3 to) {
            return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
        }

        /** Pitch for an ender pearl to land at horizontal distance d (and height difference dy): ballistic arc. */
        private static float pearlPitch(double d, double dy) {
            double v = 1.5, g = 0.03;
            double x = Math.max(1, d);
            double disc = v * v * v * v - g * (g * x * x + 2 * dy * v * v);
            if (disc < 0) return -45f;
            double theta = Math.atan((v * v - Math.sqrt(disc)) / (g * x)); // the flat (fast) arc
            return (float) -Math.toDegrees(theta);
        }

        /** Advanced moves that come before the main technique. Returns true when one was used this tick. */
        private boolean techniques(Minecraft mc, LocalPlayer p, double d) {
            Vec3 eye = p.getEyePosition();
            if (lungeCd > 0) lungeCd--;
            if (pearlCd > 0) pearlCd--;
            // pearl catch: the wind charge right after the engage pearl – fly after it with momentum
            if (catchIn >= 0 && catchIn-- == 0) {
                // the charge hits the ground just behind us: the blast throws us forward and up, after the pearl
                if (quickUse(mc, p, "wind_charge", catchYaw + 180f, 68f)) {
                    mode = "pearl catch";
                    return true;
                }
            }
            float hp = p.getHealth() + p.getAbsorptionAmount();
            // escape pearl: low, no totem, enemy close – away and up, then heal
            if (hp < 7 && !Guard.hasTotem(p) && d < 6 && pearlCd == 0 && has(mc, "ender_pearl")) {
                float away = yawTo(target.position(), p.position());
                if (quickUse(mc, p, "ender_pearl", away, -28f)) {
                    pearlCd = 40;
                    mode = "escape pearl";
                    if (has(mc, "wind_charge") && p.onGround()) { // catch it: away even faster
                        catchIn = 1;
                        catchYaw = away;
                    }
                    Bot.note("Pearling out.");
                    return true;
                }
            }
            // wind charge into their face when it gets tight (knocks them away)
            if (hp < 9 && d < 3.2 && has(mc, "wind_charge") && target.onGround()) {
                Vec3 feet = target.position();
                float pitch = (float) -Math.toDegrees(Math.atan2(feet.y - eye.y, Math.hypot(feet.x - eye.x, feet.z - eye.z)));
                if (quickUse(mc, p, "wind_charge", yawTo(eye, feet), pitch)) {
                    mode = "wind push";
                    return true;
                }
            }
            // no fall damage: wind charge at the ground just before landing from high up
            if (maceStage == 0 && flyStage == 0 && p.fallDistance > 6 && p.getDeltaMovement().y < -0.6 && has(mc, "wind_charge")) {
                BlockPos below = p.blockPosition();
                for (int i = 1; i <= 3; i++) {
                    if (Bot.solid(mc, below.below(i))) {
                        if (quickUse(mc, p, "wind_charge", p.getYRot(), 90f)) {
                            mode = "fall cancel";
                            return true;
                        }
                        break;
                    }
                }
            }
            // engage pearl (+ pearl catch) when they are far away and visible
            boolean catchKit = has(mc, "ender_pearl") && has(mc, "wind_charge");
            if ((d > 14 || (catchKit && d > 9)) && d < 60 && pearlCd == 0 && has(mc, "ender_pearl") && p.hasLineOfSight(target)
                    && calm(p, 10) && p.onGround()) {
                Vec3 t = target.position();
                double hd = Math.hypot(t.x - eye.x, t.z - eye.z) - 2.0;
                float pitch = pearlPitch(hd, t.y - eye.y);
                if (quickUse(mc, p, "ender_pearl", yawTo(eye, t), pitch)) {
                    pearlCd = 30;
                    mode = "engage pearl";
                    if (has(mc, "wind_charge")) {
                        catchIn = 1;
                        catchYaw = yawTo(eye, t);
                        mode = "pearl catch";
                    }
                    return true;
                }
            }
            // lunge swap: spear with Lunge, jab to dash in, back to the weapon
            if (lungeCd == 0 && d > 3.6 && d < 8.5 && p.onGround() && catchIn < 0 && ticks - lastHurt > 10) {
                int spear = hotbar(mc, s -> id(s).endsWith("_spear") && enchanted(s, "lunge") ? 1 : 0);
                if (spear >= 0) {
                    var inv = p.getInventory();
                    restoreSlot = inv.getSelectedSlot();
                    inv.setSelectedSlot(spear);
                    Bot.look(p, target.getEyePosition());
                    try {
                        var m = Minecraft.class.getDeclaredMethod("startAttack");
                        m.setAccessible(true);
                        m.invoke(mc);
                    } catch (ReflectiveOperationException e) {
                        mc.gameMode.attack(p, target);
                    }
                    Bot.swing(p);
                    lungeCd = 25;
                    mode = "lunge";
                    return true;
                }
            }
            return false;
        }

        /**
         * Attribute swap: the swing is charged with the weapon in hand, and in the same tick the hotbar switches to
         * the item whose attributes should hit (axe against a shield, mace while falling or Breach against armor),
         * attacks, and switches straight back – the server uses the charge of the first and the stats of the second.
         */
        /** Rough hit damage of an item right now (base + sharpness, mace fall bonus, density, breach vs. armor). */
        private double hitDamage(ItemStack s, LocalPlayer p, LivingEntity t, double dist) {
            String id = id(s);
            double dmg;
            if (id.endsWith("_sword")) dmg = switch (id) {
                case "netherite_sword" -> 8; case "diamond_sword" -> 7; case "iron_sword" -> 6; case "stone_sword" -> 5; default -> 4;
            };
            else if (id.endsWith("_axe")) dmg = switch (id) {
                case "netherite_axe" -> 10; case "diamond_axe", "iron_axe", "stone_axe" -> 9; default -> 7;
            };
            else if (id.equals("mace")) {
                double f = p.fallDistance;
                dmg = 6 + (f <= 1.5 ? 0 : f <= 3 ? 4 * f : f <= 8 ? 12 + 2 * (f - 3) : 22 + (f - 8));
                if (enchanted(s, "density") && f > 1.5) dmg += 1.5 * f;
                if (enchanted(s, "breach")) dmg *= 1 + Math.min(0.4, t.getArmorValue() / 50.0);
            } else if (id.endsWith("_spear")) dmg = switch (id) {
                case "netherite_spear" -> 7; case "diamond_spear" -> 6; case "iron_spear" -> 5; default -> 4;
            };
            else return 0;
            if (dist > 3.0 && !id.endsWith("_spear")) return 0; // only the spear reaches that far
            if (enchanted(s, "sharpness")) dmg += 2.5;
            return dmg;
        }

        private void attackWithSwap(Minecraft mc, LocalPlayer p, LivingEntity t) {
            var inv = p.getInventory();
            int hand = inv.getSelectedSlot();
            double dist = p.getEyePosition().distanceTo(t.getBoundingBox().getCenter()) - t.getBbWidth() / 2;
            if (t == target && t.isBlocking() && p.fallDistance > 1.5 && hotbar(mc, s -> id(s).equals("mace") ? 1 : 0) >= 0
                    && hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0) >= 0) {
                smash(mc, p); // falling onto a raised shield: axe takes the shield down, the mace lands in the same tick
                return;
            }
            int swap = -1;
            if (t.isBlocking()) swap = hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0); // shield: axe first
            if (swap < 0) { // otherwise whatever hits hardest now – the charge comes from the fast weapon in hand
                double handDmg = hitDamage(inv.getItem(hand), p, t, dist);
                int best = hotbar(mc, s -> hitDamage(s, p, t, dist));
                if (best >= 0 && hitDamage(inv.getItem(best), p, t, dist) > handDmg + 0.5) swap = best;
            }
            if (t == target) sinceSwordHit = 0; // a hit knocks them up – the crystal may follow
            if (swap >= 0 && swap != hand) {
                inv.setSelectedSlot(swap);
                mc.gameMode.attack(p, t);
                Bot.swing(p);
                inv.setSelectedSlot(hand);
                mode = mode + " · swap";
            } else {
                mc.gameMode.attack(p, t);
                Bot.swing(p);
            }
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
            mc.options.keyLeft.setDown(strafeLeft); // with forward held below this is a diagonal run (W+A / W+D)
            mc.options.keyRight.setDown(!strafeLeft);
            if (wtap > 0) { // sprint reset: let go of forward for a moment after a hit
                wtap--;
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(wtap > 1);
                return;
            }
            mc.options.keyDown.setDown(false);
            if (d > 3.1) { // chase – diagonally, so they can't line up a clean hit
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
                mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
                return;
            }
            // in range: stay at the edge of reach (diagonal steps in), hit when the swing is fully charged
            mc.options.keyUp.setDown(d > 2.4);
            mc.options.keySprint.setDown(true);
            boolean targetAirborne = !target.onGround();
            boolean wantCrit = targetAirborne || target.getHealth() < 6; // crits finish, sprint hits push away
            if (ready && wantCrit && p.onGround() && !p.isInWater()) {
                mc.options.keyJump.setDown(true);
                return;
            }
            mc.options.keyJump.setDown(false);
            boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.08;
            boolean spear = hotbar(mc, s -> id(s).endsWith("_spear") ? 1 : 0) >= 0;
            if (ready && (d <= 3.0 || (spear && d <= 4.3)) && (!wantCrit || falling || p.isInWater())) {
                attackWithSwap(mc, p, target);
                if (!wantCrit) wtap = 3;
            }
        }

        // ------------------------------------------------------------------ mace
        /** Eye to the nearest point of their hitbox – what the game checks for reach. */
        private static double reachTo(LocalPlayer p, LivingEntity t) {
            Vec3 e = p.getEyePosition();
            AABB b = t.getBoundingBox();
            Vec3 c = new Vec3(Math.max(b.minX, Math.min(b.maxX, e.x)), Math.max(b.minY, Math.min(b.maxY, e.y)), Math.max(b.minZ, Math.min(b.maxZ, e.z)));
            return e.distanceTo(c);
        }

        /** Ticks until our feet are down at their head height (vanilla gravity 0.08 and drag 0.98), 0 when already there. */
        private int ticksToFall(LocalPlayer p) {
            double y = p.getY(), vy = p.getDeltaMovement().y, goal = target.getBoundingBox().maxY;
            for (int t = 0; t < 100; t++) {
                if (y <= goal && vy < 0) return t;
                vy = (vy - 0.08) * 0.98;
                y += vy;
            }
            return 100;
        }

        /**
         * Air control on the way down: where will they be when we come down (their speed × fall time), where does our
         * own momentum carry us (air drag 0.91 per tick), and the rest is made up with forward/back and strafe keys –
         * so it lands on them instead of next to them. Looks at the predicted spot the whole time.
         */
        private void airSteer(Minecraft mc, LocalPlayer p) {
            int t = Math.max(1, Math.min(40, ticksToFall(p)));
            Vec3 tv = target.getDeltaMovement();
            Vec3 pred = target.position().add(tv.x * Math.min(t, 20), 0, tv.z * Math.min(t, 20));
            Vec3 v = p.getDeltaMovement();
            double carry = (1 - Math.pow(0.91, t)) / 0.09; // how far the current speed still carries
            double ex = pred.x - (p.getX() + v.x * carry), ez = pred.z - (p.getZ() + v.z * carry);
            float yaw = yawTo(p.position(), pred);
            p.setYRot(yaw);
            Vec3 aimAt = new Vec3(pred.x, target.getEyeY() - 0.4, pred.z);
            Vec3 eye = p.getEyePosition();
            p.setXRot((float) -Math.toDegrees(Math.atan2(aimAt.y - eye.y, Math.max(0.05, Math.hypot(aimAt.x - eye.x, aimAt.z - eye.z)))));
            double ry = Math.toRadians(yaw);
            double fwd = -Math.sin(ry) * ex + Math.cos(ry) * ez, left = Math.cos(ry) * ex + Math.sin(ry) * ez;
            mc.options.keyUp.setDown(fwd > 0.25);
            mc.options.keyDown.setDown(fwd < -0.25);
            mc.options.keyLeft.setDown(left > 0.25);
            mc.options.keyRight.setDown(left < -0.25);
            mc.options.keySprint.setDown(fwd > 0.25);
            mc.options.keyJump.setDown(false);
        }

        private int launchTick;
        private double launchFrom;

        private boolean mace(Minecraft mc, LocalPlayer p, double d) {
            boolean charges = has(mc, "wind_charge");
            double hd = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
            // stage 1: on the ground – wind charge so the blast throws us up AND towards them
            boolean shieldWall = target.isBlocking() && hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0) < 0; // blocked without an axe
            if (maceStage == 0 && charges && p.onGround() && d < 7 && d > 2.0 && ticks - lastHurt > 15 && p.getHealth() + p.getAbsorptionAmount() >= 6 && !shieldWall) {
                float toThem = yawTo(p.position(), target.position());
                // close: straight down. Further: the charge hits the ground behind us, the blast pushes us towards them
                float yaw = hd <= 2.5 ? p.getYRot() : toThem + 180f;
                float pitch = hd <= 2.5 ? 90f : (float) Math.max(55, Math.min(85, 90 - hd * 6));
                if (quickUse(mc, p, "wind_charge", yaw, pitch)) {
                    maceStage = 1;
                    boosted = false;
                    launchTick = ticks;
                    launchFrom = hd;
                    CombatLog.log("launch  dist " + CombatLog.f(d) + "  horizontal " + CombatLog.f(hd) + "  pitch " + CombatLog.f(pitch));
                    return true;
                }
                return false;
            }
            // stage 1b: at the top of the jump a second wind charge under the feet – higher means a much harder smash
            if (maceStage == 1 && !boosted && charges && !p.onGround() && Math.abs(p.getDeltaMovement().y) < 0.12 && p.getY() - target.getY() < 14 && hd < 4) {
                if (quickUse(mc, p, "wind_charge", p.getYRot(), 90f)) {
                    boosted = true;
                    mode = "mace · second launch";
                    CombatLog.log("second launch  height " + CombatLog.f(p.getY() - target.getY()));
                    return true;
                }
            }
            // stage 2: in the air – steer onto them, mace out, smash the moment they are in reach on the way down
            if (maceStage == 1 || p.fallDistance > 1.5) {
                if (p.onGround() && maceStage == 1 && ticks - launchTick > 6) {
                    maceStage = 0; // landed without a hit
                    CombatLog.log("MISS  landed " + CombatLog.f(hd) + " blocks from them (launched at " + CombatLog.f(launchFrom) + ")");
                    return false;
                }
                hotbar(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) : 0);
                Bot.holdBest(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) : 0);
                airSteer(mc, p);
                boolean falling = p.getDeltaMovement().y < -0.08;
                double reach = reachTo(p, target);
                if (falling && reach <= p.entityInteractionRange() - 0.05 && holding(p, "mace")) {
                    boolean burst = enchanted(p.getMainHandItem(), "wind_burst");
                    float before = target.getHealth();
                    CombatLog.log("SMASH  reach " + CombatLog.f(reach) + "  fall " + CombatLog.f(p.fallDistance) + "  their hp " + CombatLog.f(before)
                            + (target.isBlocking() ? "  (shield up)" : ""));
                    smash(mc, p);
                    maceStage = burst ? 1 : 0; // wind burst throws us up again: stay in the air and smash again
                    if (burst) {
                        mode = "mace · wind burst chain";
                        launchTick = ticks;
                    }
                }
                return true;
            }
            return false;
        }

        /**
         * The mace hit. Against a raised shield it is a stun slam: in the same tick an axe hit disables the shield
         * and the mace hit follows straight after, with the full fall damage.
         */
        private void smash(Minecraft mc, LocalPlayer p) {
            var inv = p.getInventory();
            int hand = inv.getSelectedSlot();
            int mace = hotbar(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) + (enchanted(s, "breach") ? 0.5 : 0) : 0);
            int axe = target.isBlocking() ? hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0) : -1;
            if (axe >= 0 && mace >= 0) {
                inv.setSelectedSlot(axe);
                mc.gameMode.attack(p, target); // shield down …
                Bot.swing(p);
                inv.setSelectedSlot(mace);
                mc.gameMode.attack(p, target); // … and the slam lands
                Bot.swing(p);
                inv.setSelectedSlot(hand);
                mode = "stun slam";
                Bot.note("Stun slam!");
                return;
            }
            if (mace >= 0) inv.setSelectedSlot(mace);
            mc.gameMode.attack(p, target);
            Bot.swing(p);
            if (mace >= 0) inv.setSelectedSlot(hand);
        }

        private boolean wearingElytra(LocalPlayer p) {
            return id(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)).equals("elytra");
        }

        /**
         * Elytra mace, done the way it works: elytra on, take off, rockets up, then fly level until it is right above
         * the target – there the chestplate goes back on (the elytra comes off), so it free-falls and the fall distance
         * builds up – and the mace smash lands on the way down (stun slam against a shield). Missed and close to the
         * ground: a wind charge under the feet takes the fall. Never flies into blocks (see {@link #steerClear}).
         */
        private boolean elytraMace(Minecraft mc, LocalPlayer p, double d) {
            if (flyCd > 0) flyCd--;
            boolean can = has(mc, "mace") && has(mc, "firework_rocket") && (wearingElytra(p) || has(mc, "elytra"));
            if (flyStage == 0) {
                if (!can || flyCd > 0 || d < 9 || d > 64 || !calm(p, 9) || !mc.level.canSeeSky(p.blockPosition().above(2)) || !openAbove(mc, target)) return false;
                flyStage = 1;
                flyTicks = 0;
            }
            flyTicks++;
            mode = "elytra mace";
            if (flyTicks > 20 * 20 || !target.isAlive()) return endFlight(mc);
            if (flyStage >= 3 && flyStage <= 4 && !openAbove(mc, target)) { // they went under a roof: a dive would only hit blocks
                flyStage = 6;
                mode = "elytra mace · they're under cover";
            }
            Vec3 t = target.position();
            double hd = Math.hypot(t.x - p.getX(), t.z - p.getZ());
            float yawT = (float) Math.toDegrees(Math.atan2(-(t.x - p.getX()), t.z - p.getZ()));
            switch (flyStage) {
                case 1 -> { // elytra on, jump
                    Guard.keepElytra = true;
                    if (!wearingElytra(p)) {
                        equip(mc, p, "elytra");
                        return true;
                    }
                    if (p.onGround()) {
                        mc.options.keyJump.setDown(true);
                        return true;
                    }
                    mc.options.keyJump.setDown(false); // let go, so the next press opens the wings
                    flyStage = 2;
                }
                case 2 -> { // open the wings in the air
                    if (p.isFallFlying()) {
                        flyStage = 3;
                        rocketCd = 0;
                    } else {
                        mc.options.keyJump.setDown(flyTicks % 2 == 0);
                        if (p.onGround() && flyTicks > 20) return endFlight(mc);
                    }
                }
                case 3 -> { // climb: rockets, up to ~25 above them
                    mc.options.keyJump.setDown(false);
                    // close to them: nearly straight up on a fixed heading (aiming at them would loop over and back)
                    if (hd < 15 && climbYaw == null) climbYaw = p.getYRot();
                    float pitch = hd < 15 ? -80f : -50f;
                    float[] aim = steerClear(mc, p, hd < 15 && climbYaw != null ? climbYaw : yawT, pitch);
                    p.setYRot(aim[0]);
                    p.setXRot(aim[1]);
                    if (--rocketCd <= 0 && quickUse(mc, p, "firework_rocket", aim[0], aim[1])) rocketCd = 20;
                    if (p.getY() > t.y + 25) flyStage = 4;
                    if (!p.isFallFlying() && p.onGround()) return endFlight(mc);
                }
                case 4 -> { // level flight until right above them
                    float pitch = p.getY() < t.y + 20 ? -15f : 5f;
                    float[] aim = steerClear(mc, p, yawT, pitch);
                    p.setYRot(aim[0]);
                    p.setXRot(aim[1]);
                    double speed = p.getDeltaMovement().horizontalDistance();
                    if (hd > 12 && speed < 0.9 && --rocketCd <= 0 && quickUse(mc, p, "firework_rocket", aim[0], aim[1])) rocketCd = 30;
                    // drop where the momentum carries us onto them: free fall time from here, air drag 0.91 per tick
                    int fall = Math.max(1, ticksToFall(p));
                    double carry = speed * (1 - Math.pow(0.91, fall)) / 0.09;
                    Vec3 hv = p.getDeltaMovement().multiply(1, 0, 1);
                    boolean passed = hd < 12 && hv.lengthSqr() > 0.01 && hv.dot(new Vec3(t.x - p.getX(), 0, t.z - p.getZ())) < 0;
                    if (hd <= carry + 1.5 || passed) {
                        Guard.keepElytra = false;
                        equip(mc, p, "chestplate"); // elytra off → free fall
                        hotbar(mc, s -> id(s).equals("mace") ? 1 : 0);
                        flyStage = 5;
                        mode = "elytra mace · chestplate on, falling";
                        CombatLog.log("elytra off over them  horizontal " + CombatLog.f(hd) + "  height " + CombatLog.f(p.getY() - t.y));
                    }
                    if (!p.isFallFlying() && p.onGround()) return endFlight(mc);
                }
                case 5 -> { // free fall onto them, steered, smash the moment they are in reach
                    airSteer(mc, p);
                    Bot.holdBest(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) : 0);
                    if (reachTo(p, target) <= p.entityInteractionRange() - 0.05 && p.getDeltaMovement().y < -0.2) {
                        CombatLog.log("ELYTRA SMASH  reach " + CombatLog.f(reachTo(p, target)) + "  fall " + CombatLog.f(p.fallDistance));
                        smash(mc, p);
                        flyStage = 6;
                        return true;
                    }
                    // missed and the ground is close: a wind charge under the feet takes the fall
                    if (p.fallDistance > 6 && has(mc, "wind_charge")) {
                        for (int i = 1; i <= 3; i++)
                            if (Bot.solid(mc, p.blockPosition().below(i))) {
                                if (quickUse(mc, p, "wind_charge", p.getYRot(), 90f)) mode = "elytra mace · missed, fall cancel";
                                break;
                            }
                    }
                    if (p.onGround()) {
                        CombatLog.log("ELYTRA MISS  landed " + CombatLog.f(hd) + " blocks from them");
                        return endFlight(mc);
                    }
                }
                default -> { // done or aborted: land
                    if (wearingElytra(p) && p.isFallFlying()) {
                        float[] aim = steerClear(mc, p, p.getYRot(), 20f);
                        p.setYRot(aim[0]);
                        p.setXRot(aim[1]);
                    }
                    if (p.onGround() || !p.isFallFlying()) return endFlight(mc);
                }
            }
            return true;
        }

        /** Best chestplate (netherite first) or the elytra into the chest slot. */
        private void equip(Minecraft mc, LocalPlayer p, String what) {
            var inv = p.getInventory();
            int best = -1, bestTier = -1;
            for (int i = 0; i < 36; i++) {
                String id = id(inv.getItem(i));
                int tier = what.equals("elytra") ? (id.equals("elytra") ? 1 : -1)
                        : !id.endsWith("_chestplate") ? -1 : id.startsWith("netherite") ? 6 : id.startsWith("diamond") ? 5 : id.startsWith("iron") ? 4 : id.startsWith("chainmail") ? 3 : id.startsWith("golden") ? 2 : 1;
                if (tier > bestTier) {
                    bestTier = tier;
                    best = i;
                }
            }
            if (best >= 0) Inv.equip(mc, best, net.minecraft.world.entity.EquipmentSlot.CHEST);
        }

        private int avoidTicks;
        private float avoidYaw, avoidPitch;

        /**
         * Keeps the elytra flight out of blocks: looks 8 blocks ahead in the wanted direction and 10 along the actual
         * (mostly horizontal) motion, at body height – a gliding player lies flat, so nothing is checked below the body.
         * Something in the way: climb and turn to the free side, and keep that course for half a second instead of
         * re-deciding every tick (that made it fly circles). Ground coming up: only pull up. Returns {yaw, pitch}.
         */
        private float[] steerClear(Minecraft mc, LocalPlayer p, float yaw, float pitch) {
            if (avoidTicks > 0) {
                avoidTicks--;
                return new float[]{avoidYaw, avoidPitch};
            }
            Vec3 eye = p.getEyePosition(), v = p.getDeltaMovement();
            Vec3 hv = new Vec3(v.x, Math.max(-0.15, Math.min(0.3, v.y)), v.z);
            boolean ahead = blocked(mc, p, eye, dir(yaw, Math.max(-60, Math.min(30, pitch))), 8)
                    || (v.horizontalDistance() > 0.4 && blocked(mc, p, eye, hv, 10));
            boolean groundSoon = v.y < -0.3 && blocked(mc, p, eye, new Vec3(v.x, v.y * 2, v.z), 5);
            if (!ahead) return new float[]{yaw, groundSoon ? Math.min(pitch, -35f) : pitch};
            for (float turn : new float[]{35, -35, 70, -70, 110, -110})
                for (float up : new float[]{-40, -65})
                    if (!blocked(mc, p, eye, dir(yaw + turn, up), 8)) {
                        if (rocketCd > 5 && has(mc, "firework_rocket") && quickUse(mc, p, "firework_rocket", yaw + turn, up)) rocketCd = 20;
                        mode = "elytra · avoiding blocks";
                        avoidYaw = yaw + turn;
                        avoidPitch = up;
                        avoidTicks = 10;
                        return new float[]{avoidYaw, avoidPitch};
                    }
            avoidYaw = yaw + 180;
            avoidPitch = -60;
            avoidTicks = 10;
            return new float[]{avoidYaw, avoidPitch}; // boxed in: back the way it came, upwards
        }

        private static Vec3 dir(float yaw, float pitch) {
            double y = Math.toRadians(yaw), x = Math.toRadians(pitch);
            return new Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
        }

        private static boolean blocked(Minecraft mc, LocalPlayer p, Vec3 from, Vec3 dir, double len) {
            if (dir.lengthSqr() < 1e-4) return false;
            Vec3 d = dir.normalize().scale(len);
            for (Vec3 off : new Vec3[]{Vec3.ZERO, new Vec3(0, -0.35, 0), new Vec3(0, 0.35, 0)}) { // the flat gliding body
                var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from.add(off), from.add(off).add(d),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, p));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return true;
            }
            return false;
        }

        /** Nothing solid in the 24 blocks above them – otherwise a dive from above only hits the roof. */
        private static boolean openAbove(Minecraft mc, LivingEntity t) {
            BlockPos head = t.blockPosition().above(2);
            for (int i = 0; i < 24; i++) if (!Bot.passable(mc, head.above(i))) return false;
            return true;
        }

        private Float climbYaw;

        private boolean endFlight(Minecraft mc) {
            flyStage = 0;
            flyCd = 60;
            climbYaw = null;
            avoidTicks = 0;
            Guard.keepElytra = false;
            Guard.gear(mc); // chestplate back on right away, so it doesn't keep gliding
            mc.options.keyJump.setDown(false);
            return false;
        }

        // ------------------------------------------------------------------ crystal
        /** Crystal damage to us (before armor, so on the safe side) must leave at least 4 hearts – and never over 6 hearts. */
        private static boolean safeForUs(double us, float hp) {
            return us < hp - 8 && us <= 12; // worst case still leaves 4 hearts, and never more than 6 hearts at once
        }

        /** A crystal in reach of us that would go off when they hit it (or another blows up) must be safe too. */
        private boolean crystalsAroundSafe(Minecraft mc, LocalPlayer p, float hp, Vec3 extra) {
            double total = extra == null ? 0 : crystalDamage(p, extra);
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(12), EndCrystal::isAlive))
                total = Math.max(total, crystalDamage(p, c.position())); // they chain-explode one at a time, the worst one counts
            return safeForUs(total, hp);
        }

        /** The most a crystal could do to them right now (safe spots only): existing crystals, bases, or a base we could set. */
        private double crystalPotential(Minecraft mc, LocalPlayer p) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            double best = 0;
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(6), EndCrystal::isAlive))
                if (eye.distanceTo(c.position()) <= 4.6 && safeForUs(crystalDamage(p, c.position()), hp)) best = Math.max(best, crystalDamage(target, c.position()));
            BlockPos t = target.blockPosition();
            boolean obi = has(mc, "obsidian");
            for (BlockPos b : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
                String bid = Bot.id(mc.level.getBlockState(b));
                boolean base = bid.equals("obsidian") || bid.equals("bedrock");
                boolean canSet = obi && !base && Math.max(Math.abs(b.getX() - t.getX()), Math.abs(b.getZ() - t.getZ())) == 2 && b.getY() == t.getY()
                        && mc.level.getBlockState(b).isAir() && Bot.solid(mc, b.below());
                if (!base && !canSet) continue;
                BlockPos a1 = canSet ? b.above() : b.above();
                if (!mc.level.getBlockState(a1).isAir() || eye.distanceTo(Vec3.atCenterOf(b)) > 4.5) continue;
                Vec3 c = Vec3.atBottomCenterOf(a1);
                if (!safeForUs(crystalDamage(p, c), hp)) continue;
                best = Math.max(best, crystalDamage(target, c));
            }
            return best;
        }

        private int sinceSwordHit = 100;
        private final java.util.Map<Integer, Integer> recentlyHit = new java.util.HashMap<>();

        private boolean crystal(Minecraft mc, LocalPlayer p) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            // 0) hit them up first: a sprint hit with the sword knocks them into the air, then the crystal goes off
            //    under them while they can't move away
            double dd = p.distanceTo(target);
            boolean knockedUp = !target.onGround() || sinceSwordHit < 15; // in the air, or we just hit them
            if (!knockedUp && (dd > 3.0 || p.getAttackStrengthScale(0f) < 0.9f || !p.hasLineOfSight(target))) {
                mode = "crystal · waiting to hit them up";
                return false; // first get close and hit them up (the sword fight moves in and hits)
            }
            if (!knockedUp) {
                Bot.holdBest(mc, Guard::weaponScore);
                Bot.look(p, target.getEyePosition().subtract(0, target.getBbHeight() * 0.3, 0));
                mc.options.keySprint.setDown(true);
                mc.options.keyUp.setDown(true);
                attackWithSwap(mc, p, target);
                sinceSwordHit = 0;
                mode = "crystal · hit up";
                return true;
            }
            // minimum damage for a crystal to be worth it: 3 hearts – down to 0.75 hearts when they are nearly dead
            double minDmg = target.getHealth() + target.getAbsorptionAmount() <= 8 ? 1.5 : 6;
            recentlyHit.entrySet().removeIf(e -> ticks - e.getValue() > 4);
            // 1) break any crystal that hurts them more than us (breaking comes before placing)
            EndCrystal hit = null;
            double hitScore = 0;
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(6), c -> c.isAlive() && !recentlyHit.containsKey(c.getId()))) {
                if (eye.distanceTo(c.position().add(0, 1, 0)) > 4.6) continue;
                double them = crystalDamage(target, c.position()), us = crystalDamage(p, c.position());
                if (!safeForUs(us, hp) || !crystalsAroundSafe(mc, p, hp, null)) continue; // never risk blowing ourselves up – totem or not; neighbours go off too
                double score = them - us * 0.6;
                if (them >= minDmg && score > hitScore) {
                    hitScore = score;
                    hit = c;
                }
            }
            if (hit != null) {
                if (p.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS)) Bot.holdBest(mc, Guard::weaponScore); // a fist can't break it with weakness
                Bot.look(p, hit.position().add(0, 0.8, 0));
                recentlyHit.put(hit.getId(), ticks);
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
                if (target.getBoundingBox().intersects(new AABB(a1).expandTowards(0, 1, 0))) continue; // would sit inside them
                var seen = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(b).add(0, 0.5, 0),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                if (seen.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !seen.getBlockPos().equals(b)
                        && eye.distanceTo(Vec3.atCenterOf(b)) > 3) continue; // behind a wall and not close: skip
                double them = crystalDamage(target, c), us = crystalDamage(p, c);
                if (!safeForUs(us, hp) || !crystalsAroundSafe(mc, p, hp, c)) continue;
                double score = them - us * 0.6;
                if (them >= minDmg && score > bestScore) {
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
            // 3) no spot: one obsidian base two blocks from them on our side – never right next to them (that would
            //    only wall them in), and at most two bases every 10 s
            if (!has(mc, "obsidian")) return false;
            if (++obiWindow > 200) {
                obiWindow = 0;
                obiPlaced = 0;
            }
            if (obiPlaced >= 2) return false;
            Vec3 toUs = p.position().subtract(target.position()).multiply(1, 0, 1);
            java.util.List<BlockPos> spots = new java.util.ArrayList<>();
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) spots.add(t.offset(dx, 0, dz));
            spots.sort(java.util.Comparator.comparingDouble(b -> -(toUs.x * (b.getX() - t.getX()) + toUs.z * (b.getZ() - t.getZ()))));
            for (BlockPos spot : spots) {
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
                obiPlaced++;
                mode = "crystal · obsidian base";
                return true;
            }
            return false;
        }
    }
}
