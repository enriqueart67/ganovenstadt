package de.peregrine.client.mixin;

import de.peregrine.client.Modules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Weather module: the weather you see, independent of the server (visual only). */
@Mixin(Level.class)
public class LevelMixin {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void peregrine$rain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (Modules.NO_WEATHER.enabled && (Object) this instanceof ClientLevel) cir.setReturnValue(Modules.NO_WEATHER.weather.is("Clear") ? 0f : 1f);
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void peregrine$thunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (Modules.NO_WEATHER.enabled && (Object) this instanceof ClientLevel) cir.setReturnValue(Modules.NO_WEATHER.weather.is("Storm") ? 1f : 0f);
    }
}
