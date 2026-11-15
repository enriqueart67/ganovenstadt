package org.bukkit.scheduler;
public interface BukkitScheduler {
    BukkitTask runTaskLater(org.bukkit.plugin.Plugin plugin, Runnable task, long delay);
}
