package de.peregrine.client.bot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A sparring partner for training, only in a world running on this computer: a player-sized mob (husk – it doesn't
 * burn) in real gear whose vanilla AI is switched off and that moves like a PvP player instead: strafes in an uneven
 * rhythm, keeps at the edge of reach, swings on a sword cooldown, jumps, backs off to "eat" (absorption +
 * regeneration, like a golden apple), puts a new totem in the off hand after a pop, and gets away from TNT carts,
 * end crystals and charged anchors near it. It moves, so every technique gets trained against a moving target.
 */
public final class Sparring {
    private Sparring() {
    }

    public static final String TAG = "peregrine_sparring";

    /** Last time (ms) the assistant was in a fight – the partner only fights back while a duel runs. */
    static volatile long duelSeen;

    private static final class State {
        int swingCd, strafeT, eatCd, eating, totems = 4, gapples = 8, jumpCd;
        boolean left;
    }

    private static final Map<java.util.UUID, State> STATES = new HashMap<>();
    private static final Random RND = new Random();

    /** Spawn command lines (run by the server itself). Variant: "" = protection armor, "blast" = blast protection, "shield" = with a shield. */
    static String[] spawnCommands(String player, double x, double y, double z, String variant) {
        String prot = variant.contains("blast") ? "blast_protection" : "protection";
        String ench = "enchantments={\"minecraft:" + prot + "\":4,\"minecraft:unbreaking\":3}";
        String sel = "@e[tag=" + TAG + ",limit=1,sort=nearest]";
        String pos = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
        return new String[]{
                "kill @e[tag=" + TAG + "]",
                "summon husk " + pos + " {Tags:[\"" + TAG + "\"],CustomName:\"Sparring\",CustomNameVisible:1b,PersistenceRequired:1b,CanPickUpLoot:0b}",
                "execute positioned " + pos + " run attribute " + sel + " minecraft:max_health base set 20",
                "execute positioned " + pos + " run attribute " + sel + " minecraft:movement_speed base set 0.32",
                "execute positioned " + pos + " run attribute " + sel + " minecraft:knockback_resistance base set 0",
                "execute positioned " + pos + " run attribute " + sel + " minecraft:attack_damage base set 1",
                "execute positioned " + pos + " run attribute " + sel + " minecraft:follow_range base set 0",
                "execute positioned " + pos + " run item replace entity " + sel + " armor.head with netherite_helmet[" + ench + "]",
                "execute positioned " + pos + " run item replace entity " + sel + " armor.chest with netherite_chestplate[" + ench + "]",
                "execute positioned " + pos + " run item replace entity " + sel + " armor.legs with netherite_leggings[" + ench + "]",
                "execute positioned " + pos + " run item replace entity " + sel + " armor.feet with netherite_boots[" + ench + "]",
                "execute positioned " + pos + " run item replace entity " + sel + " weapon.mainhand with netherite_sword[enchantments={\"minecraft:sharpness\":5}]",
                "execute positioned " + pos + " run item replace entity " + sel + " weapon.offhand with " + (variant.contains("shield") ? "shield" : "totem_of_undying"),
                "effect give " + sel + " minecraft:instant_damage 1 0 true", // husk is undead: instant damage heals it to full
        };
    }

    public static void init() {
        try {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Sparring::tick);
        } catch (Throwable t) {
            de.peregrine.client.PeregrineClient.LOG.warn("Sparring partner unavailable: {}", t.toString());
        }
    }

    private static void tick(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
            ServerLevel level = pl.level();
            for (Mob m : level.getEntitiesOfClass(Mob.class, pl.getBoundingBox().inflate(64), e -> e.isAlive() && e.entityTags().contains(TAG)))
                control(level, m, pl);
        }
        if (server.getTickCount() % 200 == 0 && STATES.size() > 32) STATES.clear();
    }

    private static boolean isType(Entity e, String id) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(id);
    }

    private static void control(ServerLevel level, Mob m, ServerPlayer target) {
        State s = STATES.computeIfAbsent(m.getUUID(), k -> {
            for (String f : new String[]{"goalSelector", "targetSelector"}) { // its own AI off – we drive it
                try {
                    var field = Mob.class.getDeclaredField(f);
                    field.setAccessible(true);
                    ((net.minecraft.world.entity.ai.goal.GoalSelector) field.get(m)).removeAllGoals(g -> true);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return new State();
        });
        if (target.isSpectator() || target.isCreative()) return;
        if (System.currentTimeMillis() - duelSeen > 3000) { // no duel running: stand still and wait (trained: a partner
            m.getMoveControl().strafe(0f, 0f);             // left in the world killed us while the world was loading)
            return;
        }
        if (s.swingCd > 0) s.swingCd--;
        if (s.eatCd > 0) s.eatCd--;
        if (s.jumpCd > 0) s.jumpCd--;
        m.setTarget(target);
        Vec3 to = target.position().subtract(m.position());
        double d = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.yBodyRot = yaw;
        m.getLookControl().setLookAt(target, 30f, 30f);

        // totem back in the off hand after a pop
        if (m.getOffhandItem().isEmpty() && s.totems > 0 && level.getGameTime() % 10 == 0) {
            m.setItemInHand(InteractionHand.OFF_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
            s.totems--;
        }
        // danger: TNT carts, crystals near it – get away
        Vec3 danger = null;
        for (Entity e : level.getEntities(m, m.getBoundingBox().inflate(4.5), e -> isType(e, "tnt_minecart") || isType(e, "end_crystal")))
            danger = danger == null ? e.position() : danger.add(e.position()).scale(0.5);
        if (danger != null && RND.nextFloat() < 0.85f) { // it reacts most of the time, like a decent player
            Vec3 away = m.position().subtract(danger).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-3) away = new Vec3(1, 0, 0);
            Vec3 goal = m.position().add(away.normalize().scale(5));
            m.getMoveControl().setWantedPosition(goal.x, m.getY(), goal.z, 1.4);
            if (m.onGround() && s.jumpCd == 0) {
                m.getJumpControl().jump();
                s.jumpCd = 10;
            }
            return;
        }
        // "golden apple": back off for 1.6 s, then absorption + regeneration
        if (s.eating > 0) {
            s.eating--;
            m.getMoveControl().strafe(-0.6f, s.left ? 0.5f : -0.5f);
            if (s.eating == 0) {
                m.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0));
                m.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            }
            return;
        }
        if (m.getHealth() < 9 && s.gapples > 0 && s.eatCd == 0) {
            s.gapples--;
            s.eating = 32;
            s.eatCd = 120;
            return;
        }
        // strafe rhythm, keep at the edge of reach, step in when the swing is ready
        if (--s.strafeT <= 0) {
            s.left = !s.left;
            s.strafeT = 6 + RND.nextInt(14);
        }
        boolean ready = s.swingCd == 0;
        float fwd = d > 3.2 ? 1f : ready ? (d > 2.4 ? 0.8f : 0f) : (d < 2.6 ? -0.6f : 0f);
        m.getMoveControl().strafe(fwd, s.left ? 0.7f : -0.7f);
        if (d > 6) m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.3);
        if (m.onGround() && s.jumpCd == 0 && (m.horizontalCollision || d < 3.5 && RND.nextFloat() < 0.04f)) {
            m.getJumpControl().jump();
            s.jumpCd = 12;
        }
        // swing like a sword player: every 12 ticks when in reach
        double reach = m.getEyePosition().distanceTo(nearest(target.getBoundingBox(), m.getEyePosition()));
        if (ready && reach <= 3.0 && m.hasLineOfSight(target)) {
            swing(m);
            m.doHurtTarget(level, target);
            s.swingCd = 12;
        }
    }

    /** Arm swing – the signature differs between 26.1/26.2 and 26.3, so found by name. */
    private static void swing(Mob m) {
        for (var meth : net.minecraft.world.entity.LivingEntity.class.getMethods()) {
            if (!meth.getName().equals("swing") || meth.getParameterCount() == 0 || meth.getParameterTypes()[0] != InteractionHand.class) continue;
            Object[] args = new Object[meth.getParameterCount()];
            args[0] = InteractionHand.MAIN_HAND;
            for (int i = 1; i < args.length; i++) args[i] = meth.getParameterTypes()[i] == boolean.class ? Boolean.TRUE : null;
            try {
                meth.invoke(m, args);
                return;
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            }
        }
    }

    private static Vec3 nearest(net.minecraft.world.phys.AABB b, Vec3 p) {
        return new Vec3(Math.max(b.minX, Math.min(b.maxX, p.x)), Math.max(b.minY, Math.min(b.maxY, p.y)), Math.max(b.minZ, Math.min(b.maxZ, p.z)));
    }
}
