package org.bukkit;
public interface Server {
    org.bukkit.plugin.PluginManager getPluginManager();
    org.bukkit.plugin.messaging.Messenger getMessenger();
    org.bukkit.scheduler.BukkitScheduler getScheduler();
    java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers();
    org.bukkit.command.ConsoleCommandSender getConsoleSender();
    boolean dispatchCommand(org.bukkit.command.CommandSender sender, String commandLine);
}
