package de.peregrine.client.mixin;

import de.peregrine.client.gui.LoadingScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Peregrine-Hintergrund fuer „Verbinde mit Server“ und Fortschritts-Bildschirme. */
@Mixin(Screen.class)
public class ScreenBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void peregrine$background(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        Object self = this;
        if (self instanceof ConnectScreen || self instanceof ProgressScreen) {
            LoadingScreens.background(g);
            ci.cancel();
        }
    }
}
