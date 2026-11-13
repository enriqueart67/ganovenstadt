package de.peregrine.client.mixin;

import de.peregrine.client.Emotes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emotes: Pose der eigenen Spielfigur nach der normalen Animation überschreiben. */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void peregrine$emote(AvatarRenderState state, CallbackInfo ci) {
        if (Emotes.active() == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.id != mc.player.getId()) return;
        Emotes.apply((PlayerModel) (Object) this);
    }
}
