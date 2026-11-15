package de.peregrine.grant;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server side of the Peregrine Client grant: tells the players listed in allowed.txt which Peregrine features this
 * server allows (plugin message "peregrine:features"). Install it on your own server; nothing else changes.
 *
 * allowed.txt (in plugins/PeregrineGrant/):
 *   features: blockesp,entityesp,assistant,aura      (or: all)
 *   YourName
 *   AnotherName      (one per line; "*" = everybody on the server)
 *   ban-without-code: true   (ban everyone whose Peregrine Client has not been unlocked with the access code)
 *
 * The client reports "unlocked"/"locked" on the channel peregrine:hello. Players without the Peregrine Client send
 * nothing and are left alone.
 */
public final class PeregrineGrant extends JavaPlugin implements Listener {
    static final String CHANNEL = "peregrine:features", HELLO = "peregrine:hello";
    private boolean banLocked = true;
    private String features = "all";
    private final List<String> players = new ArrayList<>();

    @Override
    public void onEnable() {
        load();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, HELLO, (channel, player, message) -> {
            String state = new String(message, StandardCharsets.UTF_8).trim();
            if (state.equals("locked") && banLocked) {
                getLogger().info("Banning " + player.getName() + ": Peregrine Client without the access code");
                getServer().dispatchCommand(getServer().getConsoleSender(),
                        "ban " + player.getName() + " Peregrine Client without access code");
            } else if (state.equals("unlocked")) grant(player);
        });
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers()) grant(p); // after a reload
        getLogger().info("Peregrine grant: " + features + " for " + (players.contains("*") ? "everybody" : players)
                + (banLocked ? "; Peregrine clients without the access code get banned" : ""));
    }

    private void load() {
        File dir = getDataFolder();
        File f = new File(dir, "allowed.txt");
        try {
            if (!f.exists()) {
                dir.mkdirs();
                Files.writeString(f.toPath(), """
                        # Peregrine Client grant – who may use the Peregrine features on this server.
                        # First line: the features (blockesp, entityesp, assistant, aura – or all).
                        features: all
                        # Ban players whose Peregrine Client is not unlocked with the access code (true/false).
                        ban-without-code: true
                        # Then one player name per line. * means everybody.
                        """, StandardCharsets.UTF_8);
            }
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                if (l.toLowerCase(Locale.ROOT).startsWith("features:")) features = l.substring(9).trim().replace(" ", "");
                else if (l.toLowerCase(Locale.ROOT).startsWith("ban-without-code:")) banLocked = l.substring(17).trim().equalsIgnoreCase("true");
                else players.add(l.toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            getLogger().warning("Could not read allowed.txt: " + e.getMessage());
        }
    }

    private boolean listed(Player p) {
        return players.contains("*") || players.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    private void grant(Player p) {
        if (!listed(p)) return;
        byte[] msg = features.getBytes(StandardCharsets.UTF_8);
        // the client announces its channels shortly after joining – send a few times so one arrives
        for (long delay : new long[]{20, 60, 140}) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) p.sendPluginMessage(this, CHANNEL, msg);
            }, delay);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        grant(e.getPlayer());
    }
}
