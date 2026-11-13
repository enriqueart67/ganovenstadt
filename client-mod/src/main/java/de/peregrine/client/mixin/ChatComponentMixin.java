package de.peregrine.client.mixin;

import de.peregrine.client.Modules;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Chat-Zeitstempel: Uhrzeit vor jede Nachricht setzen. */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private Component peregrine$time(Component msg) {
        if (!Modules.CHAT_TIME.enabled) return msg;
        String t = LocalTime.now().format(DateTimeFormatter.ofPattern(Modules.CHAT_TIME.format.get()));
        return Component.literal("[" + t + "] ").withStyle(ChatFormatting.GRAY).append(msg);
    }
}
