package de.peregrine.client.mixin;

import de.peregrine.client.Modules;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Block outline: custom colour for the outline of the targeted block. */
@Mixin(LevelRenderer.class)
public class BlockOutlineMixin {
    @ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private int peregrine$color(int color) {
        return Modules.BLOCK_OUTLINE.enabled ? Modules.BLOCK_OUTLINE.color.argb() : color;
    }
}
