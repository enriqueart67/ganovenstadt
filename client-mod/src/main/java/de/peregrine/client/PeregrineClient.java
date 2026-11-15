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

    private static KeyMapping menuKey, zoomKey, fullbrightKey, sprintKey, emoteKey, freelookKey, waypointKey, assistantKey, espKey, auraKey;

    @Override
    public void onInitializeClient() {
        Store.load();
        Store.save(); // writes the module catalogue for the launcher's Mods page
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.menu", InputConstants.KEY_RSHIFT, CATEGORY));
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.zoom", InputConstants.KEY_C, CATEGORY));
        fullbrightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.fullbright", InputConstants.KEY_G, CATEGORY));
        sprintKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.sprint", InputConstants.KEY_V, CATEGORY));
        emoteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.emote", InputConstants.KEY_B, CATEGORY));
        freelookKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.freelook", InputConstants.KEY_LALT, CATEGORY));
        waypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.waypoints", InputConstants.KEY_M, CATEGORY));
        assistantKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.assistant", InputConstants.KEY_K, CATEGORY));
        espKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.esp", InputConstants.KEY_X, CATEGORY));
        auraKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peregrine.aura", InputConstants.KEY_R, CATEGORY));
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) Modules.AUTO_GG.onMessage(msg.getString());
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("peregrine", "hud"), (g, dt) -> {
            if (HudEditor.active) return;
            Minecraft mc = Minecraft.getInstance();
            HudModule.layout(g.guiWidth(), g.guiHeight(), Modules.huds());
            Modules.BLOCK_ESP.render(g, mc);
            Modules.AURA.render(g, mc);
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
        // 3D cosmetics on player models (only drawn for your own character)
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatar) {
                @SuppressWarnings("unchecked")
                var parent = (net.minecraft.client.renderer.entity.RenderLayerParent<net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel>) (Object) avatar;
                helper.register(new CosmeticLayer(parent, ctx));
            }
        });
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

    private static boolean aiWasDown;
    private static java.lang.reflect.Method keyDownM;

    /** InputConstants.isKeyDown(Window, int) up to 26.2, isKeyDown(int) in 26.3. */
    private static boolean keyDown(Minecraft mc, int key) throws ReflectiveOperationException {
        if (keyDownM == null)
            for (var m : InputConstants.class.getMethods()) if (m.getName().equals("isKeyDown")) keyDownM = m;
        return keyDownM.getParameterCount() == 2 ? (boolean) keyDownM.invoke(null, mc.getWindow(), key) : (boolean) keyDownM.invoke(null, key);
    }

    private static void onTick(Minecraft mc) {
        boolean openAi = false;
        while (assistantKey.consumeClick()) openAi = true;
        // also read the key directly, so it works even if another mod or a vanilla action uses the same key
        boolean aiDown = false;
        try {
            InputConstants.Key k = KeyMappingHelper.getBoundKeyOf(assistantKey);
            if (Ui.current() == null && k.getType() != InputConstants.Type.MOUSE && k.getValue() > 0) aiDown = keyDown(mc, k.getValue());
        } catch (Throwable ignored) {
        }
        if (aiDown && !aiWasDown) openAi = true;
        aiWasDown = aiDown;
        if (openAi && mc.player != null && !(Ui.current() instanceof de.peregrine.client.gui.AssistantScreen)) Ui.open(new de.peregrine.client.gui.AssistantScreen());
        de.peregrine.client.bot.Bot.tick(mc);
        SessionStats.tick(mc);
        while (emoteKey.consumeClick()) if (Modules.EMOTES.enabled && mc.player != null) Ui.open(new de.peregrine.client.gui.EmoteWheel());
        while (menuKey.consumeClick()) Ui.open(new ClickGui());
        while (espKey.consumeClick()) toggle(mc, Modules.BLOCK_ESP);
        while (auraKey.consumeClick()) toggle(mc, Modules.AURA);
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
