package com.voqii.discordmentions;

import com.voqii.discordmentions.MentionRewriter.Outcome;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides who "@Name" in game chat pings on Discord, on its way through DiscordSRV.
 *
 * A Minecraft name always wins: if anyone who has ever joined the server is called Name, only
 * their linked Discord account is pinged, and nobody is pinged if they haven't linked. Discord
 * names are only matched when no Minecraft player has that name, and an ambiguous match pings
 * nobody. Every other "@" is defused so DiscordSRV's own looser nickname matching can't guess.
 * The sender gets a private line saying who was pinged, or why nobody was. In-game chat is
 * untouched; only the copy sent to Discord changes.
 */
public final class DiscordMentions extends JavaPlugin implements Listener {

    /** Lowercase Minecraft name -> UUID, for everyone who has ever joined. */
    private final Map<String, UUID> knownPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) remember(p);
        // Reads every player's data file, so keep it off the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) remember(p);
            getLogger().info("Knows " + knownPlayers.size() + " Minecraft names.");
        });
        DiscordSRV.api.subscribe(this);
    }

    @Override
    public void onDisable() {
        DiscordSRV.api.unsubscribe(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    private void remember(OfflinePlayer p) {
        String name = p.getName();
        if (name != null) knownPlayers.put(name.toLowerCase(Locale.ROOT), p.getUniqueId());
    }

    @Subscribe
    @SuppressWarnings("deprecation") // legacy-string accessors avoid DiscordSRV's relocated Adventure types
    public void onGameChat(GameChatMessagePreProcessEvent event) {
        String message = event.getMessage();
        if (message.indexOf('@') < 0) return;
        Guild guild = DiscordSRV.getPlugin().getMainGuild();
        if (guild == null) return;

        Set<String> notes = new LinkedHashSet<>();
        String rewritten = MentionRewriter.rewrite(message, (token, after) -> resolve(token, after, guild, notes));
        if (rewritten != message) event.setMessage(rewritten);

        Player sender = event.getPlayer();
        if (sender != null && !event.isCancelled()) {
            for (String note : notes) sender.sendMessage(Component.text(note, NamedTextColor.GRAY));
        }
    }

    private Outcome resolve(String token, String after, Guild guild, Set<String> notes) {
        UUID uuid = knownPlayers.get(token.toLowerCase(Locale.ROOT));
        if (uuid == null) {
            Player online = Bukkit.getPlayerExact(token);
            if (online != null) uuid = online.getUniqueId();
        }
        if (uuid != null) return resolveMinecraft(token, uuid, guild, notes);
        return resolveDiscord(token, after, guild, notes);
    }

    /** The name belongs to a Minecraft player: their linked account or nobody. */
    private Outcome resolveMinecraft(String token, UUID uuid, Guild guild, Set<String> notes) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        String name = player.getName() != null ? player.getName() : token;

        String id = linkedDiscordId(uuid, player.isOnline());
        if (id == null) {
            notes.add(name + " hasn't linked their Discord (/discord link), so nobody was pinged.");
            return Outcome.DEFUSE;
        }
        Member member = guild.getMemberById(id);
        if (member == null) {
            notes.add(name + "'s linked Discord account isn't in the Discord server, so nobody was pinged.");
            return Outcome.DEFUSE;
        }
        notes.add("Pinged " + name + " on Discord (" + label(member) + ").");
        return Outcome.ping(id, token.length());
    }

    private static String linkedDiscordId(UUID uuid, boolean online) {
        AccountLinkManager links = DiscordSRV.getPlugin().getAccountLinkManager();
        if (links == null) return null;
        String id = links.getDiscordIdFromCache(uuid);
        // The uncached lookup can hit the database, so only do it off the main thread
        // (DiscordSRV fires this event from the async chat thread).
        if (id == null && !online && !Bukkit.isPrimaryThread()) id = links.getDiscordId(uuid);
        return id;
    }

    /**
     * No Minecraft player has this name: match a Discord username, a nickname with spaces and
     * symbols squeezed out ("@YosemiteSam"), or a nickname typed in full ("@Yosemite Sam").
     * The longest match wins; a tie between different people pings nobody.
     */
    private static Outcome resolveDiscord(String token, String after, Guild guild, Set<String> notes) {
        String lower = token.toLowerCase(Locale.ROOT);
        Member best = null;
        int bestLen = 0;
        boolean tie = false;
        for (Member member : guild.getMembers()) {
            int len = 0;
            String nick = member.getEffectiveName();
            if (nick.length() > token.length() && startsWithWord(after, nick)) {
                len = nick.length();
            } else if (member.getUser().getName().equalsIgnoreCase(token) || squeeze(nick).equals(lower)) {
                len = token.length();
            }
            if (len == 0 || len < bestLen) continue;
            if (len > bestLen) {
                best = member;
                bestLen = len;
                tie = false;
            } else if (!best.getId().equals(member.getId())) {
                tie = true;
            }
        }
        if (best == null) return Outcome.DEFUSE;
        if (tie) {
            notes.add("More than one Discord member matches @" + token + ", so nobody was pinged.");
            return Outcome.DEFUSE;
        }
        notes.add("Pinged " + label(best) + " on Discord.");
        return Outcome.ping(best.getId(), bestLen);
    }

    /** True if {@code text} starts with {@code word} (any case) and the word ends there. */
    private static boolean startsWithWord(String text, String word) {
        if (!text.regionMatches(true, 0, word, 0, word.length())) return false;
        if (text.length() == word.length()) return true;
        char next = text.charAt(word.length());
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    private static String label(Member member) {
        String user = member.getUser().getName();
        String nick = member.getEffectiveName();
        return nick.equalsIgnoreCase(user) ? "@" + user : nick + " / @" + user;
    }

    private static String squeeze(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]", "");
    }
}
