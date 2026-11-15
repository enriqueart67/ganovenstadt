package de.peregrine.client.bot;

import de.peregrine.client.module.Category;
import de.peregrine.client.module.Module;
import de.peregrine.client.setting.BoolSetting;
import de.peregrine.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emergency logout: when you are down to half a heart while the assistant plays for you, it takes a screenshot,
 * writes down what was going on (what hit you, who was around, what the bot was doing) and logs out at once –
 * so you can see what happened when you come back and decide how to save the situation.
 */
public final class EmergencyLogout extends Module {
    public final NumberSetting hearts = add(new NumberSetting("hearts", "Trigger at", 0.5, 0.5, 5, 0.5, " hearts"));
    public final BoolSetting onlyBot = add(new BoolSetting("onlybot", "Only while the assistant plays", true));
    public final BoolSetting shot = add(new BoolSetting("shot", "Take a screenshot", true));

    private int logoutIn = -1, shotIn = -1, waitTicks;
    private long frames, framesMark;

    /** Called once per rendered frame (HUD layer): we wait for real frames, not ticks, before the screenshot. */
    public void onFrame() {
        frames++;
    }
    private List<String> report;
    private String shotName;
    private float heartsLeft;

    public EmergencyLogout() {
        super("emergency", "Emergency logout", "Screenshot + log out at half a heart while the bot plays", Category.OTHER, false);
    }

    @Override
    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (shotIn >= 0) { // two fresh frames after closing the menus: the frame shows the world
            if (frames - framesMark >= 2 || ++waitTicks > 100) {
                shotIn = -1;
                if (shot.on()) screenshot(mc, shotName);
                framesMark = frames;
                waitTicks = 0;
                logoutIn = 1;
            }
            return;
        }
        if (logoutIn >= 0) { // let the screenshot be copied, then leave
            if (frames - framesMark >= 2 || ++waitTicks > 100) {
                logoutIn = -1;
                waitTicks = 0;
                leave(mc);
            }
            return;
        }
        if (!enabled || p == null || mc.level == null || p.isDeadOrDying() || p.isCreative() || p.isSpectator()) return;
        if (onlyBot.on() && !Bot.busy()) return;
        if (p.getHealth() > hearts.d() * 2 + 0.01) return;
        trigger(mc, p);
    }

    private void trigger(Minecraft mc, LocalPlayer p) {
        String botStatus = Bot.busy() ? Bot.status() : "assistant not running";
        heartsLeft = p.getHealth() / 2f;
        Bot.stop(null);
        Bot.release(mc);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
        shotName = "peregrine-emergency-" + stamp + ".png";
        report = buildReport(mc, p, botStatus, stamp);
        writeReport(mc, "peregrine-emergency-" + stamp + ".txt", report);
        de.peregrine.client.gui.Ui.open(null); // close any menu so the screenshot shows the world
        shotIn = 1;
        framesMark = frames;
        waitTicks = 0;
    }

    private static List<String> buildReport(Minecraft mc, LocalPlayer p, String botStatus, String stamp) {
        List<String> r = new ArrayList<>();
        r.add("Time: " + stamp.replace('_', ' '));
        var src = p.getLastDamageSource();
        if (src != null) {
            try {
                r.add("What hit you: " + src.getLocalizedDeathMessage(p).getString().replace(p.getName().getString(), "You"));
            } catch (Throwable e) {
                r.add("What hit you: " + src.getMsgId());
            }
        }
        r.add(String.format(Locale.ROOT, "Health %.1f hearts · food %d · armor %d", p.getHealth() / 2f, p.getFoodData().getFoodLevel(), p.getArmorValue()));
        r.add("Bot was: " + botStatus);
        r.add(String.format(Locale.ROOT, "Position %d %d %d (%s)", p.getBlockX(), p.getBlockY(), p.getBlockZ(), de.peregrine.client.Waypoints.dimension(mc).replace("minecraft:", "")));
        r.add("In hand: " + name(p.getMainHandItem()) + " · off hand: " + name(p.getOffhandItem()));
        List<String> around = new ArrayList<>();
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(24), e -> e != p && e.isAlive())) {
            if (e instanceof Player pl) around.add("player " + pl.getName().getString() + " " + Math.round(e.distanceTo(p)) + " m");
            else if (e instanceof Enemy) around.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' ') + " " + Math.round(e.distanceTo(p)) + " m");
        }
        r.add(around.isEmpty() ? "Nothing hostile nearby." : "Around you: " + String.join(", ", around.subList(0, Math.min(8, around.size()))));
        int n = Bot.CHAT.size();
        for (int i = Math.max(0, n - 3); i < n; i++) r.add((Bot.CHAT.get(i)[0].equals("you") ? "You: " : "Assistant: ") + Bot.CHAT.get(i)[1]);
        return r;
    }

    private static String name(net.minecraft.world.item.ItemStack s) {
        return s.isEmpty() ? "nothing" : s.getHoverName().getString() + (s.getCount() > 1 ? " ×" + s.getCount() : "");
    }

    /** Screenshot of the current frame into screenshots/<name>; the render target getter changed name between versions. */
    private static void screenshot(Minecraft mc, String name) {
        try {
            Object target = null;
            for (Object owner : new Object[]{mc, mc.gameRenderer}) {
                for (var m : owner.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals("RenderTarget")) {
                        target = m.invoke(owner);
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target instanceof com.mojang.blaze3d.pipeline.RenderTarget rt) {
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, rt, 1, msg -> {});
                return;
            }
            // fallback: the normal screenshot (vanilla file name)
            net.minecraft.client.Screenshot.class.getMethod("grab", Minecraft.class, boolean.class).invoke(null, mc, false);
        } catch (Throwable e) {
            de.peregrine.client.PeregrineClient.LOG.warn("Emergency screenshot failed: {}", e.toString());
        }
    }

    private static void writeReport(Minecraft mc, String file, List<String> lines) {
        try {
            File dir = new File(mc.gameDirectory, "screenshots");
            Files.createDirectories(dir.toPath());
            Files.writeString(new File(dir, file).toPath(), String.join(System.lineSeparator(), lines));
        } catch (Exception ignored) {
        }
    }

    /** Leave like the pause menu does, then show what happened. */
    private void leave(Minecraft mc) {
        var screen = new de.peregrine.client.gui.EmergencyScreen(report, new File(new File(mc.gameDirectory, "screenshots"), shotName), heartsLeft);
        try {
            if (mc.level != null) mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            mc.disconnect(screen, false);
        } catch (Throwable e) {
            de.peregrine.client.PeregrineClient.LOG.warn("Emergency logout: {}", e.toString());
        }
        de.peregrine.client.gui.Ui.open(screen);
    }
}
