package me.refracdevelopment.simplestaffchat.command.commands.adminchat;

import com.google.common.base.Joiner;
import me.refracdevelopment.simplestaffchat.SimpleStaffChat;
import me.refracdevelopment.simplestaffchat.command.Command;
import me.refracdevelopment.simplestaffchat.utilities.Methods;
import me.refracdevelopment.simplestaffchat.utilities.chat.RyMessageUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class AdminChatCommand extends Command {

    private final SimpleStaffChat plugin;

    public AdminChatCommand(SimpleStaffChat plugin) {
        super(plugin.getCommands().ADMINCHAT_COMMAND_ALIASES.get(0), plugin.getCommands().ADMINCHAT_COMMAND_PERMISSION, (String[]) (plugin.getCommands()).ADMINCHAT_COMMAND_ALIASES.toArray((Object[]) new String[0]));
        this.plugin = plugin;
    }

    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        String message = Joiner.on(" ").join(args);

        if (!sender.hasPermission(this.plugin.getCommands().ADMINCHAT_COMMAND_PERMISSION)) {
            RyMessageUtils.sendPluginMessage(sender, "no-permission");
            return true;
        }

        if (!(args.length >= 1)) {
            RyMessageUtils.sendSender(sender, "&cUsage: /" + this.getName() + " <message>");
            return true;
        }

        String format = sender instanceof Player
                ? this.plugin.getSettings().ADMINCHAT_FORMAT
                : this.plugin.getSettings().CONSOLE_ADMINCHAT_FORMAT;

        Methods.sendAdminChat(sender, format, message);

        return true;
    }
}