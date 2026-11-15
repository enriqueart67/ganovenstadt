package de.peregrine.client.mixin;

import de.peregrine.client.FreelookState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook: Mausbewegung dreht nur die Kamera, nicht den Spieler. */
@Mixin(Entity.class)
public class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
    private void peregrine$freelook(double dx, double dy, CallbackInfo ci) {
        if (!FreelookState.active) return;
        if ((Object) this != Minecraft.getInstance().player) return;
        FreelookState.turn(dx, dy);
        ci.cancel();
    }
}
