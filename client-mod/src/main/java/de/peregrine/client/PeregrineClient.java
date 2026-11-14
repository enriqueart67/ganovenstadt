package de.peregrine.client;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.gui.ClickGui;
import de.peregrine.client.gui.HudEditor;
import de.peregrine.client.gui.Ui;
import de.peregrine.client.module.HudModule;
import de.peregrine.client.module.Module;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PeregrineClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Peregrine");
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("peregrine", "main"));

    private static KeyMapping menuKey, zoomKey, fullbrightKey, sprintKey, emoteKey, freelookKey, waypointKey;

    @Override
    public void onInitializeClient() {
        Store.load();
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.menu", InputConstants.KEY_RSHIFT, CATEGORY));
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.zoom", InputConstants.KEY_C, CATEGORY));
        fullbrightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.fullbright", InputConstants.KEY_G, CATEGORY));
        sprintKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.sprint", InputConstants.KEY_V, CATEGORY));
        emoteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.emote", InputConstants.KEY_B, CATEGORY));
        freelookKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.freelook", InputConstants.KEY_LALT, CATEGORY));
        waypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.waypoints", InputConstants.KEY_M, CATEGORY));
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) Modules.AUTO_GG.onMessage(msg.getString());
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("peregrine", "hud"), (g, dt) -> {
            if (HudEditor.active) return;
            Minecraft mc = Minecraft.getInstance();
            HudModule.layout(g.guiWidth(), g.guiHeight(), Modules.huds());
            Waypoints.render(g, mc);
            for (HudModule h : Modules.huds()) if (h.enabled) h.render(g, mc, false);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (HudElement) (g, dt) -> {
            Minecraft mc = Minecraft.getInstance();
            if (Modules.CROSSHAIR.enabled && mc.options.getCameraType().isFirstPerson()) Modules.CROSSHAIR.draw(g);
            else vanilla.extractRenderState(g, dt);
        });

        // Reichweite und Combo: eigene Treffer zaehlen
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Minecraft mc = Minecraft.getInstance();
            if (player == mc.player) {
                if (mc.hitResult != null) {
                    Modules.Reach.last = mc.hitResult.getLocation().distanceTo(player.getEyePosition());
                    Modules.Reach.lastTime = System.currentTimeMillis();
                }
                long now = System.currentTimeMillis();
                Modules.Combo.combo = (entity.getId() == Modules.Combo.lastTarget && now - Modules.Combo.lastHit < 2500) ? Modules.Combo.combo + 1 : 1;
                Modules.Combo.lastTarget = entity.getId();
                Modules.Combo.lastHit = now;
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(PeregrineClient::onTick);
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> de.peregrine.client.gui.TitleMenu.onScreenInit(mc, screen));
        LOG.info("Peregrine Client loaded – press Right Shift for the menu");
    }

    /** Ist das die (frei belegbare) Taste für das Mod-Menü? */
    public static boolean isMenuKey(net.minecraft.client.input.KeyEvent e) {
        return menuKey != null && menuKey.matches(e);
    }

    public static boolean isEmoteKey(net.minecraft.client.input.KeyEvent e) {
        return emoteKey != null && emoteKey.matches(e);
    }

    private static void onTick(Minecraft mc) {
        while (emoteKey.consumeClick()) if (Modules.EMOTES.enabled && mc.player != null) Ui.open(new de.peregrine.client.gui.EmoteWheel());
        while (menuKey.consumeClick()) Ui.open(new ClickGui());
        while (waypointKey.consumeClick()) if (Modules.WAYPOINTS.enabled && mc.player != null) Ui.open(new de.peregrine.client.gui.WaypointScreen());
        Zoom.setHeld(zoomKey.isDown());
        FreelookState.update(mc, freelookKey.isDown());
        while (fullbrightKey.consumeClick()) toggle(mc, Modules.FULLBRIGHT);
        while (sprintKey.consumeClick()) toggle(mc, Modules.TOGGLE_SPRINT);
        for (Module m : Modules.ALL) m.tick(mc);
    }

    private static void toggle(Minecraft mc, Module m) {
        m.toggle();
        Store.save();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§6[Peregrine]§r " + m.name + ": " + (m.enabled ? "§aon" : "§coff")));
    }
}
