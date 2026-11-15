package de.peregrine.client.module;

import de.peregrine.client.setting.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Ein Client-Mod-Modul, das im Menue (Rechts-Shift) an-/ausgeschaltet und eingestellt wird. */
public abstract class Module {
    public final String id;
    public final String name;
    public final String description;
    public final Category category;
    public final boolean defaultEnabled;
    public boolean enabled;
    public final List<Setting<?>> settings = new ArrayList<>();

    protected Module(String id, String name, String description, Category category, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.defaultEnabled = enabled;
        this.enabled = enabled;
    }

    protected <S extends Setting<?>> S add(S s) {
        settings.add(s);
        return s;
    }

    public void toggle() {
        enabled = !enabled;
        onToggle();
    }

    protected void onToggle() {
    }

    /** Jeden Spiel-Tick (auch wenn ausgeschaltet – fuer Aufraeumarbeiten). */
    public void tick(Minecraft mc) {
    }

    /** Optional own settings screen (e.g. the block picker of Block ESP); null = only the normal settings. */
    public net.minecraft.client.gui.screens.Screen editor(net.minecraft.client.gui.screens.Screen parent) {
        return null;
    }

    public void reset() {
        enabled = defaultEnabled;
        settings.forEach(Setting::reset);
    }
}
