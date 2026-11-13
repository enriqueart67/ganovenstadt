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

    private static KeyMapping menuKey, zoomKey, fullbrightKey, sprintKey;

    @Override
    public void onInitializeClient() {
        Store.load();
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.menu", InputConstants.KEY_RSHIFT, CATEGORY));
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.zoom", InputConstants.KEY_C, CATEGORY));
        fullbrightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.fullbright", InputConstants.KEY_G, CATEGORY));
        sprintKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.sprint", InputConstants.KEY_V, CATEGORY));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("peregrine", "hud"), (g, dt) -> {
            if (HudEditor.active) return;
            Minecraft mc = Minecraft.getInstance();
            HudModule.layout(g.guiWidth(), g.guiHeight(), Modules.huds());
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
        LOG.info("Peregrine Client geladen – Menü mit Rechts-Shift");
    }

    /** Ist das die (frei belegbare) Taste für das Mod-Menü? */
    public static boolean isMenuKey(net.minecraft.client.input.KeyEvent e) {
        return menuKey != null && menuKey.matches(e);
    }

    private static void onTick(Minecraft mc) {
        while (menuKey.consumeClick()) Ui.open(new ClickGui());
        Zoom.setHeld(zoomKey.isDown());
        while (fullbrightKey.consumeClick()) toggle(mc, Modules.FULLBRIGHT);
        while (sprintKey.consumeClick()) toggle(mc, Modules.TOGGLE_SPRINT);
        for (Module m : Modules.ALL) m.tick(mc);
    }

    private static void toggle(Minecraft mc, Module m) {
        m.toggle();
        Store.save();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§6[Peregrine]§r " + m.name + ": " + (m.enabled ? "§aan" : "§caus")));
    }
}
