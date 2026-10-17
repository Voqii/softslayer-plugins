package me.refracdevelopment.simplestaffchat.command.commands;

import me.refracdevelopment.simplestaffchat.SimpleStaffChat;
import me.refracdevelopment.simplestaffchat.command.Command;
import me.refracdevelopment.simplestaffchat.utilities.Permissions;
import me.refracdevelopment.simplestaffchat.utilities.chat.RyMessageUtils;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReloadCommand extends Command {

    private final SimpleStaffChat plugin;

    public ReloadCommand(SimpleStaffChat plugin) {
        // Upstream ignored commands.reload in commands.yml.
        super(reloadAliases(plugin).get(0), Permissions.STAFFCHAT_RELOAD, reloadAliases(plugin).toArray(new String[0]));
        this.plugin = plugin;
    }

    private static List<String> reloadAliases(SimpleStaffChat plugin) {
        // Keep the two names upstream hard-coded so nobody's /screload stops working.
        Set<String> aliases = new LinkedHashSet<>(List.of("staffchatreload", "screload"));
        List<String> configured = plugin.getCommandsFile().getStringList("commands.reload.aliases");
        if (configured != null)
            aliases.addAll(configured);
        return new ArrayList<>(aliases);
    }

    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (!sender.hasPermission(Permissions.STAFFCHAT_RELOAD)) {
            RyMessageUtils.sendPluginMessage(sender, "no-permission");
            return true;
        }

        reloadFiles();
        RyMessageUtils.sendPluginMessage(sender, "reload");
        return true;
    }


    private void reloadFiles() {
        this.plugin.getConfigFile().reload();
        this.plugin.getCommandsFile().reload();
        this.plugin.getDiscordFile().reload();
        this.plugin.getLocaleFile().reload();

        this.plugin.getSettings().loadConfig();
        this.plugin.getCommands().loadConfig();
        this.plugin.getDiscord().loadConfig();

        RyMessageUtils.sendConsole(true, "&aReloaded all files.");
    }
}