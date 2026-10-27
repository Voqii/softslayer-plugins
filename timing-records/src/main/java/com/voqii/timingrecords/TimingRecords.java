package com.voqii.timingrecords;

import me.makkuusen.timing.system.ApiUtilities;
import me.makkuusen.timing.system.api.events.TimeTrialFinishEvent;
import me.makkuusen.timing.system.timetrial.TimeTrialFinish;
import me.makkuusen.timing.system.track.Track;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TimingRecords extends JavaPlugin implements Listener {

    private final MiniMessage mm = MiniMessage.miniMessage();

    private String newRecordMessage;
    private String ownRecordMessage;
    private String firstFinishMessage;
    private boolean announceFirstFinish;
    private boolean announceOwnRecord;
    private Sound sound;
    private final Set<String> ignoredTracks = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        newRecordMessage = c.getString("messages.new-record", "");
        ownRecordMessage = c.getString("messages.own-record", "");
        firstFinishMessage = c.getString("messages.first-finish", "");
        announceFirstFinish = c.getBoolean("announce.first-finish", false);
        announceOwnRecord = c.getBoolean("announce.own-record", true);

        sound = null;
        if (c.getBoolean("sound.enabled", true)) {
            String key = c.getString("sound.key", "ui.toast.challenge_complete");
            try {
                sound = Sound.sound(Key.key(key), Sound.Source.MASTER,
                        (float) c.getDouble("sound.volume", 0.6), (float) c.getDouble("sound.pitch", 1.0));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid sound.key '" + key + "', record sound disabled.");
            }
        }

        ignoredTracks.clear();
        for (String name : c.getStringList("ignored-tracks")) {
            ignoredTracks.add(name.toLowerCase(Locale.ROOT));
        }
    }

    // MONITOR: TimingSystem has already saved the finish into the track's times when this fires,
    // so the track's top list already includes it.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFinish(TimeTrialFinishEvent event) {
        if (!event.isNewBestTime()) {
            return;
        }
        Track track = event.getTimeTrial().getTrack();
        if (isIgnored(track)) {
            return;
        }
        TimeTrialFinish finish = event.getTimeTrialFinish();
        if (finish == null) {
            return;
        }

        // Top list holds each player's best, sorted by time then date (an equal time set later loses).
        List<TimeTrialFinish> top = track.getTimeTrials().getTopList(2);
        if (top.isEmpty() || top.get(0).getId() != finish.getId()) {
            return;
        }
        TimeTrialFinish runnerUp = top.size() > 1 ? top.get(1) : null;
        long oldBest = event.getOldBestTime();
        // The finish's TimingSystem player rather than event.getPlayer(), which is only the Bukkit side.
        String player = finish.getPlayer().getName();

        if (oldBest > 0 && (runnerUp == null || oldBest < runnerUp.getTime())) {
            // Their old best was still ahead of everyone else, so they held the record.
            if (announceOwnRecord) {
                announce(ownRecordMessage, player, track, finish.getTime(), player, oldBest);
            }
        } else if (runnerUp != null) {
            announce(newRecordMessage, player, track, finish.getTime(), runnerUp.getPlayer().getName(), runnerUp.getTime());
        } else if (announceFirstFinish) {
            announce(firstFinishMessage, player, track, finish.getTime(), "", -1L);
        }
    }

    private boolean isIgnored(Track track) {
        return ignoredTracks.contains(track.getCommandName().toLowerCase(Locale.ROOT))
                || ignoredTracks.contains(track.getDisplayName().toLowerCase(Locale.ROOT));
    }

    private void announce(String template, String player, Track track, long time, String previousHolder, long previousTime) {
        if (template == null || template.isEmpty()) {
            return;
        }
        // Placeholders are inserted unparsed, so names can't inject MiniMessage tags.
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("track", track.getDisplayName()),
                Placeholder.unparsed("time", ApiUtilities.formatAsTime(time)),
                Placeholder.unparsed("previous_holder", previousHolder),
                Placeholder.unparsed("previous_time", previousTime > 0 ? ApiUtilities.formatAsTime(previousTime) : "-"),
                Placeholder.unparsed("delta", previousTime > 0 ? formatDelta(previousTime - time) : "-"));
        Component message = mm.deserialize(template, resolver);
        Bukkit.broadcast(message);
        if (sound != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(sound, Sound.Emitter.self());
            }
        }
    }

    // 412 -> "0.412", 75012 -> "75.012"
    static String formatDelta(long millis) {
        return (millis / 1000) + "." + String.format("%03d", millis % 1000);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage(Component.text("TimingRecords reloaded."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            // Shows what the new-record message looks like, to the sender only.
            TagResolver resolver = TagResolver.resolver(
                    Placeholder.unparsed("player", sender.getName()),
                    Placeholder.unparsed("track", "Example Track"),
                    Placeholder.unparsed("time", ApiUtilities.formatAsTime(62341)),
                    Placeholder.unparsed("previous_holder", "Someone"),
                    Placeholder.unparsed("previous_time", ApiUtilities.formatAsTime(62753)),
                    Placeholder.unparsed("delta", formatDelta(412)));
            sender.sendMessage(mm.deserialize(newRecordMessage, resolver));
            sender.sendMessage(mm.deserialize(ownRecordMessage, resolver));
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("reload", "test") : List.of();
    }
}
