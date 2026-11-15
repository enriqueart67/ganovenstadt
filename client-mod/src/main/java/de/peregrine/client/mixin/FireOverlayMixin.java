package de.peregrine.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.peregrine.client.Modules;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low fire: draw the first-person fire overlay further down. */
@Mixin(ScreenEffectRenderer.class)
public class FireOverlayMixin {
    @Inject(method = "submitFire", at = @At("HEAD"), require = 0)
    private static void peregrine$lower(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (Modules.LOW_FIRE.enabled) {
            pose.pushPose();
            pose.translate(0f, (float) -Modules.LOW_FIRE.height.d(), 0f);
        }
    }

    @Inject(method = "submitFire", at = @At("RETURN"), require = 0)
    private static void peregrine$restore(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (Modules.LOW_FIRE.enabled) pose.popPose();
    }
}
