package de.peregrine.client.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Erlaubt Helligkeit ueber 100 % (Fullbright), ohne die Begrenzung von Minecraft. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("value")
    void peregrine$setValue(Object value);
}
