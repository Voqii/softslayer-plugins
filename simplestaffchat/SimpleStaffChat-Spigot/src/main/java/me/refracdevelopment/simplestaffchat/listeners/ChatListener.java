package me.refracdevelopment.simplestaffchat.listeners;

import me.refracdevelopment.simplestaffchat.SimpleStaffChat;
import me.refracdevelopment.simplestaffchat.manager.config.cache.Config;
import me.refracdevelopment.simplestaffchat.utilities.Methods;
import me.refracdevelopment.simplestaffchat.utilities.Permissions;
import me.refracdevelopment.simplestaffchat.utilities.chat.RyMessageUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class ChatListener implements Listener {

    private final SimpleStaffChat plugin;

    public ChatListener(SimpleStaffChat plugin) {
        this.plugin = plugin;
    }

    private enum Channel {STAFF, ADMIN, DEV}

    // One handler instead of three: upstream's three LOWEST handlers could each grab the same message
    // (e.g. toggled into staff chat and typing "#hi" sent it to staff AND adult chat).
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Config settings = this.plugin.getSettings();
        String message = event.getMessage();

        if (message.startsWith("/"))
            return;

        // Toggled channel first, same order as upstream.
        if (settings.CHAT_TOGGLE_ENABLED) {
            if (Methods.getStaffChatPlayers().contains(uuid)) {
                if (stillAllowed(player, Methods.getStaffChatPlayers(), this.plugin.getCommands().STAFF_TOGGLE_COMMAND_PERMISSION, "staffchat-toggle-off"))
                    send(event, Channel.STAFF, message);
                return;
            }
            if (Methods.getAdminChatPlayers().contains(uuid)) {
                if (stillAllowed(player, Methods.getAdminChatPlayers(), this.plugin.getCommands().ADMIN_TOGGLE_COMMAND_PERMISSION, "adminchat-toggle-off"))
                    send(event, Channel.ADMIN, message);
                return;
            }
            if (Methods.getDevChatPlayers().contains(uuid)) {
                if (stillAllowed(player, Methods.getDevChatPlayers(), this.plugin.getCommands().DEV_TOGGLE_COMMAND_PERMISSION, "devchat-toggle-off"))
                    send(event, Channel.DEV, message);
                return;
            }
        }

        if (!settings.SYMBOLS_ENABLED)
            return;

        if (trySymbol(event, player, settings.STAFFCHAT_SYMBOL, Permissions.STAFFCHAT_SYMBOL, settings.STAFFCHAT_ENABLED, Channel.STAFF))
            return;
        if (trySymbol(event, player, settings.ADMINCHAT_SYMBOL, Permissions.ADMINCHAT_SYMBOL, settings.ADMINCHAT_ENABLED, Channel.ADMIN))
            return;
        trySymbol(event, player, settings.DEVCHAT_SYMBOL, Permissions.DEVCHAT_SYMBOL, settings.DEVCHAT_ENABLED, Channel.DEV);
    }

    // Lost the permission while toggled in -> drop back to public chat (message goes out normally).
    private boolean stillAllowed(Player player, Set<UUID> toggled, String permission, String offMessage) {
        if (player.hasPermission(permission))
            return true;

        toggled.remove(player.getUniqueId());
        RyMessageUtils.sendPluginMessage(player, offMessage);
        return false;
    }

    private boolean trySymbol(AsyncPlayerChatEvent event, Player player, String symbol, String permission, boolean moduleEnabled, Channel channel) {
        String message = event.getMessage();

        if (!moduleEnabled || symbol == null || symbol.isEmpty() || !message.startsWith(symbol) || !player.hasPermission(permission))
            return false;

        // Plain substring: upstream used replaceFirst, which treats the symbol as a regex.
        String text = message.substring(symbol.length()).trim();
        if (text.isEmpty())
            return false;

        send(event, channel, text);
        return true;
    }

    private void send(AsyncPlayerChatEvent event, Channel channel, String message) {
        event.setCancelled(true);

        Player player = event.getPlayer();
        Config settings = this.plugin.getSettings();

        switch (channel) {
            case STAFF -> Methods.sendStaffChat(player, settings.STAFFCHAT_FORMAT, message);
            case ADMIN -> Methods.sendAdminChat(player, settings.ADMINCHAT_FORMAT, message);
            case DEV -> Methods.sendDevChat(player, settings.DEVCHAT_FORMAT, message);
        }
    }
}
