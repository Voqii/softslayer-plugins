package com.voqii.timingghost;

import com.destroystokyo.paper.profile.ProfileProperty;
import me.makkuusen.timing.system.api.TimingSystemAPI;
import me.makkuusen.timing.system.api.events.TimeTrialFinishEvent;
import me.makkuusen.timing.system.api.events.TimeTrialStartEvent;
import me.makkuusen.timing.system.timetrial.TimeTrial;
import me.makkuusen.timing.system.timetrial.TimeTrialController;
import me.makkuusen.timing.system.track.Track;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TimingGhost extends JavaPlugin implements Listener {

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final double[] scratch = new double[4];
    private NamespacedKey modeKey;
    private GhostStore store;
    private Config config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = Config.load(getConfig(), getLogger());
        modeKey = new NamespacedKey(this, "mode");
        store = new GhostStore(this);
        store.loadIndex();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        sessions.values().forEach(Session::removeGhost);
        sessions.clear();
        if (store != null) {
            store.close();
        }
    }

    Config config() {
        return config;
    }

    /**
     * One racer's current time trial. TimingSystem reuses the same TimeTrial for back-to-back laps on
     * tracks without a finish line (it fires a finish but no new start), so a new run is spotted by the
     * timer going backwards rather than by events alone.
     */
    private final class Session {
        final Player player;
        final TimeTrial timeTrial;
        final Track track;
        GhostRecording recording;
        GhostRenderer ghost;
        long lastElapsed = Long.MIN_VALUE;
        int run;

        Session(Player player, TimeTrial timeTrial) {
            this.player = player;
            this.timeTrial = timeTrial;
            this.track = timeTrial.getTrack();
        }

        void startRun() {
            run++;
            lastElapsed = Long.MIN_VALUE;
            recording = new GhostRecording(track.getId(), player.getUniqueId(), player.getName(),
                    player.getWorld().getName(), track.isBoatTrack(), 20 * 60);
            removeGhost();
            loadGhost();
        }

        void loadGhost() {
            if (!player.hasPermission("timingghost.use")) {
                return;
            }
            UUID owner = switch (modeOf(player)) {
                case OFF -> null;
                case PB -> player.getUniqueId();
                case BEST -> store.fastestOwner(track.getId());
            };
            if (owner == null) {
                return;
            }
            int forRun = run;
            store.load(track.getId(), owner).thenAccept(rec -> {
                if (rec != null && rec.size() > 0 && sessions.get(player.getUniqueId()) == this && run == forRun) {
                    ghost = new GhostRenderer(TimingGhost.this, player, rec);
                }
            });
        }

        void record(long elapsed) {
            if (recording == null || elapsed > config.maxRunSeconds() * 1000L) {
                recording = null; // too long to keep; this run won't become a ghost
                return;
            }
            Entity body = player.getVehicle() != null ? player.getVehicle() : player;
            Location loc = body.getLocation();
            recording.add(Math.max(0, elapsed), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw());
        }

        void removeGhost() {
            if (ghost != null) {
                ghost.remove();
                ghost = null;
            }
        }
    }

    private void tick() {
        var it = sessions.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            TimeTrial current = TimeTrialController.timeTrials.get(s.player.getUniqueId());
            if (!s.player.isOnline() || current != s.timeTrial) {
                // Run ended (finished, reset, quit). A new TimeTrial gets its own session from the start event.
                s.removeGhost();
                it.remove();
                continue;
            }
            long elapsed = current.getCurrentTime();
            if (elapsed < s.lastElapsed) {
                s.startRun(); // next lap on a start-is-finish track
            }
            s.lastElapsed = elapsed;
            s.record(elapsed);
            if (s.ghost != null) {
                s.ghost.showAt(elapsed + GhostRenderer.smoothingLagMillis(), scratch);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStart(TimeTrialStartEvent event) {
        Player player = event.getPlayer();
        Session old = sessions.remove(player.getUniqueId());
        if (old != null) {
            old.removeGhost();
        }
        Session s = new Session(player, event.getTimeTrial());
        sessions.put(player.getUniqueId(), s);
        s.startRun();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFinish(TimeTrialFinishEvent event) {
        Player player = event.getPlayer();
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.timeTrial != event.getTimeTrial() || s.recording == null) {
            return;
        }
        long time = event.getTimeTrialFinish().getTime();
        GhostRecording rec = s.recording;
        s.recording = null;
        rec.finishTime = time;
        Entity body = player.getVehicle() != null ? player.getVehicle() : player;
        Location end = body.getLocation();
        rec.add(time, end.getX(), end.getY(), end.getZ(), end.getYaw());

        Long previous = store.bestTime(s.track.getId(), player.getUniqueId());
        if (previous == null || time < previous) {
            for (ProfileProperty p : player.getPlayerProfile().getProperties()) {
                if (p.getName().equals("textures")) {
                    rec.skinValue = p.getValue();
                    rec.skinSignature = p.getSignature();
                }
            }
            store.save(rec);
            if (player.hasPermission("timingghost.use")) {
                player.sendMessage(Component.text("Ghost saved: " + formatTime(time) + " on " + s.track.getDisplayName()
                        + ". You'll race it next time.", NamedTextColor.AQUA));
            }
        }
        // On start-is-finish tracks the same TimeTrial keeps going; tick() starts the next lap's recording.
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            s.removeGhost();
        }
    }

    private Config.Mode modeOf(Player player) {
        Config.Mode mode = Config.Mode.parse(player.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING));
        return mode != null ? mode : config.defaultMode();
    }

    static String formatTime(long ms) {
        if (ms < 0) {
            return "-";
        }
        return String.format("%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "off", "pb", "best" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can pick a ghost.");
                    return true;
                }
                if (!player.hasPermission("timingghost.use")) {
                    player.sendMessage(Component.text("You don't have permission to use ghosts.", NamedTextColor.RED));
                    return true;
                }
                Config.Mode mode = Config.Mode.parse(sub);
                player.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.name());
                player.sendMessage(Component.text(switch (mode) {
                    case OFF -> "Ghosts off.";
                    case PB -> "You'll race your own best run.";
                    case BEST -> "You'll race the fastest ghost on each track.";
                }, NamedTextColor.AQUA));
                Session s = sessions.get(player.getUniqueId());
                if (s != null) {
                    s.removeGhost();
                    s.loadGhost();
                }
            }
            case "clear" -> clear(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("timingghost.admin")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                reloadConfig();
                config = Config.load(getConfig(), getLogger());
                store.flush();
                store.loadIndex();
                sender.sendMessage(Component.text("TimingGhost reloaded.", NamedTextColor.AQUA));
            }
            default -> {
                if (sender instanceof Player player) {
                    sender.sendMessage(Component.text("Ghost: " + modeOf(player).name().toLowerCase()
                            + ". Use /ttghost off, /ttghost pb (your best run) or /ttghost best (fastest on the track).", NamedTextColor.AQUA));
                } else {
                    sender.sendMessage("/ttghost clear <track> [player], /ttghost reload");
                }
            }
        }
        return true;
    }

    private void clear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("timingghost.admin")) {
            sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/ttghost clear <track> [player]  (no player = every ghost on the track)", NamedTextColor.RED));
            return;
        }
        var track = TimingSystemAPI.getTrack(args[1]);
        if (track.isEmpty()) {
            sender.sendMessage(Component.text("No track called " + args[1] + ".", NamedTextColor.RED));
            return;
        }
        UUID owner = null;
        if (args.length >= 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Never seen a player called " + args[2] + ".", NamedTextColor.RED));
                return;
            }
            owner = target.getUniqueId();
        }
        int removed = store.delete(track.get().getId(), owner);
        sender.sendMessage(Component.text("Removed " + removed + " ghost(s) from " + track.get().getDisplayName() + ".", NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("off", "pb", "best"));
            if (sender.hasPermission("timingghost.admin")) {
                options.addAll(List.of("clear", "reload"));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("clear") && sender.hasPermission("timingghost.admin")) {
            TimingSystemAPI.getTracks().forEach(t -> options.add(t.getCommandName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("clear") && sender.hasPermission("timingghost.admin")) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        }
        String prefix = args[args.length - 1].toLowerCase();
        options.removeIf(o -> !o.toLowerCase().startsWith(prefix));
        return options;
    }
}
