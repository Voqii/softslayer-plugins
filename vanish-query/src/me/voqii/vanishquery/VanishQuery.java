package me.voqii.vanishquery;

import com.destroystokyo.paper.event.server.GS4QueryEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * Removes vanished players from the query-protocol player list (enable-query in server.properties).
 * Essentials already hides them from the server-list ping, but not from query, which status bots read.
 */
public final class VanishQuery extends JavaPlugin implements Listener {
    // Query is answered off the main thread, so it reads a snapshot taken on the main thread each second.
    private volatile Set<String> vanished = Set.of();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::snapshot, 0L, 20L);
    }

    private void snapshot() {
        vanished = Bukkit.getOnlinePlayers().stream()
                .filter(VanishQuery::isVanished)
                .map(Player::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The "vanished" metadata convention set by EssentialsX, SuperVanish/PremiumVanish and others. */
    static boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuery(GS4QueryEvent event) {
        Set<String> hidden = vanished;
        if (hidden.isEmpty()) return;
        GS4QueryEvent.QueryResponse response = event.getResponse();
        List<String> visible = response.getPlayers().stream().filter(name -> !hidden.contains(name)).toList();
        int removed = response.getPlayers().size() - visible.size();
        if (removed == 0) return;
        event.setResponse(response.toBuilder()
                .clearPlayers()
                .players(visible)
                .currentPlayers(Math.max(0, response.getCurrentPlayers() - removed))
                .build());
    }
}
