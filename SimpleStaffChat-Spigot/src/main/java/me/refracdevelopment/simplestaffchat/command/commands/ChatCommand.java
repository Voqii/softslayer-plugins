package me.refracdevelopment.simplestaffchat.command.commands;

import me.refracdevelopment.simplestaffchat.SimpleStaffChat;
import me.refracdevelopment.simplestaffchat.command.Command;
import me.refracdevelopment.simplestaffchat.utilities.Methods;
import me.refracdevelopment.simplestaffchat.utilities.chat.RyMessageUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatCommand extends Command {

    private final SimpleStaffChat plugin;

    public ChatCommand(SimpleStaffChat plugin) {
        super(plugin.getCommands().CHAT_COMMAND_ALIASES.get(0), plugin.getCommands().CHAT_COMMAND_PERMISSION, (String[]) (plugin.getCommands()).CHAT_COMMAND_ALIASES.toArray((Object[]) new String[0]));
        this.plugin = plugin;
    }

    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        // Make sure the sender is a player.
        if (!(sender instanceof Player player)) {
            RyMessageUtils.sendPluginMessage(sender, "no-console");
            return true;
        }

        if (!player.hasPermission(this.plugin.getCommands().CHAT_COMMAND_PERMISSION)) {
            RyMessageUtils.sendPluginMessage(player, "no-permission");
            return true;
        }

        String usage = "&c/" + getName() + " <staff|adult|dev|all>";

        if (args.length == 0) {
            RyMessageUtils.sendPlayer(player, usage);
            return true;
        }

        // "adult" is SoftSlayer's name for the admin channel; "admin" still works.
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "staff" -> Methods.toggleStaffChat(player);
            case "adult", "admin" -> Methods.toggleAdminChat(player);
            case "dev" -> Methods.toggleDevChat(player);
            case "all" -> Methods.toggleAllChat(player);
            default -> RyMessageUtils.sendPlayer(player, usage);
        }

        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1)
            return List.of();

        List<String> options = new ArrayList<>();
        if (this.plugin.getSettings().STAFFCHAT_ENABLED) options.add("staff");
        if (this.plugin.getSettings().ADMINCHAT_ENABLED) options.add("adult");
        if (this.plugin.getSettings().DEVCHAT_ENABLED) options.add("dev");
        options.add("all");

        String typed = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(typed)).toList();
    }
}
