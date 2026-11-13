package de.peregrine.client.mixin;

import de.peregrine.client.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom: Sichtfeld verkleinern. */
@Mixin(Camera.class)
public class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void peregrine$zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
        double f = Zoom.factor();
        if (f > 1.0001) {
            cir.setReturnValue((float) (cir.getReturnValue() / f));
        }
    }
}
