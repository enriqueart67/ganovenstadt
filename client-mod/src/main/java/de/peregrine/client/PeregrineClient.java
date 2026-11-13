package de.peregrine.client;

import com.mojang.blaze3d.platform.InputConstants;
import de.peregrine.client.mixin.OptionInstanceAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PeregrineClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Peregrine");
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("peregrine", "main"));

    private static KeyMapping zoomKey, fullbrightKey, sprintKey;
    private static Double savedGamma;
    private static int tick;

    @Override
    public void onInitializeClient() {
        Config.reloadIfChanged();
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.zoom", InputConstants.KEY_C, CATEGORY));
        fullbrightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.fullbright", InputConstants.KEY_G, CATEGORY));
        sprintKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.sprint", InputConstants.KEY_V, CATEGORY));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("peregrine", "hud"), Hud::render);
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (HudElement) (g, dt) -> {
            Minecraft mc = Minecraft.getInstance();
            if (Config.get().crosshair.enabled && mc.options.getCameraType().isFirstPerson()) {
                Hud.crosshair(g);
            } else {
                vanilla.extractRenderState(g, dt);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(PeregrineClient::onTick);
        LOG.info("Peregrine Client geladen");
    }

    private static void onTick(Minecraft mc) {
        if (++tick % 20 == 0 && Config.reloadIfChanged()) {
            LOG.info("Einstellungen neu geladen");
        }
        Config c = Config.get();
        Zoom.setHeld(zoomKey.isDown());
        while (fullbrightKey.consumeClick()) {
            c.fullbright = !c.fullbright;
            message(mc, "Fullbright: " + (c.fullbright ? "an" : "aus"));
        }
        while (sprintKey.consumeClick()) {
            c.autoSprint = !c.autoSprint;
            message(mc, "Auto-Sprint: " + (c.autoSprint ? "an" : "aus"));
        }
        applyFullbright(mc, c.fullbright);
        if (c.autoSprint && mc.player != null && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown()) {
            mc.options.keySprint.setDown(true);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyFullbright(Minecraft mc, boolean on) {
        var gamma = mc.options.gamma();
        OptionInstanceAccessor acc = (OptionInstanceAccessor) (Object) gamma;
        if (on) {
            if (savedGamma == null) savedGamma = gamma.get();
            if (gamma.get() < 15.0) acc.peregrine$setValue(15.0);
        } else if (savedGamma != null) {
            acc.peregrine$setValue(savedGamma);
            savedGamma = null;
        }
    }

    private static void message(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("§b[Peregrine]§r " + text));
    }
}
