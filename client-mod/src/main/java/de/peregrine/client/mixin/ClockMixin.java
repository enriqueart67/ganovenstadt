package de.peregrine.client.mixin;

import de.peregrine.client.Modules;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Time module: freeze the visible time of day (client side only). */
@Mixin(ClientClockManager.class)
public class ClockMixin {
    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true, require = 0)
    private void peregrine$time(net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clock, CallbackInfoReturnable<Long> cir) {
        if (Modules.TIME.enabled) cir.setReturnValue(Modules.TIME.ticks());
    }
}
