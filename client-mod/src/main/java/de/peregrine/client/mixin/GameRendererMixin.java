package de.peregrine.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.peregrine.client.Modules;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** „Kein Schadens-Wackeln“: Kamera bei Treffern nicht wackeln lassen. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void peregrine$noHurtCam(CameraRenderState state, PoseStack pose, CallbackInfo ci) {
        if (Modules.NO_HURT_CAM.enabled) ci.cancel();
    }
}
