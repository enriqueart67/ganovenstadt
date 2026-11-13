package de.peregrine.client.mixin;

import de.peregrine.client.CustomCapes;
import de.peregrine.client.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Umhang: eigenen Peregrine-Umhang (und auf Wunsch die Elytra) für den eigenen Spieler setzen. */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void peregrine$cape(CallbackInfoReturnable<PlayerSkin> cir) {
        if (!Modules.CAPE.enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (Object) this != mc.player) return;
        PlayerSkin skin = cir.getReturnValue();
        if (skin == null) return;
        String design = Modules.CAPE.design.get();
        Identifier tex;
        String name;
        if (CustomCapes.isCustom(design)) {
            tex = CustomCapes.texture(design);
            if (tex == null) return;
            name = tex.getPath();
        } else {
            name = design.toLowerCase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue");
            tex = Identifier.fromNamespaceAndPath("peregrine", "textures/cape/" + name + ".png");
        }
        ClientAsset.ResourceTexture cape = new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("peregrine", "cape/" + name), tex);
        cir.setReturnValue(new PlayerSkin(skin.body(), cape, Modules.CAPE.elytra.on() ? cape : skin.elytra(), skin.model(), skin.secure()));
    }
}
