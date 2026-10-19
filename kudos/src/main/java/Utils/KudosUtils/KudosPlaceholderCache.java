package Utils.KudosUtils;

import Utils.SQL.SQLGetter;
import de.urbance.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * voqii: PlaceholderAPI values served from memory.
 *
 * The expansion used to run the top-3 leaderboard query, up to three display-name lookups and the requested
 * count on every single placeholder request, each one on a freshly opened connection. InteractiveChat parses
 * the player-name hover once per name, per recipient, per chat line, so one message turned into hundreds of
 * blocking queries and chat stalled. Now requests only read these maps and all queries run on async threads.
 */
public class KudosPlaceholderCache {
    private static final long REFRESH_INTERVAL_TICKS = 20L * 60;

    private static final Map<UUID, Integer> receivedKudos = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> assignedKudos = new ConcurrentHashMap<>();
    private static final Set<UUID> pendingLoads = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private static final AtomicBoolean refreshRequested = new AtomicBoolean(false);
    private static volatile List<String> topPlayerKudos = Collections.emptyList();

    private KudosPlaceholderCache() {
    }

    public static void start(Main plugin) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, KudosPlaceholderCache::refreshAll, 0L, REFRESH_INTERVAL_TICKS);
    }

    public static void clear() {
        receivedKudos.clear();
        assignedKudos.clear();
        pendingLoads.clear();
        topPlayerKudos = Collections.emptyList();
    }

    public static int getReceivedKudos(UUID uuid) {
        Integer cached = receivedKudos.get(uuid);
        if (cached == null) {
            loadAsync(uuid);
            return 0;
        }
        return cached;
    }

    public static int getAssignedKudos(UUID uuid) {
        Integer cached = assignedKudos.get(uuid);
        if (cached == null) {
            loadAsync(uuid);
            return 0;
        }
        return cached;
    }

    public static List<String> getTopPlayerKudos() {
        return topPlayerKudos;
    }

    /** Call after anything that adds or removes kudos so placeholders catch up without waiting for the timer. */
    public static void invalidate() {
        Main plugin = Main.getPlugin(Main.class);
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, KudosPlaceholderCache::refreshAll);
    }

    public static void loadAsync(UUID uuid) {
        Main plugin = Main.getPlugin(Main.class);
        if (!plugin.isEnabled() || !pendingLoads.add(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                load(uuid);
            } finally {
                pendingLoads.remove(uuid);
            }
        });
    }

    private static void load(UUID uuid) {
        SQLGetter data = new SQLGetter(Main.getPlugin(Main.class));
        receivedKudos.put(uuid, data.getAmountKudos(uuid));
        assignedKudos.put(uuid, data.getAssignedKudos(uuid));
    }

    private static void refreshAll() {
        refreshRequested.set(true);
        // Only one refresh at a time; a request that lands mid-refresh makes it go round once more
        while (refreshRequested.get() && refreshRunning.compareAndSet(false, true)) {
            try {
                while (refreshRequested.getAndSet(false)) refresh();
            } finally {
                refreshRunning.set(false);
            }
        }
    }

    private static void refresh() {
        // Online players plus anyone a placeholder has asked about (leaderboard plugins etc.) since startup
        Set<UUID> players = ConcurrentHashMap.newKeySet();
        players.addAll(receivedKudos.keySet());
        for (Player player : Bukkit.getOnlinePlayers()) players.add(player.getUniqueId());

        for (UUID uuid : players) load(uuid);
        topPlayerKudos = List.copyOf(new SQLGetter(Main.getPlugin(Main.class)).getTopPlayersKudosTemp());
    }
}
