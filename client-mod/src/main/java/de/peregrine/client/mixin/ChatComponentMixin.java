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
        Component out = msg;
        if (Modules.MENTION.enabled) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                String me = mc.player.getGameProfile().name();
                String text = msg.getString();
                // someone else wrote our name (our own messages start with <name>)
                int at = text.toLowerCase().indexOf(me.toLowerCase());
                if (at > 0 && !text.startsWith("<" + me + ">")) {
                    out = Component.literal("● ").withStyle(ChatFormatting.GOLD).append(msg);
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
                }
            }
        }
        if (!Modules.CHAT_TIME.enabled) return out;
        String t = LocalTime.now().format(DateTimeFormatter.ofPattern(Modules.CHAT_TIME.format.get()));
        return Component.literal("[" + t + "] ").withStyle(ChatFormatting.GRAY).append(out);
    }
}
