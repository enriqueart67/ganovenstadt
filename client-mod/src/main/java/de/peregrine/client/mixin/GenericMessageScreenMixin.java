package de.peregrine.client.mixin;

import de.peregrine.client.gui.LoadingScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Peregrine-Hintergrund fuer GenericMessageScreen (Welt laden / Speichern usw.). */
@Mixin(GenericMessageScreen.class)
public class GenericMessageScreenMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void peregrine$background(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        LoadingScreens.background(g);
        ci.cancel();
    }
}
