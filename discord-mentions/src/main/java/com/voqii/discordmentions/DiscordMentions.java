package com.voqii.discordmentions;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

/**
 * DiscordSRV only turns "@Name" into a ping when Name is someone's Discord server nickname.
 * This resolves Minecraft names through DiscordSRV account links (and Discord usernames as a
 * fallback) so "@MinecraftName" in game chat pings the right Discord user. Only the copy sent
 * to Discord changes; in-game chat is untouched.
 */
public final class DiscordMentions extends JavaPlugin {

    @Override
    public void onEnable() {
        DiscordSRV.api.subscribe(this);
    }

    @Override
    public void onDisable() {
        DiscordSRV.api.unsubscribe(this);
    }

    @Subscribe
    @SuppressWarnings("deprecation") // legacy-string accessors avoid DiscordSRV's relocated Adventure types
    public void onGameChat(GameChatMessagePreProcessEvent event) {
        String message = event.getMessage();
        if (message.indexOf('@') < 0) return;
        Guild guild = DiscordSRV.getPlugin().getMainGuild();
        if (guild == null) return;

        String rewritten = MentionRewriter.rewrite(message, name -> resolve(name, guild));
        if (rewritten != message) event.setMessage(rewritten);
    }

    private String resolve(String name, Guild guild) {
        String id = linkedDiscordId(name);
        return id != null ? id : discordUserId(name, guild);
    }

    /** Minecraft name -> Discord id via DiscordSRV account linking. */
    private String linkedDiscordId(String name) {
        AccountLinkManager links = DiscordSRV.getPlugin().getAccountLinkManager();
        if (links == null) return null;

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return links.getDiscordIdFromCache(online.getUniqueId());

        // Offline players who have joined before. The non-cached lookup can hit the database,
        // so only do it off the main thread (DiscordSRV fires this event from the chat thread).
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline == null || Bukkit.isPrimaryThread()) return null;
        UUID uuid = offline.getUniqueId();
        return links.getDiscordId(uuid);
    }

    /**
     * Discord username, or a nickname with spaces/symbols squeezed out ("Yosemite Sam" -> @YosemiteSam).
     * DiscordSRV itself only matches the exact nickname. Ambiguous matches are skipped.
     */
    private static String discordUserId(String name, Guild guild) {
        String found = null;
        for (Member member : guild.getMembers()) {
            boolean match = member.getUser().getName().equalsIgnoreCase(name)
                    || squeeze(member.getEffectiveName()).equals(name.toLowerCase(Locale.ROOT));
            if (!match) continue;
            if (found != null && !found.equals(member.getId())) return null;
            found = member.getId();
        }
        return found;
    }

    private static String squeeze(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]", "");
    }
}
