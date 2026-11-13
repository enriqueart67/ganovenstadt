package de.peregrine.client.mixin;

import de.peregrine.client.gui.LoadingScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ersetzt den Mojang-Ladebildschirm (Start/Ressourcen neu laden) durch den Peregrine-Bildschirm. */
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
    @Shadow
    private float currentProgress;
    @Shadow
    private long fadeOutStart;
    @Shadow
    private long fadeInStart;

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void peregrine$drawLoading(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        // gleicher Ablauf wie bei Mojang: nach dem Laden noch 1 s voll sichtbar, dann 1 s ausblenden
        long now = Util.getMillis();
        float alpha = 1f;
        if (fadeOutStart > -1L) {
            float t = (now - fadeOutStart) / (float) LoadingOverlay.FADE_OUT_TIME;
            alpha = t < 1f ? 1f : 1f - (t - 1f);
        } else if (fadeInStart > -1L) {
            alpha = Math.min(1f, (now - fadeInStart) / 500f);
        }
        if (alpha > 0f) {
            g.nextStratum(); // eigene Ebene ueber Mojangs Logo
            LoadingScreens.startup(g, fadeOutStart > -1L ? 1f : currentProgress, alpha);
        }
    }
}
