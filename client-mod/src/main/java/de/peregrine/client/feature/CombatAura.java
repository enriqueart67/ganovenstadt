package de.peregrine.client.feature;

import de.peregrine.client.gui.Px;
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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * Combat Aura for singleplayer and private test worlds: finds hostile mobs, turns towards the chosen one and
 * attacks on full charge (or at a set rate). Never targets players, switches itself off in multiplayer and does
 * nothing to hide from anti-cheats – it only uses the normal attack, exactly like a click.
 */
public final class CombatAura extends Module {
    public final NumberSetting range = add(new NumberSetting("range", "Range", 3.0, 2.0, 6.0, 0.1, " m"));
    public final ModeSetting targets = add(new ModeSetting("targets", "Targets", "Hostile", "Hostile", "Hostile + neutral", "All mobs"));
    public final ModeSetting priority = add(new ModeSetting("priority", "Priority", "Closest", "Closest", "Lowest health", "Crosshair"));
    public final ModeSetting rotation = add(new ModeSetting("rotation", "Rotation", "Smooth", "Smooth", "Instant"));
    public final NumberSetting turn = add(new NumberSetting("turn", "Turn speed", 45, 10, 180, 5, "°/tick"));
    public final ModeSetting timing = add(new ModeSetting("timing", "Attack timing", "Full charge", "Full charge", "Fixed CPS"));
    public final NumberSetting cps = add(new NumberSetting("cps", "Clicks per second", 8, 1, 20, 1, " CPS"));
    public final ModeSetting visual = add(new ModeSetting("visual", "Target marker", "Ring", "Ring", "Box", "Glow", "Off"));
    public final ColorSetting color = add(new ColorSetting("color", "Marker color", 0xFFF05A5A));
    public final BoolSetting walls = add(new BoolSetting("walls", "Through walls", false));
    public final BoolSetting weaponOnly = add(new BoolSetting("weapon", "Only with a weapon in hand", false));

    private LivingEntity target;
    private int sinceHit;
    private boolean warned;

    public CombatAura() {
        super("aura", "Combat Aura", "Fights hostile mobs for you – singleplayer only (R)", Category.PVP, false);
    }

    public LivingEntity target() {
        return target;
    }

    @Override
    protected void onToggle() {
        target = null;
        warned = false;
    }

    private boolean wanted(LivingEntity e) {
        if (e instanceof Player || !(e instanceof Mob)) return false; // never players
        return switch (targets.get()) {
            case "Hostile" -> e instanceof Enemy;
            case "Hostile + neutral" -> e instanceof Enemy || e instanceof NeutralMob;
            default -> true;
        };
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    @Override
    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!enabled || p == null || mc.level == null) {
            target = null;
            return;
        }
        if (!mc.hasSingleplayerServer()) { // multiplayer: off, always
            if (!warned) {
                p.sendOverlayMessage(Component.literal("§6[Peregrine]§r Combat Aura is singleplayer only – it stays off here."));
                warned = true;
            }
            target = null;
            return;
        }
        if (de.peregrine.client.gui.Ui.current() != null || p.isSpectator() || !p.isAlive()) {
            target = null;
            return;
        }
        String held = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
        if (weaponOnly.on() && !(held.endsWith("_sword") || held.endsWith("_axe") || held.equals("mace") || held.endsWith("_spear") || held.equals("trident"))) {
            target = null;
            return;
        }
        sinceHit++;
        double r = range.d();
        Vec3 eye = p.getEyePosition();
        target = mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(r + 2),
                        e -> e != p && e.isAlive() && wanted(e) && e.getBoundingBox().distanceToSqr(eye) <= r * r && (walls.on() || p.hasLineOfSight(e)))
                .stream()
                .min(switch (priority.get()) {
                    case "Lowest health" -> Comparator.comparingDouble(LivingEntity::getHealth);
                    case "Crosshair" -> Comparator.comparingDouble(e -> Math.abs(wrap(yawTo(eye, e) - p.getYRot())));
                    default -> Comparator.comparingDouble(e -> e.getBoundingBox().distanceToSqr(eye));
                })
                .orElse(null);
        if (target == null) return;

        // turn towards the target
        Vec3 aim = target.getBoundingBox().getCenter();
        float yaw = yawTo(eye, target);
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y - eye.y, Math.hypot(aim.x - eye.x, aim.z - eye.z)));
        float dy = wrap(yaw - p.getYRot()), dp = pitch - p.getXRot();
        if (rotation.is("Instant")) {
            p.setYRot(p.getYRot() + dy);
            p.setXRot(pitch);
        } else {
            float step = (float) turn.d();
            p.setYRot(p.getYRot() + Math.max(-step, Math.min(step, dy * 0.6f)));
            p.setXRot(p.getXRot() + Math.max(-step, Math.min(step, dp * 0.6f)));
        }
        p.setYHeadRot(p.getYRot());

        // attack when facing it and the timing allows
        boolean facing = Math.abs(wrap(yaw - p.getYRot())) < 14 && Math.abs(pitch - p.getXRot()) < 20;
        boolean timed = timing.is("Fixed CPS") ? sinceHit >= Math.max(1, Math.round(20.0 / cps.d())) : p.getAttackStrengthScale(0f) >= 0.95f;
        if (facing && timed) {
            mc.gameMode.attack(p, target);
            de.peregrine.client.bot.Bot.swing(p);
            sinceHit = 0;
        }
    }

    private static float yawTo(Vec3 from, LivingEntity e) {
        Vec3 c = e.getBoundingBox().getCenter();
        return (float) Math.toDegrees(Math.atan2(-(c.x - from.x), c.z - from.z));
    }

    /** Marker on the current target. */
    public void render(GuiGraphicsExtractor g, Minecraft mc) {
        if (!enabled || target == null || !target.isAlive() || visual.is("Off")) return;
        Projector pr = Projector.of(mc, g);
        if (pr == null) return;
        float pt = 1f;
        Vec3 pos = target.position();
        int col = 0xFF000000 | (color.argb() & 0xFFFFFF);
        long now = System.currentTimeMillis();
        AABB bb = target.getBoundingBox();
        float[] top = pr.project(pos.x, bb.maxY + 0.35, pos.z);
        switch (visual.get()) {
            case "Ring" -> {
                double rad = Math.max(0.45, target.getBbWidth() * 0.85);
                double y = pos.y + 0.05 + 0.08 * Math.sin(now / 300.0);
                int n = 40;
                double spin = now / 900.0;
                double[] prev = null;
                for (int i = 0; i <= n; i++) {
                    double a = spin + i * Math.PI * 2 / n;
                    double[] q = pr.toCam(pos.x + Math.cos(a) * rad, y, pos.z + Math.sin(a) * rad);
                    if (prev != null) {
                        int alpha = (int) (110 + 145 * (0.5 + 0.5 * Math.sin(a * 2)));
                        pr.line(g, prev, q, 2f, (alpha << 24) | (col & 0xFFFFFF));
                    }
                    prev = q;
                }
            }
            case "Box" -> pr.box(g, bb.inflate(0.05), 1.5f, col);
            default -> {
                float[] c = pr.project(pos.x, (bb.minY + bb.maxY) / 2, pos.z);
                float[] t2 = pr.project(pos.x, bb.maxY, pos.z), b2 = pr.project(pos.x, bb.minY, pos.z);
                if (c != null && t2 != null && b2 != null) {
                    int hgt = Math.max(10, Math.round(b2[1] - t2[1]));
                    Ui.glow(g, Math.round(c[0]), Math.round(c[1]), Math.round(hgt * 1.1f), Math.round(hgt * 1.4f), 0x70000000 | (col & 0xFFFFFF));
                }
            }
        }
        if (top != null) { // name + health
            String label = target.getName().getString() + "  " + Math.round(target.getHealth()) + " HP";
            float sc = 0.7f;
            int tw = Type.width(label, Type.BOLD, sc);
            Px.roundRect(g, top[0] - tw / 2f - 4, top[1] - 2, tw + 8, 10, 3, 0xB0101216);
            Type.left(g, label, Type.BOLD, top[0] - tw / 2f, top[1], sc, 0xFFFFFFFF);
            float f = Math.max(0, Math.min(1, target.getHealth() / target.getMaxHealth()));
            Px.roundRect(g, top[0] - tw / 2f - 4, top[1] + 9, (tw + 8) * f, 1.5f, 0.75f, col);
        }
    }
}
