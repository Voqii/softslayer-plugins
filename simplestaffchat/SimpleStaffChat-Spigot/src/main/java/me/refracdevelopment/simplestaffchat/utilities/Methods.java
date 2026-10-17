package me.refracdevelopment.simplestaffchat.utilities;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import me.refracdevelopment.simplestaffchat.SimpleStaffChat;
import me.refracdevelopment.simplestaffchat.utilities.chat.RyMessageUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@UtilityClass
public final class Methods {

    // Read from the async chat thread and written from commands on the main thread.
    @Getter
    private final Set<UUID> staffChatMuted = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<UUID> adminChatMuted = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<UUID> devChatMuted = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<UUID> staffChatPlayers = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<UUID> adminChatPlayers = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<UUID> devChatPlayers = ConcurrentHashMap.newKeySet();

    /**
     * @param format  The channel format; %message% is filled in safely by {@link RyMessageUtils#formatChat}.
     * @param message What the sender typed.
     */
    public void sendStaffChat(CommandSender commandSender, String format, String message) {
        if (isMuted(commandSender, staffChatMuted, "staffchat-muted"))
            return;

        send(commandSender, Permissions.STAFFCHAT_SEE, staffChatMuted, format, message);
        DiscordImpl.sendStaffChat(commandSender, message);
    }

    public void sendDevChat(CommandSender commandSender, String format, String message) {
        if (isMuted(commandSender, devChatMuted, "devchat-muted"))
            return;

        send(commandSender, Permissions.DEVCHAT_SEE, devChatMuted, format, message);
        DiscordImpl.sendDevChat(commandSender, message);
    }

    // "Admin chat" is SoftSlayer's 18+ adult chat; only the names in code/config keys still say admin.
    public void sendAdminChat(CommandSender commandSender, String format, String message) {
        if (isMuted(commandSender, adminChatMuted, "adminchat-muted"))
            return;

        send(commandSender, Permissions.ADMINCHAT_SEE, adminChatMuted, format, message);
        DiscordImpl.sendAdminChat(commandSender, message);
    }

    private boolean isMuted(CommandSender commandSender, Set<UUID> muted, String mutedMessage) {
        if (commandSender instanceof Player player && muted.contains(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, mutedMessage);
            return true;
        }

        return false;
    }

    private void send(CommandSender commandSender, String permission, Set<UUID> muted, String format, String message) {
        String name = commandSender instanceof Player ? commandSender.getName() : "Console";

        format = format
                .replace("%server%", SimpleStaffChat.getInstance().getSettings().SERVER_NAME)
                .replace("%player%", name);

        RyMessageUtils.sendChannel(commandSender, permission, muted, RyMessageUtils.formatChat(commandSender, format, message));
    }

    public void toggleStaffChat(Player player) {
        if (staffChatPlayers.remove(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, "staffchat-toggle-off");
        } else {
            adminChatPlayers.remove(player.getUniqueId());
            devChatPlayers.remove(player.getUniqueId());

            staffChatPlayers.add(player.getUniqueId());
            RyMessageUtils.sendPluginMessage(player, "staffchat-toggle-on");
        }
    }

    public void toggleAdminChat(Player player) {
        if (adminChatPlayers.remove(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, "adminchat-toggle-off");
        } else {
            devChatPlayers.remove(player.getUniqueId());
            staffChatPlayers.remove(player.getUniqueId());

            adminChatPlayers.add(player.getUniqueId());
            RyMessageUtils.sendPluginMessage(player, "adminchat-toggle-on");
        }
    }

    public void toggleDevChat(Player player) {
        if (devChatPlayers.remove(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, "devchat-toggle-off");
        } else {
            adminChatPlayers.remove(player.getUniqueId());
            staffChatPlayers.remove(player.getUniqueId());

            devChatPlayers.add(player.getUniqueId());
            RyMessageUtils.sendPluginMessage(player, "devchat-toggle-on");
        }
    }

    // Back to normal chat. Always confirm, even if no channel was toggled.
    public void toggleAllChat(Player player) {
        staffChatPlayers.remove(player.getUniqueId());
        devChatPlayers.remove(player.getUniqueId());
        adminChatPlayers.remove(player.getUniqueId());

        RyMessageUtils.sendPluginMessage(player, "allchat-toggle-on");
    }

    // Hiding one channel no longer un-hides the others (upstream cleared them).
    public void hideStaffChat(Player player) {
        if (staffChatMuted.remove(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, "staffchat-muted-off");
        } else {
            staffChatMuted.add(player.getUniqueId());
            RyMessageUtils.sendPluginMessage(player, "staffchat-muted-on");
        }
    }

    public void hideAdminChat(Player player) {
        if (adminChatMuted.remove(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, "adminchat-muted-off");
        } else {
            adminChatMuted.add(player.getUniqueId());
            RyMessageUtils.sendPluginMessage(player, "adminchat-muted-on");
        }
    }

    public void hideDevChat(Player player) {
        if (devChatMuted.remove(player.getUniqueId())) {
            RyMessageUtils.sendPluginMessage(player, "devchat-muted-off");
        } else {
            devChatMuted.add(player.getUniqueId());
            RyMessageUtils.sendPluginMessage(player, "devchat-muted-on");
        }
    }

    // Everything hidden -> show everything; otherwise hide everything.
    public void hideAll(Player player) {
        UUID uuid = player.getUniqueId();

        if (staffChatMuted.contains(uuid) && adminChatMuted.contains(uuid) && devChatMuted.contains(uuid)) {
            staffChatMuted.remove(uuid);
            adminChatMuted.remove(uuid);
            devChatMuted.remove(uuid);

            RyMessageUtils.sendPluginMessage(player, "allchat-muted-off");
        } else {
            staffChatMuted.add(uuid);
            adminChatMuted.add(uuid);
            devChatMuted.add(uuid);

            RyMessageUtils.sendPluginMessage(player, "allchat-muted-on");
        }
    }
}
