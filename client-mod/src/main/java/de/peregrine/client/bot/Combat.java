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
        private int restoreSlot = -1, catchIn = -1, lungeCd, pearlCd;
        private int flyStage, flyTicks, rocketCd, flyCd;
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
            if (restoreSlot >= 0) { // back to the weapon after a lunge
                p.getInventory().setSelectedSlot(restoreSlot);
                restoreSlot = -1;
            }

            // ---- defence first
            Guard.gear(mc); // totem back in the off hand immediately after a pop, best armor on
            float hp = p.getHealth() + p.getAbsorptionAmount();
            if ((hp < 9 || (hp < 13 && d > 5)) && Guard.eatNow(mc, true)) {
                mode = "healing";
                return false;
            }

            // ---- special moves (pearls, wind charges, lunge)
            if (techniques(mc, p, d)) return false;

            // ---- from a distance: elytra mace (flies there itself)
            if (elytraMace(mc, p, d)) return false;

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
                if (quickUse(mc, p, "wind_charge", yawTo(p.position(), target.position()), 90f)) {
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
            if (maceStage == 0 && p.fallDistance > 6 && p.getDeltaMovement().y < -0.6 && has(mc, "wind_charge")) {
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
            if (d > 14 && d < 60 && pearlCd == 0 && has(mc, "ender_pearl") && p.hasLineOfSight(target)) {
                Vec3 t = target.position();
                double hd = Math.hypot(t.x - eye.x, t.z - eye.z) - 2.0;
                float pitch = pearlPitch(hd, t.y - eye.y);
                if (quickUse(mc, p, "ender_pearl", yawTo(eye, t), pitch)) {
                    pearlCd = 30;
                    mode = "engage pearl";
                    if (has(mc, "wind_charge") && p.onGround()) catchIn = 2;
                    return true;
                }
            }
            // lunge swap: spear with Lunge, jab to dash in, back to the weapon
            if (lungeCd == 0 && d > 3.6 && d < 8.5 && p.onGround()) {
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
        private void attackWithSwap(Minecraft mc, LocalPlayer p, LivingEntity t) {
            var inv = p.getInventory();
            int hand = inv.getSelectedSlot();
            int swap = -1;
            if (t.isBlocking()) swap = hotbar(mc, s -> id(s).endsWith("_axe") ? 1 : 0);
            if (swap < 0 && p.fallDistance > 1.5) swap = hotbar(mc, s -> id(s).equals("mace") ? 1 : 0);
            if (swap < 0 && t.getArmorValue() >= 12) swap = hotbar(mc, s -> id(s).equals("mace") && enchanted(s, "breach") ? 1 : 0);
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
                attackWithSwap(mc, p, target);
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
                    smash(mc, p);
                    maceStage = 0;
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
         * Elytra mace: elytra on, take off, rockets up until high above the target, then a steep dive and the mace
         * smash on the way down (stun slam against a shield). Afterwards the chestplate goes back on.
         */
        private boolean elytraMace(Minecraft mc, LocalPlayer p, double d) {
            if (flyCd > 0) flyCd--;
            boolean can = has(mc, "mace") && has(mc, "firework_rocket") && (wearingElytra(p) || has(mc, "elytra"));
            if (flyStage == 0) {
                if (!can || flyCd > 0 || d < 7 || d > 48 || p.getHealth() < 8) return false;
                flyStage = 1;
                flyTicks = 0;
            }
            flyTicks++;
            Guard.keepElytra = true;
            mode = "elytra mace";
            if (flyTicks > 20 * 14 || !target.isAlive()) return endFlight(mc);
            Vec3 t = target.position();
            switch (flyStage) {
                case 1 -> { // put the elytra on, jump
                    if (!wearingElytra(p)) {
                        var inv = p.getInventory();
                        for (int i = 0; i < 36; i++)
                            if (id(inv.getItem(i)).equals("elytra")) {
                                Inv.equip(mc, i, net.minecraft.world.entity.EquipmentSlot.CHEST);
                                break;
                            }
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
                case 3 -> { // climb with rockets until well above them
                    mc.options.keyJump.setDown(false);
                    float yaw = (float) Math.toDegrees(Math.atan2(-(t.x - p.getX()), t.z - p.getZ()));
                    p.setYRot(yaw);
                    p.setXRot(-55f);
                    if (--rocketCd <= 0 && quickUse(mc, p, "firework_rocket", yaw, -55f)) rocketCd = 22;
                    if (p.getY() > t.y + 20) flyStage = 4;
                    if (!p.isFallFlying() && p.onGround()) return endFlight(mc);
                }
                case 4 -> { // dive steeply onto them and smash
                    Vec3 aim = target.getEyePosition();
                    double hd = Math.hypot(aim.x - p.getX(), aim.z - p.getZ());
                    float yaw = (float) Math.toDegrees(Math.atan2(-(aim.x - p.getX()), aim.z - p.getZ()));
                    float pitch = (float) Math.toDegrees(Math.atan2(p.getEyeY() - aim.y, Math.max(0.1, hd)));
                    p.setYRot(yaw);
                    p.setXRot(Math.max(35f, Math.min(88f, pitch))); // steep: fast fall keeps the fall distance growing
                    hotbar(mc, s -> id(s).equals("mace") ? 1 : 0);
                    if (p.distanceTo(target) <= 3.4 && p.getDeltaMovement().y < -0.4) {
                        smash(mc, p);
                        flyStage = 5;
                    }
                    if (p.onGround()) return endFlight(mc);
                }
                default -> { // landing
                    if (p.onGround() || !p.isFallFlying()) return endFlight(mc);
                }
            }
            return true;
        }

        private boolean endFlight(Minecraft mc) {
            flyStage = 0;
            flyCd = 60;
            Guard.keepElytra = false; // Guard puts the best chestplate back on
            mc.options.keyJump.setDown(false);
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
