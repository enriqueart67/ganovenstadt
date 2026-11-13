package de.peregrine.client.mixin;

import de.peregrine.client.FreelookState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Freelook: Kamera-Drehung durch die freie Blickrichtung ersetzen. */
@Mixin(Camera.class)
public class CameraRotationMixin {
    @ModifyVariable(method = "setRotation", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private float peregrine$yaw(float yaw) {
        return FreelookState.active ? FreelookState.yaw : yaw;
    }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
    private float peregrine$pitch(float pitch) {
        return FreelookState.active ? FreelookState.pitch : pitch;
    }
}
