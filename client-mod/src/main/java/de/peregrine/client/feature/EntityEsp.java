package de.peregrine.client.feature;

import de.peregrine.client.bot.Bot;
import de.peregrine.client.gui.Px;
import de.peregrine.client.gui.Type;
import de.peregrine.client.gui.Ui;
import de.peregrine.client.module.Category;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.ModeSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entity ESP: monsters, animals, players and dropped items through walls – box or glow, name, distance and health,
 * optional tracers. The assistant uses the same view: whatever it is watching (threats, prey) is highlighted while
 * it plays, even when the module is off.
 *
 * <p>Allowed in singleplayer, LAN worlds you host and servers where you are operator.</p>
 */
public final class EntityEsp extends Module {
    public final BoolSetting hostile = add(new BoolSetting("hostile", "Monsters", true));
    public final BoolSetting passive = add(new BoolSetting("passive", "Animals", false));
    public final BoolSetting players = add(new BoolSetting("players", "Players", true));
    public final BoolSetting items = add(new BoolSetting("items", "Dropped items", false));
    public final ModeSetting style = add(new ModeSetting("style", "Style", "Box + glow", "Box + glow", "Box", "Glow"));
    public final NumberSetting range = add(new NumberSetting("range", "Range", 48, 8, 128, 8, " blocks"));
    public final BoolSetting labels = add(new BoolSetting("labels", "Name, distance and health", true));
    public final BoolSetting tracers = add(new BoolSetting("tracers", "Tracers", false));

    public static final int HOSTILE = 0xFFF05A5A, PASSIVE = 0xFF4AC86E, PLAYER = 0xFF4F8CFF, ITEM = 0xFFF5C41E, WATCHED = 0xFFFFFFFF;

    /** Entities the assistant is watching right now (ids), highlighted even when the module is off. */
    private Set<Integer> watched = Set.of();

    public EntityEsp() {
        super("entityesp", "Entity ESP", "Monsters, animals, players and items through walls (J)", Category.GRAPHICS, false);
    }

    public void watch(Set<Integer> ids) {
        watched = ids;
    }

    private int colour(Entity e) {
        if (e instanceof Player) return players.on() ? PLAYER : 0;
        if (e instanceof ItemEntity) return items.on() ? ITEM : 0;
        if (e instanceof Enemy) return hostile.on() ? HOSTILE : 0;
        if (e instanceof LivingEntity) return passive.on() ? PASSIVE : 0;
        return 0;
    }

    public void render(GuiGraphicsExtractor g, Minecraft mc, float pt) {
        boolean show = enabled || !watched.isEmpty();
        if (!show || mc.player == null || mc.level == null || !Access.allowed(mc, Access.ENTITY_ESP)) return;
        Projector pr = Projector.of(mc, g);
        if (pr == null) return;
        Vec3 cam = pr.camera();
        double r = range.d();
        List<Entity> list = new ArrayList<>();
        for (Entity e : mc.level.getEntitiesOfClass(Entity.class, new AABB(mc.player.blockPosition()).inflate(r), e -> e != mc.player && e.isAlive())) {
            if (watched.contains(e.getId()) || (enabled && colour(e) != 0)) list.add(e);
        }
        list.sort(Comparator.comparingDouble((Entity e) -> -e.position().distanceToSqr(cam))); // far first
        boolean box = !style.is("Glow"), glow = !style.is("Box");
        List<float[]> used = new ArrayList<>();
        int labelled = 0;
        if (list.size() > 160) list = new ArrayList<>(list.subList(list.size() - 160, list.size())); // the nearest 160
        for (int idx = 0; idx < list.size(); idx++) {
            Entity e = list.get(idx);
            boolean w = watched.contains(e.getId());
            int col = w && colour(e) == 0 ? (e instanceof Enemy ? HOSTILE : WATCHED) : (enabled ? colour(e) : HOSTILE);
            if (col == 0) col = WATCHED;
            Vec3 at = e.getPosition(pt);
            AABB bb = e.getBoundingBox().move(at.subtract(e.position()));
            float[] s;
            if (box) s = pr.box(g, bb.inflate(0.04), w ? 2f : 1.4f, col);
            else {
                float[] t = pr.project(at.x, bb.maxY, at.z), b = pr.project(at.x, bb.minY, at.z);
                s = t == null || b == null ? null : new float[]{Math.min(t[0], b[0]) - 4, t[1], Math.max(t[0], b[0]) + 4, b[1]};
            }
            if (s == null) continue;
            if (glow) {
                int cx = Math.round((s[0] + s[2]) / 2), cy = Math.round((s[1] + s[3]) / 2);
                int hh = Math.max(8, Math.round(s[3] - s[1]));
                Ui.glow(g, cx, cy, Math.max(8, Math.round((s[2] - s[0]) * 1.6f)), Math.round(hh * 1.5f), (w ? 0x70000000 : 0x48000000) | (col & 0xFFFFFF));
            }
            if (tracers.on() && enabled) {
                Px.line(g, pr.w / 2f, pr.h, (s[0] + s[2]) / 2, s[3], 1f, 0x90000000 | (col & 0xFFFFFF));
            }
            boolean nearOnes = idx >= list.size() - 12;
            if (labels.on() && (nearOnes || w) && labelled < 14) {
                double d = Math.sqrt(e.position().distanceToSqr(cam));
                String name = e instanceof ItemEntity it ? it.getItem().getHoverName().getString() + (it.getItem().getCount() > 1 ? " ×" + it.getItem().getCount() : "") : e.getName().getString();
                String label = name + "  " + Math.round(d) + " m";
                float sc = 0.68f;
                int tw = Type.width(label, Type.BOLD, sc);
                float tx = (s[0] + s[2]) / 2 - tw / 2f, ty = s[1] - 12;
                boolean free = true;
                for (float[] u : used) if (tx - 4 < u[2] && tx + tw + 4 > u[0] && ty - 2 < u[3] && ty + 11 > u[1]) free = false;
                if (!free) continue;
                used.add(new float[]{tx - 4, ty - 2, tx + tw + 4, ty + 11});
                Px.roundRect(g, tx - 4, ty - 2, tw + 8, 11, 3, 0xB0101216);
                Type.left(g, label, Type.BOLD, tx, ty, sc, 0xFFFFFFFF);
                if (e instanceof LivingEntity le && le.getMaxHealth() > 0) {
                    float f = Math.max(0, Math.min(1, le.getHealth() / le.getMaxHealth()));
                    Px.roundRect(g, tx - 4, ty + 8.5f, (tw + 8) * f, 1.4f, 0.7f, 0xFF000000 | (col & 0xFFFFFF));
                }
                labelled++;
            }
        }
    }

    /** Hostile mobs around a point, seen or not – the assistant's danger picture. */
    public static List<LivingEntity> threats(Minecraft mc, Vec3 c, double r) {
        if (mc.level == null) return List.of();
        return mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r), e -> e instanceof Enemy && e.isAlive() && e != mc.player);
    }

    public static Set<Integer> ids(List<? extends Entity> l) {
        Set<Integer> s = new HashSet<>();
        for (Entity e : l) s.add(e.getId());
        return s;
    }
}
