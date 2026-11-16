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

    /** Damage an end crystal at {@code crystal} does to {@code e}: exposure, difficulty, armor and enchantments (see {@link Blast}). */
    static double crystalDamage(LivingEntity e, Vec3 crystal) {
        return Blast.damage(e, crystal, Blast.CRYSTAL);
    }

    /** Upper bound without the ray checks – cheap, to skip spots that can't be worth it before doing the real sum. */
    static double crystalQuick(LivingEntity e, Vec3 crystal) {
        return Blast.damage(e, e.position(), crystal, Blast.CRYSTAL, false);
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
        private int restoreSlot = -1, catchIn = -1, lungeCd, pearlCd, windPushCd;
        private float catchYaw;
        private int flyStage, flyTicks, rocketCd, flyCd;
        private boolean strafeLeft, hadTarget;
        private int lastHurt = -1000, obiPlaced, obiWindow, backOff, axeCd;
        private final Random rnd = new Random();

        public LivingEntity target() {
            return target;
        }

        private float lastTh = -1, lastOh = -1;
        private double dealt, taken;
        private int startTick = -1;
        private boolean finished;

        /** Writes the fight summary once (won, or lost when we died). */
        void finish(String result) {
            if (finished || startTick < 0) return;
            finished = true;
            CombatLog.summary(result + " vs " + targetName, (ticks - startTick) / 20.0, dealt, taken);
        }

        /**
         * Keeps the hotbar stocked during the fight (real inventory clicks, so it works on servers): when the hotbar
         * has run out of something the fight lives on – totems, crystals, obsidian, carts, rails, anchors, glowstone,
         * wind charges, rockets, golden apples, pearls – one stack comes up from the main inventory into an empty
         * hotbar slot (or a slot holding something less useful). One move every half second, never in a busy moment.
         */
        private void refill(Minecraft mc, LocalPlayer p) {
            if (p.isUsingItem() || maceStage != 0 || flyStage != 0 || railPos != null && cartStage >= 10) return;
            var inv = p.getInventory();
            String[] need = {"end_crystal", "obsidian", "tnt_minecart", "rail", "respawn_anchor", "glowstone", "wind_charge", "firework_rocket",
                    "golden_apple", "ender_pearl", "totem_of_undying"};
            for (String item : need) {
                boolean inBar = false;
                for (int i = 0; i < 9; i++) if (id(inv.getItem(i)).equals(item)) inBar = true;
                if (inBar) continue;
                int from = -1;
                for (int i = 9; i < 36; i++) if (id(inv.getItem(i)).equals(item)) from = i;
                if (from < 0) continue;
                int to = -1;
                for (int i = 8; i >= 0; i--) if (inv.getItem(i).isEmpty() && i != inv.getSelectedSlot()) to = i;
                if (to < 0) for (int i = 8; i >= 0; i--) { // something we never fight with
                    String h = id(inv.getItem(i));
                    if (i != inv.getSelectedSlot() && !(h.endsWith("_sword") || h.endsWith("_axe") || h.equals("mace") || h.equals("bow") || h.endsWith("_spear")
                            || java.util.Arrays.asList(need).contains(h))) {
                        to = i;
                        break;
                    }
                }
                if (to < 0) return;
                Inv.toHotbar(mc, from, to);
                CombatLog.log("refill " + item + " -> hotbar " + (to + 1));
                return;
            }
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
                    ok = wanted != null && (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' ').startsWith(wanted.replaceAll("s$", ""))
                            || e.hasCustomName() && e.getName().getString().toLowerCase(Locale.ROOT).startsWith(wanted)); // a named mob, e.g. the sparring partner
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
            if (cartCd > 0) cartCd--; // counted here: cartKit() needs it at 0 before cart() is ever called again
            if (!mode.equals(loggedMode) && ticks - loggedAt >= 4) { // what it is doing, for tuning
                CombatLog.log("mode " + mode + (target != null ? "  dist " + CombatLog.f(p.distanceTo(target)) : ""));
                loggedMode = mode;
                loggedAt = ticks;
            }
            if (target == null || !target.isAlive() || target.isRemoved()) {
                if (hadTarget && target != null && !target.isAlive()) {
                    finish("WON");
                    Bot.say("Won – " + targetName + " is down.");
                    return true;
                }
                target = find(mc, p);
                if (target == null) {
                    if (++lost > 20 * 15) {
                        Bot.fail("I can't find " + (wanted == null ? "anyone to fight" : "\"" + wanted + "\"") + " nearby.");
                    }
                    if (!Guard.idleEat(mc)) Bot.release(mc); // nothing to hit: use the moment to eat
                    mode = "looking for them" + (p.isUsingItem() ? " · eating" : "");
                    return false;
                }
                lost = 0;
                hadTarget = true;
                targetName = target.getName().getString();
                CombatLog.start("target " + targetName + ", mace " + has(mc, "mace") + ", wind charges " + Bot.count(mc, "wind_charge")
                        + ", crystals " + Bot.count(mc, "end_crystal") + ", obsidian " + Bot.count(mc, "obsidian") + ", totems " + Bot.count(mc, "totem_of_undying")
                        + ", golden apples " + Bot.count(mc, "golden_apple", "enchanted_golden_apple") + ", elytra " + has(mc, "elytra") + ", rockets " + Bot.count(mc, "firework_rocket")
                        + ", tnt carts " + Bot.count(mc, "tnt_minecart") + ", rails " + Bot.count(mc, RAILS) + ", flame bow " + (flameBow(mc) >= 0) + ", arrows " + Bot.count(mc, "arrow", "spectral_arrow", "tipped_arrow"));
                Bot.say("Target: " + targetName + "." + (Bot.count(mc, "totem_of_undying") > 0 || Guard.hasTotem(p) ? " Totem in the off hand." : " No totem – careful.")
                        + (Bot.count(mc, "end_crystal") > 0 ? " Crystals ready." : "") + (Bot.count(mc, "mace") > 0 ? " Mace ready." : "")
                        + (cartKit(mc) && flameBow(mc) >= 0 ? " Carts ready." : Bot.count(mc, "tnt_minecart") > 0 ? " Carts need rails, a Flame bow and arrows." : ""));
            }
            double d = p.distanceTo(target);
            // damage bookkeeping for the fight summary
            float th = target.getHealth() + target.getAbsorptionAmount(), oh = p.getHealth() + p.getAbsorptionAmount();
            if (lastTh >= 0 && th < lastTh) dealt += lastTh - th;
            if (lastOh >= 0 && oh < lastOh) taken += lastOh - oh;
            lastTh = th;
            lastOh = oh;
            if (startTick < 0) startTick = ticks;
            if (ticks % 10 == 0) refill(mc, p);
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

            // now and then a bite in a quiet moment (not hit for 1.5 s, room around us): food stays above 14 so sprint,
            // lunge and regeneration keep working, and a golden apple below 7.5 hearts. Eating happens on the move.
            boolean quiet = ticks - lastHitOnUs > 30 && d > 4.5 && maceStage == 0 && flyStage == 0 && railPos == null && anchorPos == null;
            if (quiet && p.getFoodData().getFoodLevel() <= 14 && Guard.eatNow(mc, false)) {
                mode = "eating in a quiet moment";
                CombatLog.log("eat  food " + p.getFoodData().getFoodLevel() + "  hp " + CombatLog.f(hp));
                return false;
            }
            if (quiet && hp < 15 && Guard.canGapple(mc, p) && Guard.eatGapple(mc)) {
                mode = "golden apple in a quiet moment";
                CombatLog.log("gapple  hp " + CombatLog.f(hp));
                return false;
            }
            if (p.hurtTime > 0) lastHurt = ticks;
            if (p.hurtTime >= 9) lastHitOnUs = ticks; // the moment a hit lands, not the whole animation

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

            // ---- a cart attack under way (rail, carts, backing off, the shot) is finished first
            if (railPos != null && cart(mc, p)) return false;

            // ---- special moves (pearls, wind charges, lunge)
            if (techniques(mc, p, d)) return false;

            // ---- from a distance: elytra mace (flies there itself)
            if (elytraMace(mc, p, d)) return false;

            // ---- they are up high (on a pillar, a ledge): build up under ourselves
            if (pillar(mc, p)) return false;

            // ---- getting there
            if (d > 10) {
                mode = "closing in";
                // intercept: run to where they will be when we get there (sprint ≈ 0.28 blocks per tick), and re-plan
                // as soon as they change course by more than 3 blocks – not on a fixed timer
                Vec3 tv = target.getDeltaMovement();
                double lead = Math.min(40, d / 0.28) * 0.6;
                BlockPos goal = BlockPos.containing(target.getX() + tv.x * lead, target.getY(), target.getZ() + tv.z * lead);
                if (!Bot.solid(mc, goal.below())) goal = target.blockPosition(); // predicted spot in the air or over a gap
                ++navAge;
                if (nav == null || navAge > 60 || nav.goal().distSqr(goal) > 9) {
                    nav = new Navigator(goal, 3.0, null);
                    navAge = 0;
                }
                if (nav.tick(mc)) nav = null;
                return false;
            }
            nav = null;

            // ---- pick what hurts most right now: crystals, a mace jump, or the sword
            double cp = has(mc, "end_crystal") && d < 7.5 ? crystalPotential(mc, p) : 0;
            if (maceStage == 0 && cp >= minDamage()) { // crystals in the bag and a spot that hurts: crystal fight (sword hits them up, crystals follow)
                if (crystal(mc, p)) {
                    if (!mode.startsWith("crystal")) mode = "crystal";
                    return false;
                }
                melee(mc, p, d);
                mode = "crystal · " + mode;
                return false;
            }
            if (maceStage == 0 && d < 6.5 && (anchorPos != null || has(mc, "respawn_anchor")) && anchor(mc, p)) return false;
            if (maceStage == 0 && d < 7 && railPos == null && cartKit(mc) && cart(mc, p)) return false;
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
            if (!enclosed(mc)) return false; // only behind a tree or a corner: walk around (see chaseAround), don't dig
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

        /** Walled in on all four sides at body height (a box, a hole): the only way to them is through a block. */
        private boolean enclosed(Minecraft mc) {
            BlockPos f = target.blockPosition();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = f.relative(dir);
                if (Bot.passable(mc, n) && Bot.passable(mc, n.above())) return false;
            }
            return true;
        }

        /**
         * Called by {@link Guard} while an apple, food or a potion is being used (that takes 1.6 s and blocks hits):
         * instead of standing still next to them it backs off facing them and strafes, so they can't land free hits.
         */
        void whileBusy(Minecraft mc) {
            LocalPlayer p = mc.player;
            if (target == null || !target.isAlive()) return;
            double d = p.distanceTo(target);
            Bot.look(p, target.getEyePosition());
            if (--strafeT <= 0) {
                strafeLeft = !strafeLeft;
                strafeT = 6 + rnd.nextInt(8);
            }
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyDown.setDown(d < 6);
            mc.options.keyLeft.setDown(strafeLeft);
            mc.options.keyRight.setDown(!strafeLeft);
            mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
            mode = "eating on the move";
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
            // trained: a wind charge threw them up and it started building – only for someone standing up there
            if (dy < 3 || hd > 4 || !target.onGround() || wearingElytra(p) && p.isFallFlying()) {
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
                    CombatLog.log("pearl catch  wind charge after the pearl");
                    return true;
                }
            }
            float hp = p.getHealth() + p.getAbsorptionAmount();
            // escape pearl: low, no totem, enemy close – away and up, then heal
            // trained: the pearl itself costs 5 HP on landing – at 2 hearts it killed us. Only with HP to spare.
            if (hp < 11 && hp >= 7.5 && !Guard.hasTotem(p) && d < 6 && pearlCd == 0 && has(mc, "ender_pearl")) {
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
            if (windPushCd > 0) windPushCd--;
            if (hp < 9 && d < 3.2 && has(mc, "wind_charge") && target.onGround() && windPushCd == 0) {
                Vec3 feet = target.position();
                float pitch = (float) -Math.toDegrees(Math.atan2(feet.y - eye.y, Math.hypot(feet.x - eye.x, feet.z - eye.z)));
                if (quickUse(mc, p, "wind_charge", yawTo(eye, feet), pitch)) {
                    windPushCd = 60;
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
            if ((d > 14 || (catchKit && d > 7.5)) && d < 60 && pearlCd == 0 && has(mc, "ender_pearl") && p.hasLineOfSight(target) && hp >= 9
                    && (calm(p, 10) || catchKit && ticks - lastHitOnUs > 15) && p.onGround()) {
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

        /** Attack damage attribute of an item while it is held (what the server counts as the base of a hit). */
        private static double baseDamage(ItemStack s) {
            String id = id(s);
            if (id.endsWith("_sword")) return switch (id) {
                case "netherite_sword" -> 8; case "diamond_sword" -> 7; case "iron_sword" -> 6; case "stone_sword", "copper_sword" -> 5; default -> 4;
            };
            if (id.endsWith("_axe")) return switch (id) {
                case "netherite_axe" -> 10; case "diamond_axe", "iron_axe", "stone_axe", "copper_axe" -> 9; default -> 7;
            };
            if (id.equals("mace")) return 6;
            if (id.equals("trident")) return 9;
            if (id.endsWith("_spear")) return switch (id) {
                case "netherite_spear" -> 7; case "diamond_spear" -> 6; case "iron_spear" -> 5; default -> 4;
            };
            return 1;
        }

        /** Mace smash bonus for the current fall (vanilla steps: 4/block to 3, 2/block to 8, then 1/block), plus Density. */
        private static double maceBonus(ItemStack s, LocalPlayer p) {
            if (!id(s).equals("mace") || p.fallDistance <= 1.5 || p.isFallFlying()) return 0;
            double f = p.fallDistance;
            double b = f <= 3 ? 4 * f : f <= 8 ? 12 + 2 * (f - 3) : 22 + (f - 8);
            return b + 0.5 * Blast.level(s, "density") * f;
        }

        /**
         * What an item adds when it is swapped in for the hit only. The server works out the base damage and the
         * charge from the item held during the last tick (attributes update in the player tick), but the enchantments,
         * the mace smash bonus and shield breaking from the item in hand at the moment of the hit – so the charge and
         * base come from the fast sword, everything else from the swapped item.
         */
        private double swapBonus(ItemStack s, LocalPlayer p, LivingEntity t, double base, float charge) {
            if (s.isEmpty()) return 0;
            double b = 0;
            int sharp = Blast.level(s, "sharpness");
            if (sharp > 0) b += (0.5 * sharp + 0.5) * charge;
            int fire = Blast.level(s, "fire_aspect");
            if (fire > 0 && !t.isOnFire()) b += 2.5 * fire; // burning afterwards
            double mace = maceBonus(s, p);
            b += mace; // not scaled by the charge
            int breach = Blast.level(s, "breach");
            if (breach > 0 && id(s).equals("mace")) { // Breach: their armor counts 15 % less per level
                double armor = Blast.armor(t)[0];
                b += (base + mace) * Math.min(0.8, armor / 25) * 0.15 * breach;
            }
            return b;
        }

        /**
         * Attribute swap: charge with the weapon in hand, and in the same tick switch the hotbar to the item whose
         * extras should hit (axe against a raised shield, mace while falling, Sharpness / Fire Aspect / Breach), attack,
         * and switch straight back. Picks the item that adds the most right now; against a shield the axe always wins.
         * Past sword reach only a spear can hit.
         */
        private void attackWithSwap(Minecraft mc, LocalPlayer p, LivingEntity t) {
            var inv = p.getInventory();
            int hand = inv.getSelectedSlot();
            double dist = reachTo(p, t);
            if (t == target && t.isBlocking() && p.fallDistance > 1.5 && hotbar(mc, s -> id(s).equals("mace") ? 1 : 0) >= 0
                    && hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0) >= 0) {
                smash(mc, p); // falling onto a raised shield: axe takes the shield down, the mace lands in the same tick
                return;
            }
            ItemStack held = inv.getItem(hand);
            float charge = p.getAttackStrengthScale(0.5f);
            double base = baseDamage(held) * (0.2 + charge * charge * 0.8);
            int swap = -1;
            if (t.isBlocking()) swap = hotbar(mc, s -> id(s).endsWith("_axe") ? 1 + Blast.level(s, "sharpness") * 0.1 : 0); // shield: axe first
            if (swap < 0 && dist > p.entityInteractionRange() && !id(held).endsWith("_spear"))
                swap = hotbar(mc, s -> id(s).endsWith("_spear") ? baseDamage(s) : 0); // out of sword reach: only a spear gets there
            if (swap < 0) {
                double handExtra = swapBonus(held, p, t, base, charge);
                int best = hotbar(mc, s -> swapBonus(s, p, t, base, charge));
                if (best >= 0 && best != hand && swapBonus(inv.getItem(best), p, t, base, charge) > handExtra + 0.5) swap = best;
            }
            if (t == target) sinceSwordHit = 0; // a hit knocks them up – the crystal may follow
            if (swap >= 0 && swap != hand) {
                String what = id(inv.getItem(swap));
                inv.setSelectedSlot(swap);
                mc.gameMode.attack(p, t);
                Bot.swing(p);
                inv.setSelectedSlot(hand);
                mode = mode + " · swap " + what.replace("netherite_", "").replace("diamond_", "");
                CombatLog.log("attribute swap  " + id(held) + " -> " + what + "  charge " + CombatLog.f(charge) + "  fall " + CombatLog.f(p.fallDistance));
            } else {
                mc.gameMode.attack(p, t);
                Bot.swing(p);
            }
        }

        // ------------------------------------------------------------------ sword / axe
        /** Where to look: chest height, a tick and a half ahead of where they are going (strafing targets). */
        private Vec3 aimPoint() {
            Vec3 v = target.getDeltaMovement();
            return target.position().add(v.x * 1.5, target.getBbHeight() * 0.7, v.z * 1.5);
        }

        /** Ticks until the swing is charged enough for a full hit (0 = now). */
        private static int ticksToCharge(LocalPlayer p) {
            float s = p.getAttackStrengthScale(0f);
            if (s >= 0.93f) return 0;
            return (int) Math.ceil((0.93f - s) * p.getCurrentItemAttackStrengthDelay());
        }

        private Navigator chase;
        private int chaseTicks, chaseStall;
        private double chaseBest = Double.MAX_VALUE;

        /**
         * Close by but can't walk straight there – no line of sight, they stand on a ledge, or running at them gets no
         * closer for a second (a hole, a fence, a tree in between): walk the A* route instead of pressing forward into a
         * wall. Back to the normal fight as soon as they are in reach.
         */
        private boolean chaseAround(Minecraft mc, LocalPlayer p, double reach, double range) {
            if (reach <= range + 0.4) {
                chase = null;
                chaseStall = 0;
                chaseBest = reach;
                return false;
            }
            if (reach < chaseBest - 0.25) {
                chaseBest = reach;
                chaseStall = 0;
            } else chaseStall++;
            boolean ledge = target.getY() - p.getY() > 1.2 && target.onGround() && p.onGround();
            boolean blocked = !p.hasLineOfSight(target) || ledge || chaseStall > 20;
            if (chase == null && !blocked) return false;
            if (chase == null || ++chaseTicks > 40 || chase.goal().distSqr(target.blockPosition()) > 4) {
                chase = new Navigator(target.blockPosition(), 2.0, null);
                chaseTicks = 0;
                chaseStall = 0;
                chaseBest = reach;
            }
            mode = "finding a way to them";
            if (chase.tick(mc)) chase = null;
            return true;
        }

        // combo: hits in a row on them without a hit back
        private String loggedMode = "";
        private int loggedAt;
        private int lastHitOnUs = -1000, comboHits, lastSwingTick = -1000, lastLandedTick = -1000, jumpReset, targetHurtSeen;

        /** We are on a run: our last hit landed within 1.5 s and they have not hit us since. */
        private boolean inCombo() {
            return comboHits > 0 && ticks - lastLandedTick < 30 && lastHitOnUs < lastLandedTick;
        }

        /** Counts a hit as landed when their hurt animation starts right after our swing. */
        private void trackCombo() {
            if (target.hurtTime > targetHurtSeen && target.hurtTime >= 9 && ticks - lastSwingTick <= 4) {
                comboHits = inCombo() ? comboHits + 1 : 1;
                lastLandedTick = ticks;
                if (comboHits >= 2) CombatLog.log("combo x" + comboHits);
            }
            targetHurtSeen = target.hurtTime;
            if (lastHitOnUs > lastLandedTick && comboHits > 0) { // they hit back: the run is over
                if (comboHits >= 3) CombatLog.log("combo ended at " + comboHits);
                comboHits = 0;
            }
        }

        /**
         * Jump reset: the moment their hit lands (our hurt animation starts) jump while running at them. The jump
         * cancels most of the backwards push, so we stay in reach and can hit straight back instead of being comboed.
         */
        private boolean jumpReset(Minecraft mc, LocalPlayer p, double reach) {
            if (p.hurtTime >= 9 && p.onGround() && !p.isInWater() && reach < 4.5 && jumpReset == 0) {
                jumpReset = 3;
                CombatLog.log("jump reset");
            }
            if (jumpReset <= 0) return false;
            jumpReset--;
            mc.options.keyUp.setDown(true);
            mc.options.keyDown.setDown(false);
            mc.options.keySprint.setDown(true);
            mc.options.keyJump.setDown(jumpReset >= 1);
            if (jumpReset == 0) mc.options.keyJump.setDown(false);
            mode = "jump reset";
            return true;
        }

        /**
         * Sword fight: hits only at real reach (eye to their hitbox, like the game checks) and only when the swing is
         * charged and their hurt-invulnerability is over, so no swing is wasted. Between hits it keeps at the edge of
         * their reach and steps in so that it arrives exactly when the next hit is ready – they walk into the hit
         * instead of trading. Jump reset when they hit us. Combos: once a hit lands, it stays on them – short w-tap,
         * full sprint after them, the next sprint hit the moment the swing is charged and they are back in reach, no
         * crit jumps (a crit takes out the sprint knockback that keeps them helpless).
         */
        private void melee(Minecraft mc, LocalPlayer p, double d) {
            boolean shield = target.isBlocking() && Bot.count(mc, "wooden_axe", "stone_axe", "iron_axe", "golden_axe", "diamond_axe", "netherite_axe") > 0;
            if (shield) Bot.holdBest(mc, s -> id(s).endsWith("_axe") ? 10 : 0); // an axe hit disables the shield
            else Bot.holdBest(mc, Guard::weaponScore);
            mode = shield ? "axe vs shield" : "sword";
            double reach = reachTo(p, target), range = p.entityInteractionRange() - 0.08;
            boolean spear = hotbar(mc, s -> id(s).endsWith("_spear") ? 1 : 0) >= 0; // swapped in for hits past sword reach
            trackCombo();
            boolean combo = inCombo();
            if (combo) mode = "combo x" + comboHits;
            if (!combo && chaseAround(mc, p, reach, spear ? range + 1.2 : range)) return;
            Bot.look(p, aimPoint());
            int charge = ticksToCharge(p);
            boolean ready = charge == 0;
            double hitRange = spear ? range + 1.2 : range;
            boolean iframes = target.hurtTime > 1; // a hit now would mostly be swallowed by their invulnerability
            // strafe in an uneven rhythm so the opponent can't time it; in a combo straight at them (no lost speed)
            if (--strafeT <= 0) {
                strafeLeft = !strafeLeft;
                strafeT = 7 + rnd.nextInt(10);
            }
            mc.options.keyLeft.setDown(!combo && strafeLeft);
            mc.options.keyRight.setDown(!combo && !strafeLeft);
            if (jumpReset(mc, p, reach)) {
                // still hit back in the air if the swing is ready – that is the point of the reset
                if (ready && reach <= hitRange && !iframes) swingAt(mc, p, reach, false);
                return;
            }
            if (wtap > 0) { // sprint reset: let go of forward for a moment after a hit
                wtap--;
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(!combo && wtap > 1); // in a combo only a tap, no step back
                mc.options.keySprint.setDown(false);
                return;
            }
            if (reach > range + 1.0) { // chase – diagonally, so they can't line up a clean hit
                mc.options.keyDown.setDown(false);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
                mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
                return;
            }
            // spacing: with the swing still charging, hold just outside their reach; step in when the hit will be ready
            // on arrival (sprinting covers ~0.28 blocks a tick). In a combo: always after them, they are flying away
            boolean stepIn = combo || reach - 0.28 * charge > range - 0.5;
            boolean tooClose = !combo && charge > 3 && reach < 2.0;
            mc.options.keyUp.setDown(stepIn && !tooClose);
            mc.options.keyDown.setDown(tooClose);
            mc.options.keySprint.setDown(!tooClose);
            boolean finishing = target.getHealth() + target.getAbsorptionAmount() < 6;
            boolean wantCrit = finishing || !combo && !target.onGround(); // crits finish, sprint hits keep the combo going
            if (ready && wantCrit && p.onGround() && !p.isInWater() && reach <= range + 0.4) {
                mc.options.keyJump.setDown(true);
                return;
            }
            mc.options.keyJump.setDown(p.horizontalCollision && p.onGround() && stepIn);
            boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.08;
            if (ready && reach <= hitRange && !iframes && (!wantCrit || falling || p.isInWater() || !p.onGround() && reach > range - 0.3))
                swingAt(mc, p, reach, falling);
            if (lastSwingTick == ticks && !wantCrit) wtap = combo ? 1 : 3;
        }

        private void swingAt(Minecraft mc, LocalPlayer p, double reach, boolean crit) {
            attackWithSwap(mc, p, target);
            lastSwingTick = ticks;
            CombatLog.log("hit  reach " + CombatLog.f(reach) + (crit ? "  crit" : "") + (inCombo() ? "  combo " + comboHits : "") + "  their hp " + CombatLog.f(target.getHealth()));
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
        private double[] airSteer(Minecraft mc, LocalPlayer p) {
            int t = Math.max(1, Math.min(40, ticksToFall(p)));
            Vec3 tv = target.getDeltaMovement();
            Vec3 pred = target.position().add(tv.x * Math.min(t, 20), 0, tv.z * Math.min(t, 20));
            Vec3 v = p.getDeltaMovement();
            double carry = (1 - Math.pow(0.91, t)) / 0.09; // how far the current speed still carries
            // come down just beside them, not on top: straight above, a fast fall skips the short moment where they
            // are in reach (and landing on their head ends the fall); 1.1 blocks to the side keeps them in reach for
            // several ticks of the fall
            Vec3 side = p.position().subtract(pred).multiply(1, 0, 1);
            if (side.lengthSqr() < 1e-3) {
                double ry = Math.toRadians(p.getYRot());
                side = new Vec3(Math.sin(ry), 0, -Math.cos(ry));
            }
            Vec3 land = pred.add(side.normalize().scale(Tuning.maceOffset())); // learned from hits and misses
            double ex = land.x - (p.getX() + v.x * carry), ez = land.z - (p.getZ() + v.z * carry);
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
            return new double[]{ex, ez, t};
        }

        private int airLungeAim;
        private float airLungeYaw;

        /**
         * Spear-mace lunge swap: in the air, a jab with a Lunge spear pushes us 0.46 blocks/tick per level in the look
         * direction (horizontal only; works mid-air, not while gliding or in water, needs food 7+ – from the
         * enchantment data). When the fall would land off target by more than the air keys can fix, it turns flat
         * towards the gap (one tick, so the server has that rotation), jabs with the spear and swaps straight back to
         * the mace – the smash bonus doesn't depend on the swing charge, so the jab costs nothing there.
         * Returns true in the tick of the jab (no smash in the same tick).
         */
        private boolean airLunge(Minecraft mc, LocalPlayer p, double[] err) {
            double ex = err[0], ez = err[1];
            int t = (int) err[2];
            double miss = Math.hypot(ex, ez);
            if (lungeCd > 0 || p.isFallFlying() || p.isInWater() || p.getFoodData().getFoodLevel() < 7 || t < 4 || miss > 9) { // >9: a wind-burst bounce, not a drift
                airLungeAim = 0;
                return false;
            }
            int slot = hotbar(mc, s -> id(s).endsWith("_spear") ? Blast.level(s, "lunge") : 0);
            if (slot < 0) return false;
            int lvl = Blast.level(p.getInventory().getItem(slot), "lunge");
            double push = 0.458 * lvl * (1 - Math.pow(0.91, t)) / 0.09; // how far the impulse carries in the time left
            double fix = 0.12 * t; // what the air keys manage on their own
            if (airLungeAim == 0) {
                if (miss < fix + 1.0 || push > miss + 2.5) return false; // the keys do it, or the push would overshoot
                airLungeYaw = (float) Math.toDegrees(Math.atan2(-ex, ez));
                airLungeAim = 1;
                p.setYRot(airLungeYaw);
                p.setXRot(0f);
                p.setYHeadRot(airLungeYaw);
                return false;
            }
            airLungeAim = 0;
            p.setYRot(airLungeYaw);
            p.setXRot(0f);
            var inv = p.getInventory();
            int hand = inv.getSelectedSlot();
            inv.setSelectedSlot(slot);
            try {
                var m = Minecraft.class.getDeclaredMethod("startAttack");
                m.setAccessible(true);
                m.invoke(mc);
            } catch (ReflectiveOperationException e) {
                Bot.swing(p);
            }
            inv.setSelectedSlot(hand);
            lungeCd = 12;
            mode = "mace · lunge swap";
            CombatLog.log("air lunge  miss " + CombatLog.f(miss) + "  push " + CombatLog.f(push) + "  ticks left " + t);
            return true;
        }

        private int launchTick, lastSmash = -1;
        private double launchFrom;

        private boolean mace(Minecraft mc, LocalPlayer p, double d) {
            boolean charges = has(mc, "wind_charge");
            double hd = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
            // stage 1: on the ground – wind charge so the blast throws us up AND towards them
            boolean shieldWall = target.isBlocking() && hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0) < 0; // blocked without an axe
            // trained: "no damage for 0.75 s" never happened against someone who keeps swinging – a fresh hit 0.4 s ago is enough
            if (maceStage == 0 && charges && p.onGround() && d < 7 && d > 2.0 && ticks - lastHitOnUs > 8 && p.getHealth() + p.getAbsorptionAmount() >= 6 && !shieldWall) {
                float toThem = yawTo(p.position(), target.position());
                // close: straight down. Further: the charge hits the ground behind us, the blast pushes us towards them
                // trained: a tilted launch lands the charge further back and lifts us only 0.3–1 block (no smash at all);
                // straight down gives the full height, the air keys and the lunge swap do the sideways part
                float yaw = hd <= 4 ? p.getYRot() : toThem + 180f;
                float pitch = hd <= 4 ? 90f : (float) Math.max(70, Math.min(85, 90 - (hd - 4) * Tuning.launchTilt()));
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
            if (maceStage == 1 || p.fallDistance > 3) { // trained: crit jumps (fall 1.6–2) were taken for smashes
                if (p.onGround() && maceStage == 1 && ticks - launchTick > 6) {
                    maceStage = 0; // landed without a hit
                    CombatLog.log("MISS  landed " + CombatLog.f(hd) + " blocks from them (launched at " + CombatLog.f(launchFrom) + ")");
                    Tuning.mace(false, hd);
                    if (launchFrom > 4) Tuning.launch(launchFrom, hd);
                    return false;
                }
                hotbar(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) : 0);
                Bot.holdBest(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) : 0);
                if (airLunge(mc, p, airSteer(mc, p))) return true;
                boolean falling = p.getDeltaMovement().y < -0.08;
                double reach = reachTo(p, target);
                if (falling && reach <= p.entityInteractionRange() - 0.05 && holding(p, "mace") && p.fallDistance > 1.5 && lastSmash != ticks) {
                    lastSmash = ticks;
                    boolean burst = enchanted(p.getMainHandItem(), "wind_burst");
                    float before = target.getHealth();
                    CombatLog.log("SMASH  reach " + CombatLog.f(reach) + "  fall " + CombatLog.f(p.fallDistance) + "  their hp " + CombatLog.f(before)
                            + (target.isBlocking() ? "  (shield up)" : ""));
                    smash(mc, p);
                    Tuning.mace(true, 0);
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

        /** Blocks of free air under us (up to 40). */
        private static int airBelow(Minecraft mc, LocalPlayer p) {
            BlockPos b = p.blockPosition();
            for (int i = 1; i <= 40; i++) if (!Bot.passable(mc, b.below(i))) return i - 1;
            return 40;
        }

        private static final double FLY_ALT = 22; // height above them for the drop: ~25 fall = a 30+ damage smash

        /**
         * Elytra mace:
         * <ol>
         *   <li>elytra on, jump, open the wings;</li>
         *   <li>climb with rockets – nearly straight up when they are close, so it can start right in the fight;</li>
         *   <li>at ~22 above them: fly over and brake (nose up, no rockets) until the point where a free fall would carry
         *   us lands within what the air keys can still correct – worked out as a vector (our momentum with air drag
         *   0.91 per tick over the real fall time against where they will be), not just "distance &lt; carry";</li>
         *   <li>chestplate on (elytra off) → free fall, steered to land just beside them, mace smash when in reach
         *   (stun slam against a shield);</li>
         *   <li>missed: wings open again (elytra back on) and glide down, or a wind charge under the feet, so the fall
         *   doesn't kill us.</li>
         * </ol>
         * Never flies into blocks (see {@link #steerClear}).
         */
        private boolean elytraMace(Minecraft mc, LocalPlayer p, double d) {
            if (flyCd > 0) flyCd--;
            boolean can = has(mc, "mace") && has(mc, "firework_rocket") && (wearingElytra(p) || has(mc, "elytra"))
                    && Bot.count(mc, "firework_rocket") >= 2;
            if (flyStage == 0) {
                // start right in the fight too: only not while their hits keep landing (taking off takes half a second)
                boolean quiet = ticks - lastHitOnUs > 12 || d > 6;
                if (d < 6 && has(mc, "end_crystal") && crystalPotential(mc, p) >= minDamage()) return false; // crystals hit harder right here
                if (!can || flyCd > 0 || d > 64 || !quiet || p.getHealth() + p.getAbsorptionAmount() < 8
                        || !mc.level.canSeeSky(p.blockPosition().above(2)) || !openAbove(mc, target) || !p.onGround()) return false;
                flyStage = 1;
                flyTicks = 0;
                climbYaw = null;
                CombatLog.log("elytra mace: take off  dist " + CombatLog.f(d));
            }
            flyTicks++;
            mode = "elytra mace";
            if (flyTicks > 20 * 25 || !target.isAlive()) return flyStage == 5 ? recover(mc, p) : endFlight(mc);
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
                case 2 -> { // open the wings in the air (a fresh key press each time)
                    if (p.isFallFlying()) {
                        flyStage = 3;
                        rocketCd = 0;
                    } else {
                        mc.options.keyJump.setDown(flyTicks % 2 == 0);
                        if (p.onGround() && flyTicks > 20) return endFlight(mc);
                    }
                }
                case 3 -> { // climb
                    mc.options.keyJump.setDown(false);
                    boolean near = hd < 14;
                    if (near && climbYaw == null) climbYaw = p.getYRot();
                    float[] aim = steerClear(mc, p, near && climbYaw != null ? climbYaw : yawT, near ? -82f : -40f);
                    p.setYRot(aim[0]);
                    p.setXRot(aim[1]);
                    if (--rocketCd <= 0 && quickUse(mc, p, "firework_rocket", aim[0], aim[1])) rocketCd = 18;
                    if (p.getY() > t.y + FLY_ALT) {
                        flyStage = 4;
                        CombatLog.log("elytra mace: at height " + CombatLog.f(p.getY() - t.y) + "  horizontal " + CombatLog.f(hd));
                    }
                    if (!p.isFallFlying() && p.onGround()) return endFlight(mc);
                }
                case 4 -> { // over them: line up the drop
                    Vec3 v = p.getDeltaMovement();
                    Vec3 hv = new Vec3(v.x, 0, v.z);
                    int fall = Math.max(1, Math.min(60, ticksToFallFromRest(p)));
                    double k = (1 - Math.pow(0.91, fall)) / 0.09;
                    Vec3 land = p.position().add(hv.scale(k));
                    Vec3 tv = target.getDeltaMovement();
                    Vec3 tPred = t.add(tv.x * Math.min(fall, 20), 0, tv.z * Math.min(fall, 20));
                    double miss = Math.hypot(land.x - tPred.x, land.z - tPred.z);
                    double fix = Math.min(4.0, Tuning.elytraFix() * fall); // what the air keys can still move us on the way down (learned)
                    double toPred = Math.hypot(tPred.x - p.getX(), tPred.z - p.getZ());
                    float yawP = yawTo(p.position(), tPred);
                    if (miss <= fix + 0.6 && hv.length() < 1.1) {
                        Guard.keepElytra = false;
                        equip(mc, p, "chestplate"); // elytra off → free fall
                        hotbar(mc, s -> id(s).equals("mace") ? 1 : 0);
                        mc.options.keyJump.setDown(false);
                        flyStage = 5;
                        mode = "elytra mace · dropping";
                        CombatLog.log("elytra mace: DROP  height " + CombatLog.f(p.getY() - t.y) + "  predicted miss " + CombatLog.f(miss)
                                + "  air fix " + CombatLog.f(fix) + "  speed " + CombatLog.f(hv.length()));
                        return true;
                    }
                    // brake when the momentum would carry us past them, otherwise fly at them
                    boolean overshoot = hv.length() * k > toPred - 1;
                    float pitch;
                    if (p.getY() < t.y + FLY_ALT - 5) pitch = -35f; // lost height: climb back
                    else if (overshoot) pitch = -60f; // nose up: speed turns into height
                    else pitch = p.getY() > t.y + FLY_ALT + 8 ? 15f : 0f;
                    float[] aim = steerClear(mc, p, yawP, pitch);
                    p.setYRot(aim[0]);
                    p.setXRot(aim[1]);
                    mode = overshoot ? "elytra mace · braking over them" : "elytra mace · flying over";
                    boolean lowSlow = p.getY() < t.y + FLY_ALT - 5 || toPred > 14 && hv.length() < 0.8;
                    if (lowSlow && --rocketCd <= 0 && quickUse(mc, p, "firework_rocket", aim[0], aim[1])) rocketCd = 25;
                    else if (!lowSlow) rocketCd = Math.min(rocketCd, 5);
                    if (!p.isFallFlying() && p.onGround()) return endFlight(mc);
                }
                case 5 -> { // free fall onto them, steered, smash the moment they are in reach
                    if (airLunge(mc, p, airSteer(mc, p))) return true;
                    Bot.holdBest(mc, s -> id(s).equals("mace") ? 1 + (enchanted(s, "density") ? 1 : 0) : 0);
                    if (reachTo(p, target) <= p.entityInteractionRange() - 0.05 && p.getDeltaMovement().y < -0.2 && p.fallDistance > 1.5) {
                        CombatLog.log("ELYTRA SMASH  reach " + CombatLog.f(reachTo(p, target)) + "  fall " + CombatLog.f(p.fallDistance) + "  their hp " + CombatLog.f(target.getHealth()));
                        smash(mc, p);
                        Tuning.elytra(true);
                        flyStage = 6;
                        return true;
                    }
                    // gone past them (below their feet), or they're out of reach for good: don't hit the ground at full speed
                    boolean passed = p.getY() < target.getY() - 0.5;
                    boolean hopeless = hd > 6 && airBelow(mc, p) < 12;
                    if (passed || hopeless) {
                        CombatLog.log("ELYTRA MISS  " + CombatLog.f(hd) + " blocks from them, " + airBelow(mc, p) + " blocks of air below");
                        Tuning.elytra(false);
                        return recover(mc, p);
                    }
                    if (p.onGround()) {
                        CombatLog.log("ELYTRA MISS  landed " + CombatLog.f(hd) + " blocks from them");
                        return endFlight(mc);
                    }
                }
                case 7 -> { // recovering: wings open again, then glide down
                    if (p.onGround() || p.isInWater()) return endFlight(mc);
                    if (p.isFallFlying()) {
                        flyStage = 6;
                        return true;
                    }
                    if (!wearingElytra(p)) {
                        Guard.keepElytra = true;
                        equip(mc, p, "elytra");
                        return true;
                    }
                    mc.options.keyJump.setDown(flyTicks % 2 == 0);
                    // close to the ground and still not gliding: a wind charge takes the fall
                    if (airBelow(mc, p) <= 3 && p.getDeltaMovement().y < -0.5 && has(mc, "wind_charge"))
                        quickUse(mc, p, "wind_charge", p.getYRot(), 90f);
                }
                default -> { // done or aborted: glide down and land
                    mc.options.keyJump.setDown(false);
                    if (wearingElytra(p) && p.isFallFlying()) {
                        float[] aim = steerClear(mc, p, p.getYRot(), 20f);
                        p.setYRot(aim[0]);
                        p.setXRot(aim[1]);
                    }
                    if (p.onGround() || p.isInWater() || !p.isFallFlying() && !wearingElytra(p)) return endFlight(mc);
                    if (!p.isFallFlying() && airBelow(mc, p) > 4) flyStage = 7; // falling without wings: open them
                }
            }
            return true;
        }

        /** After a missed drop: wind charge if the ground is close, otherwise the elytra back on to glide down. */
        private boolean recover(Minecraft mc, LocalPlayer p) {
            int air = airBelow(mc, p);
            if (air <= 3 && has(mc, "wind_charge") && quickUse(mc, p, "wind_charge", p.getYRot(), 90f)) {
                mode = "elytra mace · missed, fall cancel";
                flyStage = 6;
                return true;
            }
            if (air > 3 && (wearingElytra(p) || has(mc, "elytra"))) {
                mode = "elytra mace · missed, wings out";
                flyStage = 7;
                return true;
            }
            return endFlight(mc);
        }

        /** Ticks of free fall from the current height down to their head, starting from zero vertical speed. */
        private int ticksToFallFromRest(LocalPlayer p) {
            double y = p.getY(), vy = Math.min(0, p.getDeltaMovement().y), goal = target.getBoundingBox().maxY;
            for (int t = 0; t < 100; t++) {
                if (y <= goal) return t;
                vy = (vy - 0.08) * 0.98;
                y += vy;
            }
            return 100;
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
        /**
         * Damage to us (real numbers: armor, blast protection, exposure) must leave at least 2 hearts, and one blast
         * never takes more than 5 hearts. With a totem in the off hand a little more is fine.
         */
        private static boolean safeForUs(double us, float hp, boolean totem) {
            return us < hp - 4 && us <= (totem ? 12 : 10);
        }

        private boolean safeForUs(double us, float hp) {
            return safeForUs(us, hp, Guard.hasTotem(Minecraft.getInstance().player));
        }

        /** A crystal in reach of us that would go off when they hit it (or another blows up) must be safe too. */
        private boolean crystalsAroundSafe(Minecraft mc, LocalPlayer p, float hp, Vec3 extra) {
            double total = extra == null ? 0 : crystalDamage(p, extra);
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(12), EndCrystal::isAlive))
                if (crystalQuick(p, c.position()) > 0) total = Math.max(total, crystalDamage(p, c.position())); // they chain-explode one at a time, the worst one counts
            return safeForUs(total, hp);
        }

        /** Smallest blast worth the time: 2 hearts – down to half a heart when it finishes them. */
        private double minDamage() {
            double left = target.getHealth() + target.getAbsorptionAmount();
            return Math.min(4, Math.max(1, left));
        }

        /** Where a crystal can go on this base: the block above is air and nothing stands in the 2 blocks above it. */
        private static boolean crystalFits(Minecraft mc, BlockPos base) {
            BlockPos a1 = base.above();
            if (!mc.level.getBlockState(a1).isAir()) return false;
            AABB room = new AABB(a1.getX(), a1.getY(), a1.getZ(), a1.getX() + 1, a1.getY() + 2, a1.getZ() + 1);
            return mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, room, e -> !(e instanceof EndCrystal) || e.isAlive()).isEmpty();
        }

        /** The most a crystal could do to them right now (safe spots only): existing crystals, bases, or a base we could set. */
        private double crystalPotential(Minecraft mc, LocalPlayer p) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            double best = 0;
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(6), EndCrystal::isAlive))
                if (eye.distanceTo(c.position()) <= 4.6 && crystalQuick(target, c.position()) > best && safeForUs(crystalDamage(p, c.position()), hp))
                    best = Math.max(best, crystalDamage(target, c.position()));
            BlockPos t = target.blockPosition();
            boolean obi = has(mc, "obsidian");
            java.util.List<Spot> spots = new java.util.ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
                String bid = Bot.id(mc.level.getBlockState(b));
                boolean base = bid.equals("obsidian") || bid.equals("bedrock");
                int ring = Math.max(Math.abs(b.getX() - t.getX()), Math.abs(b.getZ() - t.getZ()));
                boolean canSet = obi && !base && ring >= 1 && ring <= 3 && b.getY() >= t.getY() - 1 && b.getY() <= t.getY()
                        && mc.level.getBlockState(b).isAir() && Bot.solid(mc, b.below());
                if (!base && !canSet) continue;
                if (!mc.level.getBlockState(b.above()).isAir() || eye.distanceTo(Vec3.atCenterOf(b)) > 4.5) continue;
                Vec3 c = Vec3.atBottomCenterOf(b.above());
                double q = crystalQuick(target, c);
                if (q > best) spots.add(new Spot(b.immutable(), c, q));
            }
            spots.sort((a, b) -> Double.compare(b.quick(), a.quick()));
            int exact = 0;
            for (Spot sp : spots) { // best first; the exact value is never above the quick one, so stop early
                if (sp.quick() <= best || ++exact > 10) break;
                double them = crystalDamage(target, sp.c());
                if (them > best && safeForUs(crystalDamage(p, sp.c()), hp)) best = them;
            }
            return best;
        }

        /** A candidate blast spot with its cheap upper-bound damage to the target. */
        private record Spot(BlockPos base, Vec3 c, double quick) {
        }

        private int sinceSwordHit = 100;
        private final java.util.Map<Integer, Integer> recentlyHit = new java.util.HashMap<>();

        /**
         * Crystal fight without waiting: every tick either breaks the best crystal or places one. Spots are rated on
         * the real damage (their armor and blast protection, how much of them the blast can see), so it finds spots
         * that pay against netherite instead of skipping them on a worst-case guess. Breaking comes before placing.
         */
        private boolean crystal(Minecraft mc, LocalPlayer p) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            double minDmg = minDamage();
            recentlyHit.entrySet().removeIf(e -> ticks - e.getValue() > 4);
            // 1) break any crystal that hurts them more than us
            EndCrystal hit = null;
            double hitScore = 0;
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(p.blockPosition()).inflate(6), c -> c.isAlive() && !recentlyHit.containsKey(c.getId()))) {
                if (eye.distanceTo(c.getBoundingBox().getCenter()) > p.entityInteractionRange()) continue;
                if (crystalQuick(target, c.position()) < minDmg) continue;
                double them = crystalDamage(target, c.position()), us = crystalDamage(p, c.position());
                if (!safeForUs(us, hp)) continue; // never risk blowing ourselves up – totem or not
                double score = them - us * 0.6;
                if (them >= minDmg && score > hitScore) {
                    hitScore = score;
                    hit = c;
                }
            }
            if (hit != null && crystalsAroundSafe(mc, p, hp, null)) { // neighbours go off too
                if (p.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS)) Bot.holdBest(mc, Guard::weaponScore); // a fist can't break it with weakness
                Bot.look(p, hit.position().add(0, 0.8, 0));
                recentlyHit.put(hit.getId(), ticks);
                mc.gameMode.attack(p, hit);
                Bot.swing(p);
                mode = "crystal · break";
                return true;
            }
            if (crystalCd > 0) { // the crystal we just set needs a tick to show up – meanwhile the sword fight goes on
                crystalCd--;
                return false;
            }
            // 2) place a crystal on the best obsidian/bedrock block near them (face place included)
            BlockPos t = target.blockPosition(), best = null;
            double bestScore = 0;
            java.util.List<Spot> spots = new java.util.ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
                String bid = Bot.id(mc.level.getBlockState(b));
                if (!bid.equals("obsidian") && !bid.equals("bedrock")) continue;
                if (eye.distanceTo(Vec3.atCenterOf(b).add(0, 0.5, 0)) > 4.5) continue;
                Vec3 c = Vec3.atBottomCenterOf(b.above());
                double q = crystalQuick(target, c);
                if (q < minDmg || !crystalFits(mc, b)) continue;
                var seen = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(b).add(0, 0.5, 0),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                if (seen.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !seen.getBlockPos().equals(b)
                        && eye.distanceTo(Vec3.atCenterOf(b)) > 3) continue; // behind a wall and not close: skip
                spots.add(new Spot(b.immutable(), c, q));
            }
            spots.sort((a, b) -> Double.compare(b.quick(), a.quick()));
            int exact = 0;
            for (Spot sp : spots) {
                if (sp.quick() <= bestScore || ++exact > 10) break;
                double them = crystalDamage(target, sp.c());
                if (them < minDmg || them <= bestScore) continue;
                double us = crystalDamage(p, sp.c());
                if (!safeForUs(us, hp)) continue;
                double score = them - us * 0.6;
                if (score > bestScore) {
                    bestScore = score;
                    best = sp.base();
                }
            }
            if (best != null && crystalsAroundSafe(mc, p, hp, Vec3.atBottomCenterOf(best.above()))) {
                if (!Bot.holdBest(mc, s -> id(s).equals("end_crystal") ? 1 : 0)) return false;
                if (!holding(p, "end_crystal")) return true;
                Bot.look(p, Vec3.atCenterOf(best).add(0, 0.5, 0));
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(best).add(0, 0.5, 0), Direction.UP, best, false));
                Bot.swing(p);
                crystalCd = 1;
                mode = "crystal · place";
                CombatLog.log("crystal  their est. " + CombatLog.f(crystalDamage(target, Vec3.atBottomCenterOf(best.above()))) + "  ours " + CombatLog.f(crystalDamage(p, Vec3.atBottomCenterOf(best.above()))));
                return true;
            }
            // 3) no spot: one obsidian base next to them on our side, at most two bases every 10 s
            if (!has(mc, "obsidian")) return false;
            if (++obiWindow > 200) {
                obiWindow = 0;
                obiPlaced = 0;
            }
            if (obiPlaced >= 4) return false; // trained: 2 per 10 s left it swinging a sword on open ground most of the fight
            Vec3 toUs = p.position().subtract(target.position()).multiply(1, 0, 1);
            BlockPos spotBest = null;
            double spotScore = 0;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    int ring = Math.max(Math.abs(dx), Math.abs(dz));
                    if (ring == 0) continue;
                    for (int dy = -1; dy <= 0; dy++) {
                        BlockPos spot = t.offset(dx, dy, dz);
                        if (!mc.level.getBlockState(spot).isAir() || !mc.level.getBlockState(spot.above()).isAir()) continue;
                        if (!Bot.solid(mc, spot.below())) continue;
                        if (new AABB(spot).intersects(p.getBoundingBox()) || new AABB(spot).intersects(target.getBoundingBox())) continue;
                        if (eye.distanceTo(Vec3.atCenterOf(spot)) > 4.5) continue;
                        if (!mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(spot)).isEmpty()) continue;
                        Vec3 c = Vec3.atBottomCenterOf(spot.above());
                        double them = crystalQuick(target, c);
                        if (them < minDmg) continue;
                        double score = them + 0.2 * (toUs.x * dx + toUs.z * dz) / Math.max(0.1, toUs.length()); // our side first
                        if (score > spotScore && safeForUs(crystalQuick(p, c) * 0.8, hp)) {
                            spotScore = score;
                            spotBest = spot;
                        }
                    }
                }
            if (spotBest == null) return false;
            if (!Bot.holdBest(mc, s -> id(s).equals("obsidian") ? 1 : 0)) return false;
            if (!holding(p, "obsidian")) return true;
            BlockPos floor = spotBest.below();
            Bot.look(p, Vec3.atCenterOf(floor).add(0, 0.5, 0));
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
            Bot.swing(p);
            crystalCd = 1;
            obiPlaced++;
            mode = "crystal · obsidian base";
            return true;
        }

        // ------------------------------------------------------------------ respawn anchor
        private BlockPos anchorPos;
        private int anchorTicks, anchorCd;

        /**
         * Respawn anchor outside the Nether: place it next to them, charge it with glowstone, then use it with anything
         * else in hand – it blows up (power 5). Same damage model and safety rule as the crystals.
         */
        private boolean anchor(Minecraft mc, LocalPlayer p) {
            if (anchorCd > 0) anchorCd--;
            if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER) return false; // there it only sets the spawn
            if (!has(mc, "glowstone")) return false;
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            if (anchorPos != null) {
                var st = mc.level.getBlockState(anchorPos);
                Vec3 c = Vec3.atCenterOf(anchorPos);
                if (!Bot.id(st).equals("respawn_anchor") || ++anchorTicks > 40 || eye.distanceTo(c) > 4.5) {
                    if (anchorTicks > 40 || !Bot.id(st).equals("respawn_anchor")) anchorPos = null;
                    return false;
                }
                BlockHitResult face = new BlockHitResult(c.add(0, 0.5, 0), Direction.UP, anchorPos, false);
                mc.options.keyShift.setDown(false); // sneaking would place the item instead
                if (st.getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE) == 0) {
                    if (!Bot.holdBest(mc, s -> id(s).equals("glowstone") ? 1 : 0)) return false;
                    if (!holding(p, "glowstone")) return true;
                    Bot.look(p, c);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, face);
                    Bot.swing(p);
                    mode = "anchor · charge";
                    return true;
                }
                double them = Blast.damage(target, target.position(), c, Blast.ANCHOR, true, anchorPos);
                double us = Blast.damage(p, p.position(), c, Blast.ANCHOR, true, anchorPos);
                if (!safeForUs(us, hp)) { // too close to it ourselves: step back first
                    Guard.backOff(mc, p, c);
                    mode = "anchor · stepping back";
                    return true;
                }
                if (them < minDamage()) return false; // they moved off: keep it for later
                if (holding(p, "glowstone") || holding(p, "respawn_anchor")) { // anything else in hand sets it off
                    var inv = p.getInventory();
                    for (int i = 0; i < 9; i++) {
                        String hid = id(inv.getItem(i));
                        if (!hid.equals("glowstone") && !hid.equals("respawn_anchor")) {
                            inv.setSelectedSlot(i);
                            break;
                        }
                    }
                    if (holding(p, "glowstone")) return false;
                }
                Bot.look(p, c);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, face);
                Bot.swing(p);
                CombatLog.log("anchor  their est. " + CombatLog.f(them) + "  ours " + CombatLog.f(us));
                anchorPos = null;
                anchorCd = 3;
                mode = "anchor · boom";
                return true;
            }
            if (anchorCd > 0 || !has(mc, "respawn_anchor")) return false;
            double minDmg = minDamage();
            BlockPos t = target.blockPosition(), best = null;
            double bestScore = 0;
            java.util.List<Spot> cands = new java.util.ArrayList<>();
            for (BlockPos b : BlockPos.betweenClosed(t.offset(-2, -1, -2), t.offset(2, 2, 2))) {
                if (!mc.level.getBlockState(b).isAir() || !Bot.solid(mc, b.below())) continue;
                AABB box = new AABB(b);
                if (box.intersects(p.getBoundingBox()) || box.intersects(target.getBoundingBox())) continue;
                if (!mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, box).isEmpty()) continue;
                Vec3 c = Vec3.atCenterOf(b);
                if (eye.distanceTo(c) > 4.5) continue;
                double quick = Blast.damage(target, target.position(), c, Blast.ANCHOR, false);
                if (quick >= minDmg) cands.add(new Spot(b.immutable(), c, quick));
            }
            cands.sort((a, b) -> Double.compare(b.quick(), a.quick()));
            int exact = 0;
            for (Spot sp : cands) {
                if (sp.quick() <= bestScore || ++exact > 8) break;
                double them = Blast.damage(target, target.position(), sp.c(), Blast.ANCHOR, true, sp.base());
                if (them < minDmg) continue;
                double us = Blast.damage(p, p.position(), sp.c(), Blast.ANCHOR, true, sp.base());
                if (!safeForUs(us, hp)) continue;
                double score = them - us * 0.6;
                if (score > bestScore) {
                    bestScore = score;
                    best = sp.base();
                }
            }
            if (best == null) return false;
            if (!Bot.holdBest(mc, s -> id(s).equals("respawn_anchor") ? 1 : 0)) return false;
            if (!holding(p, "respawn_anchor")) return true;
            BlockPos floor = best.below();
            Bot.look(p, Vec3.atCenterOf(floor).add(0, 0.5, 0));
            mc.options.keyShift.setDown(false);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
            Bot.swing(p);
            anchorPos = best;
            anchorTicks = 0;
            mode = "anchor · place";
            return true;
        }

        // ------------------------------------------------------------------ cart PvP (TNT minecart)
        private BlockPos railPos;
        private double instaDist;
        private int awayTicks;
        private int cartStage, cartTicks, cartCd, cartsPlaced, drawTicks, retreatTicks, missCount, blockedTicks, cartsAtShot;

        /** Ticks to draw for an insta-cart shot: just enough speed that the arrow gets to the spot in ~6 ticks without a high arc. */
        private static int instaDraw(Vec3 eye, Vec3 c) {
            double d = eye.distanceTo(c);
            return (d < 2.5 ? 4 : d < 3.5 ? 5 : d < 4.5 ? 6 : 7) + Tuning.instaBias(d); // learned: more draw where it missed
        }

        /** Nothing solid between our eyes and the point (blocks only). */
        private static boolean clearShot(Minecraft mc, LocalPlayer p, Vec3 to) {
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceTo(to) < 0.3;
        }

        /** One cart, or two (overload) when the armor takes even the chained blast. */
        private int instaCarts(LocalPlayer p, float hp, Vec3 c) {
            if (Bot.count(Minecraft.getInstance(), "tnt_minecart") < 2 || blastProt(p) < 8) return 1;
            float pw = (float) (4 + 1.5 * 3 * bowPower(instaDraw(p.getEyePosition(), c))) + 3f;
            return safeForUs(Blast.damage(p, p.position(), c, pw, true), hp) ? 2 : 1;
        }

        /** Insta cart works from here: in place reach, the slow arrow's blast is safe for us, and it still hurts them. */
        private boolean instaReady(Minecraft mc, LocalPlayer p, BlockPos spot) {
            Vec3 eye = p.getEyePosition(), c = Vec3.atBottomCenterOf(spot).add(0, 0.35, 0);
            if (eye.distanceTo(Vec3.atCenterOf(spot)) > 4.4 || eye.distanceTo(c) < 1.6) return false;
            if (target.getBoundingBox().inflate(0.35).clip(eye, c).isPresent()) return false; // they'd catch the arrow
            if (!clearShot(mc, p, c)) return false;
            float hp = p.getHealth() + p.getAbsorptionAmount();
            float pw = (float) (4 + 1.5 * 3 * bowPower(instaDraw(eye, c)));
            return safeForUs(Blast.damage(p, p.position(), c, pw, true), hp) && Blast.damage(target, c, 4f) >= minDamage();
        }

        /** Another hotbar slot holding a TNT minecart. */
        private int hotbarExcept(Minecraft mc, int not) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 9; i++) if (i != not && id(inv.getItem(i)).equals("tnt_minecart")) return i;
            return -1;
        }

        /** Explosion protection points on our armor (blast protection counts double, like the damage formula). */
        private static int blastProt(LivingEntity e) {
            int epf = 0;
            for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                ItemStack it = e.getItemBySlot(slot);
                epf += Blast.level(it, "protection") + 2 * Blast.level(it, "blast_protection");
            }
            return epf;
        }

        /** Bow charge after t ticks of drawing (vanilla BowItem.getPowerForTime). */
        private static float bowPower(int t) {
            float f = t / 20f;
            f = (f * f + f * 2) / 3;
            return Math.min(1f, f);
        }

        /**
         * Pitch for an arrow to hit a point d blocks away horizontally and dy blocks above the bow, found by simulating the
         * vanilla arrow (moves, then speed × 0.99 and gravity 0.05 every tick; spawns 0.1 below the eyes) and bisecting the
         * angle. Trained: a closed-form arc without drag aimed up to 0.3 blocks low – the arrows stuck in the ground in
         * front of the cart.
         */
        private static float bowPitch(double d, double dy, double v) {
            double lo = -60, hi = 60; // degrees of elevation (up = positive)
            for (int it = 0; it < 24; it++) {
                double mid = (lo + hi) / 2;
                double y = arrowHeightAt(Math.toRadians(mid), v, d);
                if (Double.isNaN(y) || y < dy) lo = mid; else hi = mid;
            }
            return (float) -((lo + hi) / 2);
        }

        /** Height of the arrow (relative to the bow) when it has flown d blocks horizontally, NaN if it never gets there. */
        private static double arrowHeightAt(double elev, double v, double d) {
            double x = 0, y = -0.1, vx = v * Math.cos(elev), vy = v * Math.sin(elev);
            for (int t = 0; t < 80; t++) {
                double nx = x + vx, ny = y + vy;
                if (nx >= d) return y + (ny - y) * (d - x) / Math.max(1e-6, nx - x);
                x = nx;
                y = ny;
                vx *= 0.99;
                vy = vy * 0.99 - 0.05;
            }
            return Double.NaN;
        }

        /**
         * A point on the cart the arrow can reach without hitting the target first: the cart is wider than a player
         * (0.98 vs 0.6), so with them standing on it the edges still stick out. Centre if the line is clear, otherwise
         * the clearest edge, low on the cart.
         */
        private Vec3 cartAimPoint(Vec3 eye, AABB cart) {
            Vec3 c = cart.getCenter();
            AABB them = target.getBoundingBox().inflate(0.35); // arrows hit hitboxes grown by 0.3
            double y = cart.minY + (cart.maxY - cart.minY) * 0.55; // middle: room for error both ways, never the ground
            Vec3[] pts = {new Vec3(c.x, y, c.z), new Vec3(cart.minX + 0.08, y, c.z), new Vec3(cart.maxX - 0.08, y, c.z),
                    new Vec3(c.x, y, cart.minZ + 0.08), new Vec3(c.x, y, cart.maxZ - 0.08), new Vec3(c.x, cart.maxY - 0.05, c.z)};
            for (Vec3 pt : pts) if (them.clip(eye, pt).isEmpty()) return pt;
            return null; // no clear line: move first
        }
        private static final String[] RAILS = {"rail", "powered_rail", "detector_rail"}; // no activator rail: it would set the cart off unseen

        private static boolean isRail(String id) {
            for (String r : RAILS) if (r.equals(id)) return true;
            return false;
        }

        private int flameBow(Minecraft mc) {
            return hotbar(mc, s -> id(s).equals("bow") && Blast.level(s, "flame") > 0 ? 1 + Blast.level(s, "power") * 0.1 : 0);
        }

        private static boolean hasArrows(Minecraft mc) {
            return Bot.count(mc, "arrow", "spectral_arrow", "tipped_arrow") > 0;
        }

        /** Rails, TNT minecarts, a Flame bow and arrows – everything cart PvP needs. */
        private boolean cartKit(Minecraft mc) {
            return cartCd == 0 && has(mc, "tnt_minecart") && Bot.count(mc, RAILS) > 0 && hasArrows(mc)
                    && Bot.count(mc, "bow") > 0 && mc.player.getInventory().getSelectedSlot() >= 0;
        }

        /** Our TNT minecarts on the rail (or just next to it). */
        private java.util.List<net.minecraft.world.entity.Entity> cartsAt(Minecraft mc, BlockPos rail) {
            return mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(rail).inflate(1.0),
                    e -> e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("tnt_minecart"));
        }

        /**
         * Cart PvP: a rail right under (or next to) them, one or two TNT minecarts on it, then a Flame arrow into the
         * cart – a burning arrow sets a TNT minecart off on the spot (power 4, checked in the 26.2 code). The spot is
         * rated with the same damage model as the crystals (their armor, blast protection, exposure) and must be safe
         * for us; while the bow draws it steps back. Rails go into their feet block too – a rail has no collision.
         */
        private boolean cart(Minecraft mc, LocalPlayer p) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Vec3 eye = p.getEyePosition();
            // a cart is worth the time from 2.5 hearts up (or when it finishes them) – armored players take little
            double minDmg = Math.max(minDamage(), Math.min(5, target.getHealth() + target.getAbsorptionAmount()));
            if (railPos == null) {
                if (cartCd > 0 || flameBow(mc) < 0 || !hasArrows(mc)) return false;
                // carts already lying around them (an earlier try that timed out): shoot those first – trained: left-over
                // carts got shoved into each other by the target and went off together next to us
                for (var e : mc.level.getEntities((net.minecraft.world.entity.Entity) null, target.getBoundingBox().inflate(3),
                        e -> e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("tnt_minecart"))) {
                    if (Blast.damage(target, e.getBoundingBox().getCenter(), 4f) < minDmg) continue;
                    railPos = e.blockPosition();
                    cartStage = 2;
                    cartTicks = drawTicks = retreatTicks = missCount = 0;
                    CombatLog.log("cart: shooting a cart that is already there");
                    break;
                }
            }
            if (railPos == null) {
                if (!cartKit(mc)) return false;
                BlockPos t = target.blockPosition(), best = null;
                double bestScore = 0;
                for (BlockPos b : BlockPos.betweenClosed(t.offset(-1, -1, -1), t.offset(1, 0, 1))) {
                    var st = mc.level.getBlockState(b);
                    boolean rail = isRail(Bot.id(st));
                    if (!rail && !st.isAir() || !Bot.solid(mc, b.below())) continue;
                    if (new AABB(b).intersects(p.getBoundingBox())) continue; // not under ourselves
                    // trained: a cart shoved over an edge blew up while we were still backing off (a TNT cart goes off
                    // when it falls 3+ blocks or crashes into a wall while moving) – only spots with ground all around
                    boolean flat = true;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos n = b.relative(dir);
                        if (!Bot.solid(mc, n.below()) && Bot.passable(mc, n)) flat = false;
                    }
                    if (!flat) continue;
                    if (!clearShot(mc, p, Vec3.atBottomCenterOf(b).add(0, 0.4, 0))) continue; // trained: shots from a crater hit its wall
                    Vec3 cc = Vec3.atBottomCenterOf(b).add(0, 0.4, 0);
                    if (Math.abs(Math.toDegrees(Math.atan2(cc.y - eye.y, Math.hypot(cc.x - eye.x, cc.z - eye.z)))) > 35 && p.getEyeY() < cc.y) continue; // steep shots up out of a hole missed
                    if (eye.distanceTo(Vec3.atCenterOf(b)) > 4.4) continue;
                    Vec3 c = Vec3.atBottomCenterOf(b).add(0, 0.35, 0);
                    double them = Blast.damage(target, c, 4f);
                    if (them < minDmg) continue;
                    // we shoot from a safe distance later, so only "can we get far enough" matters here: the spot must
                    // not be on the side we back off to. Beside them on our side keeps the cart out of their hitbox,
                    // so the arrow hits the cart and not them.
                    Vec3 toUs = p.position().subtract(target.position()).multiply(1, 0, 1);
                    double side = toUs.lengthSqr() < 1e-4 ? 0 : (toUs.normalize().x * (b.getX() - t.getX()) + toUs.normalize().z * (b.getZ() - t.getZ()));
                    boolean feet = b.getX() == t.getX() && b.getZ() == t.getZ();
                    boolean shoved = new AABB(b).intersects(target.getBoundingBox().inflate(0.05)); // they'd push the cart around
                    if (shoved && target.getBbWidth() > 0.9) continue; // a wide target hides the whole cart: no clear shot (trained vs. iron golem)
                    // trained: under their feet the arrow hits them, not the cart (arrows see every hitbox 0.3 bigger,
                    // so a player on the cart covers it); one block beside them on our side is nearly as strong
                    double score = them + (rail ? 1 : 0) + side * 1.5 - (feet ? 8 : 0) - (shoved && !feet ? 3 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = b.immutable();
                    }
                }
                if (best == null) return false;
                boolean insta = instaReady(mc, p, best);
                // trained vs. the sparring partner: the slow way (place, back off, full draw) never works on someone who
                // moves – they follow or walk off the rail. Only against a (nearly) standing target.
                if (!insta && target.getDeltaMovement().horizontalDistance() > 0.06) {
                    cartCd = 10;
                    return false;
                }
                railPos = best;
                cartStage = isRail(Bot.id(mc.level.getBlockState(best))) ? 1 : 0;
                if (insta) cartStage = 10;
                cartTicks = cartsPlaced = drawTicks = retreatTicks = missCount = 0;
                CombatLog.log("cart: rail spot " + best.toShortString() + "  their est. " + CombatLog.f(bestScore));
            }
            if (++cartTicks > 160 || !target.isAlive()) return endCart(mc, p, "timed out");
            Vec3 c = Vec3.atBottomCenterOf(railPos).add(0, 0.35, 0);
            if (eye.distanceTo(Vec3.atCenterOf(railPos)) > 5.5 && cartStage < 2 || eye.distanceTo(Vec3.atCenterOf(railPos)) > 30) return endCart(mc, p, "too far");
            mc.options.keyShift.setDown(false);
            switch (cartStage) {
                case 0 -> { // rail
                    if (isRail(Bot.id(mc.level.getBlockState(railPos)))) {
                        cartStage = 1;
                        return true;
                    }
                    if (!Bot.holdBest(mc, s -> isRail(id(s)) ? (id(s).equals("rail") ? 2 : 1) : 0)) return endCart(mc, p, "no rails");
                    if (!isRail(id(p.getMainHandItem()))) return true;
                    BlockPos floor = railPos.below();
                    Bot.look(p, Vec3.atCenterOf(floor).add(0, 0.5, 0));
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
                    Bot.swing(p);
                    mode = "cart · rail";
                }
                case 1 -> { // carts on the rail – two if there are, the second one hits through the first one's i-frames rarely, but doubles the chance
                    if (Blast.damage(target, c, 4f) < minDmg) return endCart(mc, p, "they moved off the rail");
                    // a second cart gets thrown by the first blast and goes off much harder (power 4 + up to 7.5 with
                    // speed) – trained: two carts killed us at 7 blocks without armor. Only with real blast protection.
                    int want = Math.min(blastProt(p) >= 8 ? 2 : 1, Bot.count(mc, "tnt_minecart") + cartsPlaced);
                    if (cartsPlaced >= want || cartsPlaced > 0 && !has(mc, "tnt_minecart")) {
                        if (!cartsAt(mc, railPos).isEmpty()) {
                            cartStage = 2;
                            drawTicks = 0;
                        } else if (cartTicks > 30) return endCart(mc, p, "carts didn't show up");
                        return true;
                    }
                    if (!Bot.holdBest(mc, s -> id(s).equals("tnt_minecart") ? 1 : 0)) return endCart(mc, p, "no carts");
                    if (!holding(p, "tnt_minecart")) return true;
                    Bot.look(p, Vec3.atCenterOf(railPos));
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atBottomCenterOf(railPos).add(0, 0.1, 0), Direction.UP, railPos, false));
                    Bot.swing(p);
                    cartsPlaced++;
                    mode = "cart · minecart " + cartsPlaced;
                }
                case 2 -> { // back off to a safe distance, then a full-draw flame arrow into the cart
                    var carts = cartsAt(mc, railPos);
                    if (carts.isEmpty()) return endCart(mc, p, "boom");
                    var cartBox = carts.get(0).getBoundingBox();
                    // the arrow's speed feeds the blast (26.2: power = 4 + random · 1.5 · min(5, arrow speed)), so a
                    // full draw can make it 8.5 instead of 4 – trained: that killed us at 7 blocks. Safety uses the worst
                    // case for the draw we plan at this distance; a second cart thrown by the first adds more.
                    double aimDist = eye.distanceTo(cartBox.getCenter());
                    int need = aimDist < 5 ? 10 : aimDist < 9 ? 15 : 20;
                    double arrowSpeed = 3.0 * bowPower(need);
                    float worst = (float) (4 + 1.5 * Math.min(5, arrowSpeed)) + (carts.size() >= 2 ? 3f : 0f);
                    int cluster = mc.level.getEntities((net.minecraft.world.entity.Entity) null, cartBox.inflate(2.5),
                            e -> e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("tnt_minecart")).size();
                    worst = Math.min(11.5f, worst + 3f * Math.max(0, cluster - Math.max(1, carts.size()))); // more carts nearby chain off too
                    double us = Blast.damage(p, p.position(), cartBox.getCenter(), worst, true);
                    int bow = flameBow(mc);
                    if (bow < 0 || !hasArrows(mc)) return endCart(mc, p, "no flame bow or arrows");
                    boolean widen = missCount >= 2 && aimDist < 8; // missed twice up close: a flatter angle from further away
                    if (p.isUsingItem() && !safeForUs(us, hp) && drawTicks >= need) {
                        // trained: it stood there fully drawn for 8 s waiting to be safe. Switching the slot cancels the
                        // draw without shooting – then back off again.
                        mc.options.keyUse.setDown(false);
                        var inv = p.getInventory();
                        inv.setSelectedSlot(inv.getSelectedSlot() == 0 ? 1 : 0);
                        drawTicks = 0;
                        CombatLog.log("cart: too close while drawn – cancel, back off");
                        return true;
                    }
                    if ((!safeForUs(us, hp) || widen) && !p.isUsingItem()) { // too close for a power 4 blast: run off first (sprinting, not drawing)
                        float away = yawTo(cartBox.getCenter(), p.position());
                        p.setYRot(away);
                        p.setXRot(0f);
                        mc.options.keyUp.setDown(true);
                        mc.options.keyDown.setDown(false);
                        mc.options.keySprint.setDown(true);
                        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
                        mode = carts.get(0).getDeltaMovement().horizontalDistance() > 0.03 ? "cart · backing off (cart rolling)" : "cart · backing off";
                        if (++retreatTicks > 90) return endCart(mc, p, "couldn't get far enough");
                        return true;
                    }
                    retreatTicks = 0;
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                    mc.options.keyJump.setDown(false);
                    mc.options.keyDown.setDown(false);
                    if (!clearShot(mc, p, cartBox.getCenter()) && !p.isUsingItem() && retreatTicks == 0) {
                        if (++blockedTicks > 10) return endCart(mc, p, "no line of sight to the cart"); // a new spot from a better place
                    }
                    Vec3 aim = cartAimPoint(eye, cartBox);
                    if (aim == null && !p.isUsingItem() && ++blockedTicks < 30) { // they stand in the line: step sideways
                        float side = yawTo(eye, cartBox.getCenter()) + 90f;
                        p.setYRot(side);
                        p.setXRot(0f);
                        mc.options.keyUp.setDown(true);
                        mc.options.keySprint.setDown(false);
                        mode = "cart · stepping aside for a clear shot";
                        return true;
                    }
                    if (aim == null) aim = cartBox.getCenter();
                    blockedTicks = 0;
                    // ballistic aim for the charge the arrow will have at release (gravity 0.05, drag 0.99)
                    float power = bowPower(Math.max(drawTicks + 1, 1));
                    float yaw = yawTo(eye, aim);
                    float pitch = bowPitch(Math.hypot(aim.x - eye.x, aim.z - eye.z), aim.y - eye.y, 3.0 * Math.max(0.3f, power));
                    p.setYRot(yaw); // every tick: the server shoots with the rotation of the last movement packet
                    p.setXRot(pitch);
                    p.setYHeadRot(yaw);
                    p.getInventory().setSelectedSlot(bow);
                    if (!p.isUsingItem()) {
                        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                        mc.options.keyUse.setDown(true);
                        drawTicks = 0;
                    } else drawTicks++;
                    mode = "cart · drawing";
                    double dist = eye.distanceTo(aim);
                    if (drawTicks >= need && Blast.damage(target, cartBox.getCenter(), 4f) < minDmg * 0.6) { // they left the cart: don't waste it
                        if (++awayTicks > 20) return endCart(mc, p, "they moved off the rail");
                        return true;
                    }
                    awayTicks = 0;
                    if (drawTicks >= need && safeForUs(us, hp)) {
                        mc.options.keyUse.setDown(false);
                        mc.gameMode.releaseUsingItem(p);
                        CombatLog.log("cart: flame arrow  distance " + CombatLog.f(dist) + "  draw " + drawTicks + "  pitch " + CombatLog.f(pitch)
                                + "  power up to " + CombatLog.f(worst) + "  their est. " + CombatLog.f(Blast.damage(target, cartBox.getCenter(), (float) (4 + 0.75 * Math.min(5, arrowSpeed))))
                                + "  ours worst " + CombatLog.f(us));
                        cartStage = 3;
                        drawTicks = 0;
                        cartsAtShot = carts.size();
                    }
                }
                case 10 -> { // insta cart: draw a little, aim at the spot, release – then rail + cart into the arrow's path, same tick
                    if (!instaReady(mc, p, railPos)) { // got unsafe or they left: cancel the draw (slot change) and do it the slow way
                        if (p.isUsingItem()) {
                            mc.options.keyUse.setDown(false);
                            var inv = p.getInventory();
                            inv.setSelectedSlot(inv.getSelectedSlot() == 0 ? 1 : 0);
                        }
                        cartStage = isRail(Bot.id(mc.level.getBlockState(railPos))) ? 1 : 0;
                        return true;
                    }
                    int bow = flameBow(mc), rail = hotbar(mc, st -> isRail(id(st)) ? 1 : 0), cartSlot = hotbar(mc, st -> id(st).equals("tnt_minecart") ? 1 : 0);
                    if (bow < 0 || rail < 0 && !isRail(Bot.id(mc.level.getBlockState(railPos))) || cartSlot < 0) return true; // items still moving to the hotbar
                    int draw = instaDraw(eye, c);
                    float power = bowPower(Math.max(draw, drawTicks + 1));
                    float yaw = yawTo(eye, c);
                    float pitch = bowPitch(Math.hypot(c.x - eye.x, c.z - eye.z), c.y - eye.y, 3.0 * power);
                    p.setYRot(yaw);
                    p.setXRot(pitch);
                    p.setYHeadRot(yaw);
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                    var inv = p.getInventory();
                    if (!p.isUsingItem()) {
                        inv.setSelectedSlot(bow);
                        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                        mc.options.keyUse.setDown(true);
                        drawTicks = 0;
                        mode = "insta cart · draw";
                        return true;
                    }
                    if (++drawTicks < draw) return true;
                    // release, then rail and cart(s) in the same tick: the arrow is still at the bow when the cart appears
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(p);
                    BlockPos floor = railPos.below();
                    if (!isRail(Bot.id(mc.level.getBlockState(railPos)))) {
                        inv.setSelectedSlot(rail);
                        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
                    }
                    int placed = 0, want = instaCarts(p, hp, c);
                    for (int k = 0; k < want; k++) {
                        int cs = k == 0 ? cartSlot : hotbarExcept(mc, cartSlot);
                        if (cs < 0) break;
                        inv.setSelectedSlot(cs);
                        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atBottomCenterOf(railPos).add(0, 0.1, 0), Direction.UP, railPos, false));
                        placed++;
                    }
                    Bot.swing(p);
                    inv.setSelectedSlot(bow);
                    CombatLog.log("insta cart  draw " + drawTicks + "  arrow speed " + CombatLog.f(3 * power) + "  pitch " + CombatLog.f(pitch) + "  carts " + placed
                            + "  distance " + CombatLog.f(eye.distanceTo(c)) + "  their est. " + CombatLog.f(Blast.damage(target, c, (float) (4 + 0.75 * 3 * power)))
                            + "  ours worst " + CombatLog.f(Blast.damage(p, p.position(), c, (float) (4 + 1.5 * 3 * power) + (placed > 1 ? 3f : 0f), true)));
                    mode = "insta cart";
                    instaDist = eye.distanceTo(c);
                    cartsPlaced = placed;
                    cartsAtShot = placed;
                    drawTicks = 0;
                    cartStage = 11;
                }
                case 11 -> { // insta: did the arrow get it? (it flies a few ticks) – if not, shoot the cart normally
                    var left = cartsAt(mc, railPos);
                    if (left.isEmpty() && ++drawTicks > 2) {
                        Tuning.insta(instaDist, true);
                        return endCart(mc, p, "boom");
                    }
                    if (++drawTicks > 16) {
                        Tuning.insta(instaDist, false);
                        CombatLog.log("cart: insta arrow missed, shooting the cart");
                        cartStage = 2;
                        drawTicks = 0;
                    }
                }
                default -> { // wait for the blast; missed: draw again
                    mc.options.keyDown.setDown(false);
                    var left = cartsAt(mc, railPos);
                    if (left.isEmpty()) return endCart(mc, p, "boom");
                    if (left.size() < cartsAtShot) { // one went off, the other one got thrown and primed (4 s fuse)
                        CombatLog.log("cart: boom, " + left.size() + " cart(s) left (primed by the blast) – shooting/avoiding them");
                        cartStage = 2;
                        drawTicks = 0;
                        cartsAtShot = left.size();
                        return true;
                    }
                    if (++drawTicks > 25) {
                        cartStage = 2;
                        drawTicks = 0;
                        missCount++;
                        CombatLog.log("cart: arrow missed, again (" + missCount + ")");
                    }
                }
            }
            return true;
        }

        private boolean endCart(Minecraft mc, LocalPlayer p, String why) {
            CombatLog.log("cart: " + why);
            if (p.isUsingItem() && id(p.getMainHandItem()).equals("bow")) mc.gameMode.releaseUsingItem(p);
            mc.options.keyUse.setDown(false);
            mc.options.keyDown.setDown(false);
            railPos = null;
            cartStage = 0;
            cartCd = why.equals("boom") ? 2 : why.startsWith("no line") ? 8 : 30;
            if (why.equals("boom")) mode = "cart · boom";
            return false;
        }
    }
}
