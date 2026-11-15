package de.peregrine.client.mixin;

import de.peregrine.client.Clicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mausklicks fuer die CPS-Anzeige zaehlen. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void peregrine$countClick(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (action == 1 && Minecraft.getInstance().mouseHandler.isMouseGrabbed()) {
            Clicks.click(info.button());
        }
    }
}
