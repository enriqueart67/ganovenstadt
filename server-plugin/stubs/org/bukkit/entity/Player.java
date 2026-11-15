package org.bukkit.entity;
public interface Player {
    String getName();
    void sendPluginMessage(org.bukkit.plugin.Plugin source, String channel, byte[] message);
    boolean isOnline();
}
