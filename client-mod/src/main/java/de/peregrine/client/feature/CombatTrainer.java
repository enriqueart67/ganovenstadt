package de.peregrine.client.feature;

import de.peregrine.client.Clicks;
import de.peregrine.client.bot.Bot;
import de.peregrine.client.gui.Px;
import de.peregrine.client.gui.Theme;
import de.peregrine.client.gui.Type;
import de.peregrine.client.gui.Ui;
import de.peregrine.client.module.Category;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.ColorSetting;
import de.peregrine.client.setting.ModeSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat Trainer: spawns training dummies in your own singleplayer world and measures how you fight them –
 * hits, accuracy, CPS, reach, combo, damage, crits and DPS – with a reach ring and floating damage numbers.
 *
 * <p>The dummies are ordinary mobs with their goals removed and steered by this module on the integrated server.
 * They are tagged {@code peregrine_dummy}, never drop anything and are removed when the module is switched off.
 * Nothing here attacks for you; you do the fighting.</p>
 */
public final class CombatTrainer extends Module {
    public static final String TAG = "peregrine_dummy";

    public final ModeSetting dummy = add(new ModeSetting("dummy", "Dummy", "Husk", "Husk", "Zombie", "Skeleton", "Vindicator", "Iron golem"));
    public final ModeSetting behaviour = add(new ModeSetting("behaviour", "Behaviour", "Strafe", "Stand still", "Strafe", "Chase", "Chase and hit back"));
    public final NumberSetting count = add(new NumberSetting("count", "Dummies", 1, 1, 4, 1, ""));
    public final NumberSetting health = add(new NumberSetting("health", "Dummy health", 100, 20, 500, 10, " HP"));
    public final NumberSetting speed = add(new NumberSetting("speed", "Dummy speed", 1.0, 0.4, 2.0, 0.1, "×"));
    public final NumberSetting hitBack = add(new NumberSetting("hitback", "Hit back damage", 2, 1, 10, 1, " HP"));
    public final BoolSetting knockback = add(new BoolSetting("knockback", "Dummies take knockback", true));
    public final NumberSetting resetAfter = add(new NumberSetting("reset", "Heal after", 3, 1, 10, 1, " s"));
    public final BoolSetting ring = add(new BoolSetting("ring", "Show reach ring", true));
    public final ColorSetting ringColor = add(new ColorSetting("ringcolor", "Reach ring color", 0xFF8DB4D4));
    public final BoolSetting numbers = add(new BoolSetting("numbers", "Damage numbers", true));
    public final BoolSetting panel = add(new BoolSetting("panel", "Stats panel", true));

    /** Entity ids of the dummies (same on the integrated server and the client). Written by the server thread. */
    private final Set<Integer> dummies = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Vec3> home = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lastHurt = new ConcurrentHashMap<>(), cooldown = new ConcurrentHashMap<>();
    private volatile long spawningSince; // 0 = idle; a spawn that never ran is retried after 3 s
    private volatile int hitsTaken;
    private volatile String serverNote;
    private ClientLevel levelSeen;
    private boolean warned;
    private int serverTicks;
    private Theme theme = Theme.fallback();

    // stats (client thread)
    private int hits, swings, combo, bestCombo, crits;
    private double reachSum, bestReach, damage, biggest;
    private long started;
    private final ArrayDeque<double[]> recent = new ArrayDeque<>(); // [time, damage]
    private final Map<Integer, Float> lastHp = new HashMap<>();
    private final List<Popup> popups = new ArrayList<>();

    private record Popup(double x, double y, double z, float amount, boolean crit, long at) {
    }

    public CombatTrainer() {
        super("trainer", "Combat Trainer", "Training dummies with hit, reach and combo stats – singleplayer (N)", Category.PVP, false);
    }

    public boolean isDummy(Entity e) {
        return e != null && dummies.contains(e.getId());
    }

    @Override
    protected void onToggle() {
        Minecraft mc = Minecraft.getInstance();
        warned = false;
        spawningSince = 0;
        if (enabled) {
            theme = Theme.load();
            resetStats();
        } else {
            removeAll(mc);
        }
    }

    private void resetStats() {
        hits = swings = combo = bestCombo = crits = 0;
        reachSum = bestReach = damage = biggest = 0;
        hitsTaken = 0;
        started = System.currentTimeMillis();
        recent.clear();
        lastHp.clear();
        popups.clear();
    }

    // ------------------------------------------------------------------ input hooks (client thread)

    /** A left click in the world while dummies are close counts as a swing (for accuracy). */
    public void onSwing() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || dummies.isEmpty() || mc.player == null || Ui.current() != null) return;
        if (nearestDummy(mc, 8) == null) return;
        swings++;
        // a swing that does not hit breaks the combo; onHit runs right after for a hit and puts it back
        if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && isDummy(eh.getEntity()))) combo = 0;
    }

    public void onHit(LocalPlayer p, Entity target) {
        if (!enabled || !isDummy(target)) return;
        hits++;
        combo++;
        bestCombo = Math.max(bestCombo, combo);
        double reach = p.getEyePosition().distanceTo(nearestPoint(target.getBoundingBox(), p.getEyePosition()));
        reachSum += reach;
        bestReach = Math.max(bestReach, reach);
        boolean crit = p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isPassenger() && p.getAttackStrengthScale(0.5f) > 0.9f;
        if (crit) crits++;
        lastCrit = crit;
    }

    private boolean lastCrit;

    private static Vec3 nearestPoint(AABB b, Vec3 v) {
        return new Vec3(Math.max(b.minX, Math.min(b.maxX, v.x)), Math.max(b.minY, Math.min(b.maxY, v.y)), Math.max(b.minZ, Math.min(b.maxZ, v.z)));
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick(Minecraft mc) {
        if (mc.level != levelSeen) { // new world: old ids mean nothing
            levelSeen = mc.level;
            dummies.clear();
            home.clear();
            lastHp.clear();
            spawningSince = 0;
        }
        if (!enabled || mc.player == null || mc.level == null) return;
        if (!mc.hasSingleplayerServer()) {
            if (!warned) {
                mc.player.sendOverlayMessage(Component.literal("§6[Peregrine]§r Combat Trainer needs your own singleplayer world – it spawns its dummies there."));
                warned = true;
            }
            return;
        }
        if (serverNote != null) {
            mc.player.sendOverlayMessage(Component.literal("§6[Peregrine]§r " + serverNote));
            serverNote = null;
        }
        if (started == 0) started = System.currentTimeMillis();
        if (mc.player.tickCount < 40) return; // let the world and the player settle first
        if (dummies.size() < count.i() && (spawningSince == 0 || System.currentTimeMillis() - spawningSince > 3000)) spawn(mc);
        if (dummies.size() > count.i()) trim(mc);
        trackDamage(mc);
        serverTicks++;
        Bot.onServer(mc, this::serverTick);
    }

    /** Health drops seen on the client become damage numbers and DPS. */
    private void trackDamage(Minecraft mc) {
        long now = System.currentTimeMillis();
        for (int id : dummies) {
            Entity e = mc.level.getEntity(id);
            if (!(e instanceof LivingEntity le)) continue;
            float hp = le.getHealth();
            Float before = lastHp.put(id, hp);
            if (before != null && hp < before - 0.01f) {
                float d = before - hp;
                damage += d;
                biggest = Math.max(biggest, d);
                recent.addLast(new double[]{now, d});
                if (numbers.on()) popups.add(new Popup(e.getX() + (Math.random() - 0.5) * 0.4, e.getBoundingBox().maxY + 0.2, e.getZ() + (Math.random() - 0.5) * 0.4, d, lastCrit, now));
            }
        }
        while (!recent.isEmpty() && recent.peekFirst()[0] < now - 5000) recent.pollFirst();
        popups.removeIf(p -> now - p.at > 1100);
    }

    private double dps() {
        double s = 0;
        for (double[] r : recent) s += r[1];
        return s / 5.0;
    }

    // ------------------------------------------------------------------ server side (integrated server thread)

    private EntityType<?> type(ServerLevel level) {
        String id = switch (dummy.get()) {
            case "Zombie" -> "zombie";
            case "Skeleton" -> "skeleton";
            case "Vindicator" -> "vindicator";
            case "Iron golem" -> "iron_golem";
            default -> "husk";
        };
        if (level.getDifficulty() == Difficulty.PEACEFUL && !id.equals("iron_golem")) {
            serverNote = "Peaceful removes hostile mobs – using an iron golem as dummy.";
            id = "iron_golem";
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
    }

    private void spawn(Minecraft mc) {
        spawningSince = System.currentTimeMillis();
        int want = count.i();
        Bot.onServer(mc, sp -> {
            try {
                ServerLevel level = sp.level();
                if (dummies.isEmpty()) clearTagged(level, sp.position(), 96);
                EntityType<?> type = type(level);
                for (int i = dummies.size(); i < want; i++) {
                    Entity e = type.create(level, EntitySpawnReason.COMMAND);
                    if (!(e instanceof Mob m)) continue;
                    place(m, sp, i, want);
                    prepare(m);
                    if (level.addFreshEntity(m)) {
                        dummies.add(m.getId());
                        home.put(m.getId(), m.position());
                        lastHurt.put(m.getId(), m.tickCount);
                    }
                }
                de.peregrine.client.PeregrineClient.LOG.info("Combat Trainer: {} dummies ({})", dummies.size(), type.getDescriptionId());
            } finally {
                spawningSince = 0;
            }
        });
    }

    /** In front of the player, side by side when there are several. */
    private static void place(Mob m, ServerPlayer sp, int i, int of) {
        ServerLevel level = sp.level();
        Vec3 look = sp.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1e-4) look = new Vec3(0, 0, 1);
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0, look.x);
        Vec3 at = sp.position().add(look.scale(4.0)).add(side.scale((i - (of - 1) / 2.0) * 2.2));
        m.snapTo(at.x, groundY(level, at), at.z, sp.getYRot() + 180f, 0f);
        m.setDeltaMovement(Vec3.ZERO);
    }

    /** Standing spot closest to the player's height: solid floor, free feet and head. */
    private static double groundY(ServerLevel level, Vec3 at) {
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(at);
        for (int i = 0; i <= 8; i++) {
            int dy = (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2); // 0, -1, +1, -2, +2 ...
            net.minecraft.core.BlockPos p = base.above(dy);
            if (!free(level, p) || !free(level, p.above())) continue;
            if (!level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) return p.getY();
        }
        return at.y;
    }

    private static boolean free(ServerLevel level, net.minecraft.core.BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private void prepare(Mob m) {
        m.addTag(TAG);
        m.setCustomName(Component.literal("Training dummy"));
        m.setCustomNameVisible(true);
        m.setPersistenceRequired();
        m.setSilent(true);
        m.setCanPickUpLoot(false);
        for (String field : new String[]{"goalSelector", "targetSelector"}) {
            try {
                java.lang.reflect.Field f = Mob.class.getDeclaredField(field);
                f.setAccessible(true);
                ((GoalSelector) f.get(m)).removeAllGoals(g -> true);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        AttributeInstance max = m.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) max.setBaseValue(health.d());
        AttributeInstance kb = m.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(knockback.on() ? 0.0 : 1.0);
        m.setHealth(m.getMaxHealth());
    }

    private void serverTick(ServerPlayer sp) {
        ServerLevel level = sp.level();
        Iterator<Integer> it = dummies.iterator();
        int index = 0;
        while (it.hasNext()) {
            int id = it.next();
            Entity e = level.getEntity(id);
            if (!(e instanceof Mob m) || !m.isAlive()) { // died or unloaded: replaced on the next client tick
                if (e != null && !e.isRemoved()) e.discard();
                it.remove();
                home.remove(id);
                continue;
            }
            m.clearFire();
            m.setTarget(null);
            // health: refill after a pause, and before a hit could kill it
            if (m.hurtTime > 0) lastHurt.put(id, m.tickCount);
            boolean idle = m.tickCount - lastHurt.getOrDefault(id, 0) > resetAfter.d() * 20;
            if (idle || m.getHealth() < m.getMaxHealth() * 0.25f) m.setHealth(m.getMaxHealth());
            AttributeInstance max = m.getAttribute(Attributes.MAX_HEALTH);
            if (max != null && max.getBaseValue() != health.d()) max.setBaseValue(health.d());
            AttributeInstance kb = m.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(knockback.on() ? 0.0 : 1.0);
            if (m.distanceToSqr(sp) > 24 * 24 || m.level() != sp.level()) { // left behind: bring it back
                place(m, sp, index, dummies.size());
                home.put(id, m.position());
            }
            steer(m, sp, index++);
        }
    }

    private void steer(Mob m, ServerPlayer sp, int index) {
        m.getLookControl().setLookAt(sp, 40f, 40f);
        Vec3 base = home.getOrDefault(m.getId(), m.position());
        double sp0 = speed.d();
        double dist = m.distanceTo(sp);
        switch (behaviour.get()) {
            case "Stand still" -> {
                if (m.position().distanceToSqr(base) > 0.36) m.getMoveControl().setWantedPosition(base.x, base.y, base.z, sp0);
            }
            case "Strafe" -> {
                Vec3 to = sp.position().subtract(base).multiply(1, 0, 1);
                if (to.lengthSqr() < 1e-4) to = new Vec3(1, 0, 0);
                Vec3 side = new Vec3(-to.z, 0, to.x).normalize();
                double t = (serverTicks + index * 17) / 20.0 * sp0;
                double s = Math.sin(t * 1.7) * 2.2 + Math.sin(t * 4.1) * 0.6; // irregular, so it is not trivial to track
                Vec3 w = base.add(side.scale(s));
                m.getMoveControl().setWantedPosition(w.x, base.y, w.z, sp0);
            }
            default -> { // Chase, Chase and hit back
                if (dist > 3.2) {
                    m.getNavigation().moveTo(sp, sp0);
                } else {
                    m.getNavigation().stop();
                    double a = (serverTicks + index * 30) / 20.0 * 1.3 * sp0;
                    Vec3 w = sp.position().add(Math.cos(a) * 2.4, 0, Math.sin(a) * 2.4);
                    m.getMoveControl().setWantedPosition(w.x, sp.getY(), w.z, sp0);
                }
                if (behaviour.is("Chase and hit back")) {
                    int cd = cooldown.getOrDefault(m.getId(), 0);
                    if (cd > 0) cooldown.put(m.getId(), cd - 1);
                    else if (dist < 2.6 && m.hasLineOfSight(sp) && !sp.isCreative() && !sp.isSpectator()) {
                        swingArm(m);
                        if (sp.hurtServer(sp.level(), m.damageSources().mobAttack(m), (float) hitBack.d())) hitsTaken++;
                        cooldown.put(m.getId(), 20);
                    }
                }
            }
        }
    }

    private static java.lang.reflect.Method swingM;
    private static boolean swingLooked;

    /** LivingEntity.swing(InteractionHand) – the signature changed in 26.3, so it is looked up once. */
    private static void swingArm(Mob m) {
        try {
            if (!swingLooked) {
                swingLooked = true;
                for (var x : LivingEntity.class.getMethods())
                    if (x.getName().equals("swing") && x.getParameterCount() >= 1 && x.getParameterTypes()[0] == InteractionHand.class) {
                        if (swingM == null || x.getParameterCount() < swingM.getParameterCount()) swingM = x;
                    }
            }
            if (swingM == null) return;
            Object[] args = new Object[swingM.getParameterCount()];
            args[0] = InteractionHand.MAIN_HAND;
            for (int i = 1; i < args.length; i++) args[i] = swingM.getParameterTypes()[i] == boolean.class ? Boolean.TRUE : null;
            swingM.invoke(m, args);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
        }
    }

    private static void clearTagged(ServerLevel level, Vec3 around, double r) {
        for (Entity e : level.getEntitiesOfClass(Mob.class, new AABB(around, around).inflate(r), x -> x.entityTags().contains(TAG))) e.discard();
    }

    private void trim(Minecraft mc) {
        List<Integer> extra = new ArrayList<>(dummies).subList(count.i(), dummies.size());
        List<Integer> ids = List.copyOf(extra);
        ids.forEach(dummies::remove);
        Bot.onServer(mc, sp -> ids.forEach(id -> {
            Entity e = sp.level().getEntity(id);
            if (e != null) e.discard();
        }));
    }

    private void removeAll(Minecraft mc) {
        dummies.clear();
        home.clear();
        if (mc.hasSingleplayerServer()) Bot.onServer(mc, sp -> clearTagged(sp.level(), sp.position(), 128));
    }

    private Entity nearestDummy(Minecraft mc, double r) {
        Entity best = null;
        double bd = r * r;
        for (int id : dummies) {
            Entity e = mc.level.getEntity(id);
            if (e == null) continue;
            double d = e.distanceToSqr(mc.player);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ drawing (HUD layer)

    public void render(GuiGraphicsExtractor g, Minecraft mc, float pt) {
        if (!enabled || mc.player == null || mc.level == null || !mc.hasSingleplayerServer()) return;
        LocalPlayer p = mc.player;
        Projector pr = Projector.of(mc, g);
        if (pr != null) {
            if (ring.on() && mc.options.getCameraType().isFirstPerson()) drawRing(g, pr, mc, p, pt);
            drawDummyBars(g, pr, mc, pt);
            if (numbers.on()) drawPopups(g, pr);
        }
        if (panel.on()) drawPanel(g, mc);
    }

    private void drawRing(GuiGraphicsExtractor g, Projector pr, Minecraft mc, LocalPlayer p, float pt) {
        double reach = p.entityInteractionRange();
        boolean inReach = false;
        Vec3 eye = p.getEyePosition(pt);
        for (int id : dummies) {
            Entity e = mc.level.getEntity(id);
            if (e != null && e.getBoundingBox().distanceToSqr(eye) <= reach * reach) inReach = true;
        }
        int col = inReach ? 0xFF5AD08C : 0xFF000000 | (ringColor.argb() & 0xFFFFFF);
        Vec3 feet = p.getPosition(pt);
        double y = feet.y + 0.02;
        int n = 72;
        double[] prev = null;
        for (int i = 0; i <= n; i++) {
            double a = i * Math.PI * 2 / n;
            double[] q = pr.toCam(feet.x + Math.cos(a) * reach, y, feet.z + Math.sin(a) * reach);
            if (prev != null) pr.line(g, prev, q, 1.5f, (i % 2 == 0 ? 0xD0000000 : 0x70000000) | (col & 0xFFFFFF));
            prev = q;
        }
    }

    private void drawDummyBars(GuiGraphicsExtractor g, Projector pr, Minecraft mc, float pt) {
        for (int id : dummies) {
            Entity e = mc.level.getEntity(id);
            if (!(e instanceof LivingEntity le)) continue;
            Vec3 pos = e.getPosition(pt);
            float[] s = pr.project(pos.x, e.getBoundingBox().maxY + 0.75, pos.z);
            if (s == null) continue;
            float f = Math.max(0, Math.min(1, le.getHealth() / le.getMaxHealth()));
            float w = 46;
            Px.roundRect(g, s[0] - w / 2 - 1, s[1] - 1, w + 2, 4, 2, 0xB0101216);
            Px.roundRect(g, s[0] - w / 2, s[1], w * f, 2, 1, 0xFF000000 | (theme.accent & 0xFFFFFF));
        }
    }

    private void drawPopups(GuiGraphicsExtractor g, Projector pr) {
        long now = System.currentTimeMillis();
        for (Popup p : popups) {
            float t = (now - p.at) / 1100f;
            float[] s = pr.project(p.x, p.y + t * 0.9, p.z);
            if (s == null) continue;
            int alpha = (int) (255 * Math.min(1f, (1f - t) * 2.2f));
            if (alpha < 8) continue;
            String txt = (p.amount >= 10 ? String.valueOf(Math.round(p.amount)) : String.format(java.util.Locale.ROOT, "%.1f", p.amount)) + (p.crit ? "!" : "");
            int rgb = p.crit ? 0xF2C14E : 0xF4F4F4;
            float sc = p.crit ? 1.05f : 0.85f;
            Type.center(g, txt, Type.BOLD, s[0] + 0.6f, s[1] + 0.6f, sc, (alpha / 2 << 24));
            Type.center(g, txt, Type.BOLD, s[0], s[1], sc, (alpha << 24) | rgb);
        }
    }

    private void drawPanel(GuiGraphicsExtractor g, Minecraft mc) {
        int sw = g.guiWidth();
        float w = 118, x = sw - w - 6, y = 30;
        String[][] rows = {
                {"HITS", String.valueOf(hits)},
                {"ACCURACY", swings == 0 ? "–" : Math.round(100.0 * Math.min(hits, swings) / swings) + " %"},
                {"CPS", String.valueOf(Clicks.left())},
                {"COMBO", combo + "  (best " + bestCombo + ")"},
                {"REACH", hits == 0 ? "–" : String.format(java.util.Locale.ROOT, "%.2f  (max %.2f)", reachSum / hits, bestReach)},
                {"CRITS", String.valueOf(crits)},
                {"DAMAGE", String.format(java.util.Locale.ROOT, "%.1f  (top %.1f)", damage, biggest)},
                {"DPS", String.format(java.util.Locale.ROOT, "%.1f", dps())},
                {"HITS TAKEN", String.valueOf(hitsTaken)},
        };
        float rowH = 9.5f, h = 22 + rows.length * rowH;
        Px.roundRect(g, x, y, w, h, 5, 0xC8000000 | (theme.bg & 0xFFFFFF));
        Px.roundStroke(g, x, y, w, h, 5, 0x40000000 | (theme.line & 0xFFFFFF));
        Px.diamond(g, x + 8, y + 8.5f, 2, 0xFF000000 | (theme.accent & 0xFFFFFF));
        Type.spaced(g, "TRAINING", Type.MONO, x + 14, y + 5, 0.62f, theme.text, 1.2f);
        long secs = (System.currentTimeMillis() - started) / 1000;
        Type.right(g, String.format("%d:%02d", secs / 60, secs % 60), Type.MONO, x + w - 7, y + 5, 0.62f, theme.muted);
        Px.hline(g, x + 6, x + w - 6, y + 15, 0x30000000 | (theme.line & 0xFFFFFF));
        float ry = y + 19;
        for (String[] r : rows) {
            Type.left(g, r[0], Type.MONO, x + 7, ry, 0.55f, theme.muted);
            Type.right(g, r[1], Type.BOLD, x + w - 7, ry - 0.5f, 0.62f, theme.text);
            ry += rowH;
        }
    }
}
