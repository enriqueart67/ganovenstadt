package de.peregrine.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Unterdrueckt Mojangs eigene Zeichnungen im Ladebildschirm (Logo, roter Hintergrund, Balken) –
 * der Peregrine-Bildschirm (LoadingOverlayMixin) ersetzt sie komplett. Die Ablauf-Logik bleibt erhalten.
 * (Eigene Quelle je Grafik-Bibliothek: 26.1/26.2 blaze3d, ab 26.3 renderpearl.)
 */
@Mixin(LoadingOverlay.class)
public class LoadingLogoMixin {
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
    private void peregrine$noLogo(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, float u, float v,
                                  int w, int h, int a, int b, int c, int d, int color) {
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void peregrine$noBackground(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;extractProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"))
    private void peregrine$noBar(LoadingOverlay self, GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, float alpha) {
    }
}
