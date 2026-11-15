package org.bukkit.plugin.messaging;
public interface PluginMessageListener {
    void onPluginMessageReceived(String channel, org.bukkit.entity.Player player, byte[] message);
}
