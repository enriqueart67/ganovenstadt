package de.peregrine.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the assistant is mining, vanilla must not cancel the block break every tick. */
@Mixin(Minecraft.class)
public abstract class BotAttackMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void peregrine$botMining(boolean down, CallbackInfo ci) {
        if (de.peregrine.client.bot.Bot.busy()) ci.cancel();
    }
}
