package de.peregrine.client.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps you alive while the assistant works. Runs before the job every tick; while it handles something the
 * job waits. What it knows, in order of urgency:
 * <ol>
 *   <li>lava: jump and walk to the nearest solid ground</li>
 *   <li>stuck inside a block: dig the head free</li>
 *   <li>under water and running out of air: swim up</li>
 *   <li>creepers: run, or knock it back when it is already too close to escape</li>
 *   <li>outnumbered and hurt: retreat away from the group</li>
 *   <li>fighting: best weapon, critical hits (jump, hit while falling), zig-zag towards archers</li>
 *   <li>emergency food (golden apples allowed) below 4 hearts, normal food when hungry or hurt</li>
 *   <li>no food left: hunt cows, pigs, sheep, chickens or rabbits and eat what they drop</li>
 *   <li>resting until health comes back</li>
 *   <li>always: wear the best armor, keep a totem in the off hand, light dark tunnels with torches</li>
 * </ol>
 */
final class Guard {
    private Guard() {
    }

    /** Set by the elytra mace: don't swap the elytra for a chestplate while flying. */
    static boolean keepElytra;
    private static int eatTicks, fleeTicks, critTicks, strafeTicks, gearTicks, huntTicks;
    private static boolean resting, strafeLeft;
    private static BlockPos lastTorch;
    private static LivingEntity prey;
    private static Navigator huntNav;
    private static final Set<String> BAD_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chorus_fruit", "suspicious_stew");
    private static final Set<String> ARCHERS = Set.of("skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast", "drowned");
    private static final Set<String> PREY = Set.of("cow", "pig", "sheep", "chicken", "rabbit", "mooshroom");

    static void reset() {
        keepElytra = false;
        eatTicks = fleeTicks = critTicks = strafeTicks = huntTicks = 0;
        resting = false;
        prey = null;
    }

    private static String type(LivingEntity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** Returns true when it handled this tick (the job should wait). */
    static boolean tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (++gearTicks % 10 == 0) gear(mc); // fast, so a popped totem is replaced right away
        boolean duel = Bot.current() instanceof Combat.Duel;

        // 1) lava: get out instead of giving up
        if (p.isInLava()) {
            escapeLava(mc, p);
            Bot.note("Lava! Getting out.");
            return true;
        }
        // 2) inside a block (sand/gravel fell on us): dig the head free
        if (p.isInWall()) {
            BlockPos head = BlockPos.containing(p.getEyePosition());
            if (!Bot.passable(mc, head)) {
                Bot.mine(mc, head);
                return true;
            }
        }
        // 3) drowning: swim up
        if (p.isUnderWater() && p.getAirSupply() < 140) {
            Bot.release(mc);
            p.setXRot(-60f);
            mc.options.keyJump.setDown(true);
            mc.options.keyUp.setDown(true);
            Bot.note("Coming up for air.");
            return true;
        }
        // 4) finish eating
        if (eatTicks > 0) {
            eatTicks--;
            mc.options.keyUse.setDown(eatTicks > 0);
            if (eatTicks > 0 && !p.isUsingItem() && eatTicks < 28) eatTicks = 0;
            if (eatTicks == 0) mc.options.keyUse.setDown(false);
            return true;
        }

        // the ESP picture: every monster around, seen or not (behind walls, in caves) – for routes and warnings
        List<LivingEntity> threats = de.peregrine.client.feature.EntityEsp.threats(mc, p.position(), 24);
        List<Vec3> spots = new ArrayList<>();
        for (LivingEntity e : threats) spots.add(e.position());
        Navigator.danger = spots;
        java.util.Set<Integer> watch = new java.util.HashSet<>();
        for (LivingEntity e : threats) if (e.distanceTo(p) < 16) watch.add(e.getId());
        if (prey != null && prey.isAlive()) watch.add(prey.getId());
        if (Bot.current() instanceof Combat.Duel fight && fight.target() != null) watch.add(fight.target().getId());
        de.peregrine.client.Modules.ENTITY_ESP.watch(watch);

        // look around
        List<LivingEntity> enemies = new ArrayList<>();
        LivingEntity enemy = null, creeper = null;
        double ed = Double.MAX_VALUE, cd = Double.MAX_VALUE;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(14),
                e -> e instanceof Enemy && e.isAlive() && e != p)) {
            double d = e.distanceTo(p);
            boolean seen = p.hasLineOfSight(e);
            if (e instanceof Creeper) {
                if ((seen ? d < 7 : d < 4.5) && d < cd) { // also the one behind the wall
                    cd = d;
                    creeper = e;
                }
                continue;
            }
            if (!seen || Math.abs(e.getY() - p.getY()) > 4) continue;
            if (d < 10) enemies.add(e);
            if (d < ed && d < 10) {
                ed = d;
                enemy = e;
            }
        }
        float hp = p.getHealth();

        // 5) creeper: run – unless it is so close that hitting it (knockback) is safer
        if (creeper != null && cd < 2.6 && p.getAttackStrengthScale(0f) > 0.8f) {
            Bot.holdBest(mc, Guard::weaponScore);
            Bot.look(p, creeper.getEyePosition());
            mc.gameMode.attack(p, creeper);
            Bot.swing(p);
            fleeTicks = 30;
            return true;
        }
        if (creeper != null || fleeTicks > 0) {
            if (creeper != null) fleeTicks = Math.max(fleeTicks, 40);
            fleeTicks--;
            if (creeper != null) runFrom(mc, p, creeper.position());
            safeWalk(mc, true);
            Bot.note("Creeper! Getting away.");
            return true;
        }
        // 6) outnumbered and hurt: retreat
        if (!duel && !enemies.isEmpty() && (enemies.size() >= 4 || (hp < 8 && enemies.size() >= 2) || hp < 6)) {
            Vec3 centre = Vec3.ZERO;
            for (LivingEntity e : enemies) centre = centre.add(e.position());
            centre = centre.scale(1.0 / enemies.size());
            if (hp < 8) eatNow(mc, true); // a bite while running if possible
            runFrom(mc, p, centre);
            safeWalk(mc, true);
            Bot.note("Too many monsters – falling back.");
            return true;
        }
        // 7) emergency food (golden apples allowed) – before a fight goes wrong
        if (hp < 8 && eatNow(mc, true)) return true;
        // 8) fighting (in a duel only mobs that are really close – the player is the main target)
        if (enemy != null && (!duel || ed < 3)) {
            fight(mc, p, enemy, ed);
            return true;
        }
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        if (duel) return false; // eating, resting and hunting are handled by the duel itself
        // 9) eating
        int food = p.getFoodData().getFoodLevel();
        if ((food <= 14 || (hp < 16 && food < 20)) && eatNow(mc, false)) return true;
        // 10) no food at all and getting hungry: hunt
        if (food <= 10 && !hasFood(mc)) {
            if (hunt(mc, p)) return true;
        } else {
            prey = null;
            huntTicks = 0;
        }
        // 11) resting when badly hurt (health comes back by itself with a full food bar)
        if (hp < 10 || (resting && hp < 18)) {
            if (food < 18 && !hasFood(mc)) {
                if (hunt(mc, p)) return true;
                Bot.stop("Your health is low and I couldn't find anything to eat – I stopped. Eat something, then ask me again.");
                return true;
            }
            if (!resting) Bot.say("I'm hurt – resting until I'm healthy again.");
            resting = true;
            Bot.release(mc);
            return true;
        }
        resting = false;
        // 12) light up dark tunnels (doesn't take the tick)
        torch(mc, p);
        return false;
    }

    // ------------------------------------------------------------------ fighting
    private static void fight(Minecraft mc, LocalPlayer p, LivingEntity enemy, double d) {
        mc.options.keyUse.setDown(false);
        Bot.holdBest(mc, Guard::weaponScore);
        Bot.look(p, enemy.getEyePosition().subtract(0, 0.4, 0));
        boolean archer = ARCHERS.contains(type(enemy));
        if (d <= 2.9) {
            mc.options.keyUp.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            boolean ready = p.getAttackStrengthScale(0f) >= 0.92f;
            if (ready && critTicks == 0 && p.onGround() && !p.isInWater()) {
                mc.options.keyJump.setDown(true); // jump for a critical hit
                critTicks = 10;
            } else {
                mc.options.keyJump.setDown(false);
            }
            if (critTicks > 0) critTicks--;
            boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
            if (ready && (falling || critTicks == 0 || p.isInWater())) {
                mc.gameMode.attack(p, enemy);
                Bot.swing(p);
                critTicks = 0;
            }
        } else {
            critTicks = 0;
            // zig-zag towards archers so arrows miss
            if (archer) {
                if (++strafeTicks % 12 == 0) strafeLeft = !strafeLeft;
                mc.options.keyLeft.setDown(strafeLeft);
                mc.options.keyRight.setDown(!strafeLeft);
            }
            safeWalk(mc, archer && d > 5);
        }
        Bot.note("Fighting a " + type(enemy).replace('_', ' ') + ".");
    }

    private static void runFrom(Minecraft mc, LocalPlayer p, Vec3 from) {
        Vec3 dir = p.position().subtract(from);
        if (dir.lengthSqr() < 1e-4) dir = new Vec3(1, 0, 0);
        Vec3 away = p.position().add(dir.normalize().scale(6));
        Bot.look(p, new Vec3(away.x, p.getEyeY(), away.z));
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

    private static void escapeLava(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        Direction best = null;
        for (Direction d : new Direction[]{p.getDirection().getOpposite(), Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockPos n = feet.relative(d);
            for (int up = 0; up <= 1 && best == null; up++) {
                BlockPos c = n.above(up);
                if (Bot.passable(mc, c) && !Bot.isLava(mc.level.getBlockState(c)) && Bot.solid(mc, c.below())) best = d;
            }
            if (best != null) break;
        }
        if (best == null) best = p.getDirection().getOpposite();
        Vec3 t = Vec3.atCenterOf(feet.relative(best));
        Bot.look(p, new Vec3(t.x, p.getEyeY(), t.z));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(true);
        mc.options.keySprint.setDown(true);
    }

    // ------------------------------------------------------------------ food
    static double weaponScore(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        double tier = id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3 : id.startsWith("stone") ? 2 : 1;
        if (id.endsWith("_sword")) return 10 + tier;
        if (id.endsWith("_axe")) return 5 + tier;
        return 0;
    }

    private static double foodScore(ItemStack s, boolean emergency) {
        if (!s.has(DataComponents.FOOD)) return 0;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (BAD_FOOD.contains(id)) return 0;
        if (id.contains("golden_apple")) return emergency ? 100 : 0; // saved for emergencies
        boolean raw = id.startsWith("raw_") || Set.of("beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon").contains(id);
        return (raw ? 1 : 3) + s.getCount() * 0.01;
    }

    private static boolean hasFood(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (foodScore(inv.getItem(i), true) > 0) return true;
        return false;
    }

    static boolean hasTotem(LocalPlayer p) {
        return BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).getPath().equals("totem_of_undying")
                || BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath().equals("totem_of_undying");
    }

    static boolean eatNow(Minecraft mc, boolean emergency) {
        LocalPlayer p = mc.player;
        if (p.getFoodData().getFoodLevel() >= 20 && !emergency) return false;
        if (!Bot.holdBest(mc, s -> foodScore(s, emergency))) return false;
        if (foodScore(p.getMainHandItem(), emergency) <= 0) return true; // still swapping it into the hand
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(true);
        eatTicks = 40;
        Bot.note(emergency ? "Emergency snack!" : "Eating.");
        return true;
    }

    /** Hunts the nearest farm animal and walks over its drops. Returns false when there is nothing to hunt. */
    private static boolean hunt(Minecraft mc, LocalPlayer p) {
        if (huntTicks++ > 20 * 90) return false; // give up after 90 s
        if (prey == null || !prey.isAlive()) {
            if (prey != null) { // just killed it: pick up what it dropped
                BlockPos drop = Bot.dropNear(mc, prey.blockPosition());
                prey = null;
                if (drop != null) {
                    Vec3 t = Vec3.atCenterOf(drop);
                    Bot.look(p, new Vec3(t.x, p.getEyeY(), t.z));
                    safeWalk(mc, false);
                    return true;
                }
            }
            double best = Double.MAX_VALUE;
            for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(48),
                    e -> e.isAlive() && !e.isBaby() && PREY.contains(type(e)))) {
                double d = e.distanceTo(p) * (p.hasLineOfSight(e) ? 1 : 1.4); // visible ones are a bit cheaper
                if (d < best) {
                    best = d;
                    prey = e;
                }
            }
            huntNav = null;
            if (prey == null) {
                BlockPos drop = Bot.dropNear(mc, p.blockPosition());
                if (drop != null && !drop.equals(p.blockPosition())) { // food lying around from the last hunt
                    Vec3 t = Vec3.atCenterOf(drop);
                    Bot.look(p, new Vec3(t.x, p.getEyeY(), t.z));
                    safeWalk(mc, false);
                    return true;
                }
                return false;
            }
            Bot.say("I'm out of food – hunting a " + type(prey).replace('_', ' ') + ".");
        }
        double d = prey.distanceTo(p);
        if (d > 4 && !p.hasLineOfSight(prey)) { // seen through the ESP only: walk there by route
            if (huntNav == null || p.tickCount % 60 == 0) huntNav = new Navigator(prey.blockPosition(), 2.5, null);
            if (huntNav.tick(mc)) huntNav = null;
            return true;
        }
        huntNav = null;
        Bot.holdBest(mc, Guard::weaponScore);
        Bot.look(p, prey.getEyePosition().subtract(0, 0.3, 0));
        if (d <= 2.8) {
            mc.options.keyUp.setDown(false);
            if (p.getAttackStrengthScale(0f) >= 0.9f) {
                mc.gameMode.attack(p, prey);
                Bot.swing(p);
            }
        } else safeWalk(mc, d > 6);
        return true;
    }

    // ------------------------------------------------------------------ gear & torches
    private static int armorTier(String id) {
        if (!(id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots"))) return -1;
        if (id.startsWith("netherite")) return 6;
        if (id.startsWith("diamond")) return 5;
        if (id.startsWith("iron") || id.startsWith("turtle")) return 4;
        if (id.startsWith("chainmail")) return 3;
        if (id.startsWith("golden")) return 2;
        return 1; // leather
    }

    /** Puts on better armor and keeps a totem in the off hand – with normal inventory clicks, one move at a time. */
    static void gear(Minecraft mc) {
        var p = mc.player;
        var inv = p.getInventory();
        boolean totemInHand = BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).getPath().equals("totem_of_undying");
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
            if (id.equals("totem_of_undying")) {
                if (!totemInHand) {
                    Inv.toOffhand(mc, i);
                    return;
                }
                continue;
            }
            int tier = armorTier(id);
            if (tier < 0) continue;
            var eq = it.get(DataComponents.EQUIPPABLE);
            if (eq == null) continue;
            EquipmentSlot slot = eq.slot();
            if (slot == EquipmentSlot.CHEST && keepElytra) continue; // flying: the elytra stays on
            ItemStack worn = p.getItemBySlot(slot);
            if (worn.isEmpty() || armorTier(BuiltInRegistries.ITEM.getKey(worn.getItem()).getPath()) < tier) {
                Inv.equip(mc, i, slot);
                return;
            }
        }
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
