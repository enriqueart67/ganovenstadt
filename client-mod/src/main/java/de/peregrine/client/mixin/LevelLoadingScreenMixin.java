package de.peregrine.client.mixin;

import de.peregrine.client.gui.LoadingScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** „Welt wird geladen“ komplett im Peregrine-Stil (Fortschritt kommt aus Minecrafts tick()). */
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {
    @Shadow
    private float smoothedProgress;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void peregrine$render(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        LoadingScreens.world(g, smoothedProgress);
        ci.cancel();
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void peregrine$background(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        ci.cancel();
    }
}
