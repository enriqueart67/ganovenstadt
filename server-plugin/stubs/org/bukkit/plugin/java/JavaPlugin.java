package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
    public final org.bukkit.Server getServer() { throw new UnsupportedOperationException(); }
    public final java.io.File getDataFolder() { throw new UnsupportedOperationException(); }
    public final java.util.logging.Logger getLogger() { throw new UnsupportedOperationException(); }
    public void onEnable() { }
    public void onDisable() { }
}
