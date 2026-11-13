package de.peregrine.client.mixin;

import de.peregrine.client.Modules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** „Kein Regen“: Regen und Gewitter nur in der eigenen Anzeige abschalten. */
@Mixin(Level.class)
public class LevelMixin {
    @Inject(method = {"getRainLevel", "getThunderLevel"}, at = @At("HEAD"), cancellable = true)
    private void peregrine$noWeather(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (Modules.NO_WEATHER.enabled && (Object) this instanceof ClientLevel) cir.setReturnValue(0f);
    }
}
