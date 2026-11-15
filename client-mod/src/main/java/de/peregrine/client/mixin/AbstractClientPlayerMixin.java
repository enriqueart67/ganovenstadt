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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (Object) this != mc.player) return;
        // the wardrobe's try-on preview wins over the equipped cape
        String preview = de.peregrine.client.CosmeticLayer.previewCape;
        if (preview == null && !Modules.CAPE.enabled) return;
        PlayerSkin skin = cir.getReturnValue();
        if (skin == null) return;
        if ("None".equals(preview)) {
            cir.setReturnValue(new PlayerSkin(skin.body(), null, skin.elytra(), skin.model(), skin.secure()));
            return;
        }
        String design = preview != null ? preview : Modules.CAPE.design.get();
        Identifier tex;
        String name;
        if (CustomCapes.isCustom(design)) {
            tex = CustomCapes.texture(design);
            if (tex == null) return;
            name = tex.getPath();
        } else {
            name = design.toLowerCase();
            tex = Identifier.fromNamespaceAndPath("peregrine", "textures/cape/" + name + ".png");
        }
        ClientAsset.ResourceTexture cape = new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("peregrine", "cape/" + name), tex);
        cir.setReturnValue(new PlayerSkin(skin.body(), cape, Modules.CAPE.elytra.on() ? cape : skin.elytra(), skin.model(), skin.secure()));
    }
}
