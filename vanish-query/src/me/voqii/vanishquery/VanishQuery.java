package me.voqii.vanishquery;

import com.destroystokyo.paper.event.server.GS4QueryEvent;
import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shapes the query-protocol player list (enable-query in server.properties), which status bots read:
 * - vanished players are left out (Essentials already hides them from the server-list ping, but not from query);
 * - each entry can carry extra fields after "|", which can't appear in a Java or Bedrock username:
 *   "YosemiteSamson|n:Sam|afk" = nickname Sam, AFK. A plain "Name" means no nickname and not AFK.
 */
public final class VanishQuery extends JavaPlugin implements Listener {
    // Hex (&#rrggbb, §x§r§r§g§g§b§b) and legacy (&c, §l) colour/format codes in Essentials nicknames
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[&§]#[0-9a-f]{6}|§x(§[0-9a-f]){6}|[&§][0-9a-fk-or]");

    // Query is answered off the main thread, so it reads snapshots taken on the main thread each second.
    private volatile Set<String> vanished = Set.of();
    private volatile Map<String, String> entries = Map.of();
    private Essentials essentials;

    @Override
    public void onEnable() {
        Plugin ess = getServer().getPluginManager().getPlugin("Essentials");
        if (ess instanceof Essentials e) {
            essentials = e;
        } else {
            getLogger().info("EssentialsX not found: no AFK or nickname info in the query list.");
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::snapshot, 0L, 20L);
    }

    private void snapshot() {
        Set<String> hidden = new java.util.HashSet<>();
        Map<String, String> decorated = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isVanished(player)) {
                hidden.add(player.getName());
                continue;
            }
            String entry = entryFor(player);
            if (!entry.equals(player.getName())) decorated.put(player.getName(), entry);
        }
        vanished = Set.copyOf(hidden);
        entries = Map.copyOf(decorated);
    }

    private String entryFor(Player player) {
        if (essentials == null) return player.getName();
        User user = essentials.getUser(player);
        if (user == null) return player.getName();
        StringBuilder entry = new StringBuilder(player.getName());
        String nick = cleanNick(user.getNickname());
        if (nick != null && !nick.equalsIgnoreCase(player.getName())) entry.append("|n:").append(nick);
        if (user.isAfk()) entry.append("|afk");
        return entry.toString();
    }

    /** Plain-text nickname, or null when there isn't a usable one. */
    static String cleanNick(String raw) {
        if (raw == null) return null;
        String nick = COLOR_CODES.matcher(raw).replaceAll("").replace("|", "").strip();
        return nick.isEmpty() ? null : nick;
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
        Map<String, String> extra = entries;
        if (hidden.isEmpty() && extra.isEmpty()) return;
        GS4QueryEvent.QueryResponse response = event.getResponse();
        List<String> shown = new ArrayList<>();
        for (String name : response.getPlayers()) {
            if (!hidden.contains(name)) shown.add(extra.getOrDefault(name, name));
        }
        int removed = response.getPlayers().size() - shown.size();
        event.setResponse(response.toBuilder()
                .clearPlayers()
                .players(shown)
                .currentPlayers(Math.max(0, response.getCurrentPlayers() - removed))
                .build());
    }
}
