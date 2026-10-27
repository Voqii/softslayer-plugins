package me.refracdevelopment.simplestaffchat.manager;

import me.refracdevelopment.simplestaffchat.SimpleStaffChat;
import me.refracdevelopment.simplestaffchat.command.Command;
import me.refracdevelopment.simplestaffchat.command.commands.*;
import me.refracdevelopment.simplestaffchat.command.commands.adminchat.AdminChatCommand;
import me.refracdevelopment.simplestaffchat.command.commands.adminchat.AdminToggleCommand;
import me.refracdevelopment.simplestaffchat.command.commands.devchat.DevChatCommand;
import me.refracdevelopment.simplestaffchat.command.commands.devchat.DevToggleCommand;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class CommandManager {
    private final Set<Command> commands = new HashSet<>();
    private final SimpleStaffChat plugin;

    public CommandManager(SimpleStaffChat plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        if (this.plugin.getSettings().STAFFCHAT_ENABLED) {
            this.commands.add(new StaffChatCommand(this.plugin));
        }

        if (this.plugin.getSettings().DEVCHAT_ENABLED) {
            this.commands.add(new DevChatCommand(this.plugin));
        }

        if (this.plugin.getSettings().ADMINCHAT_ENABLED) {
            this.commands.add(new AdminChatCommand(this.plugin));
        }

        // Toggles only for channels that exist (upstream registered /devchattoggle etc. with devchat off).
        if ((this.plugin.getSettings()).CHAT_TOGGLE_ENABLED) {
            if (this.plugin.getSettings().STAFFCHAT_ENABLED)
                this.commands.add(new ToggleCommand(this.plugin));
            if (this.plugin.getSettings().ADMINCHAT_ENABLED)
                this.commands.add(new AdminToggleCommand(this.plugin));
            if (this.plugin.getSettings().DEVCHAT_ENABLED)
                this.commands.add(new DevToggleCommand(this.plugin));
            this.commands.add(new ChatCommand(this.plugin));
            this.commands.add(new HideCommand(this.plugin));
        }

        this.commands.add(new ReloadCommand(this.plugin));
    }

    /**
     * Fork addition: claims every name and alias of our commands in the server's command map, overriding
     * whoever holds them. Run once all plugins are enabled. Needed because plugins built on ACF (TimingSystem
     * registers /s as a shortcut for /settings) force-register their names and delete every entry that
     * pointed at the old command, even simplestaffchat:s, so /s for staff chat vanished entirely.
     */
    public void claimAll() {
        java.util.Map<String, org.bukkit.command.Command> known = org.bukkit.Bukkit.getCommandMap().getKnownCommands();
        for (Command command : commands) {
            for (String name : command.getNameList()) {
                String label = name.toLowerCase(java.util.Locale.ROOT);
                org.bukkit.command.Command previous = known.put(label, command);
                known.put("simplestaffchat:" + label, command);
                if (previous != null && previous != command) {
                    plugin.getLogger().info("/" + label + " now opens " + command.getName() + " (it was taken by "
                            + previous.getClass().getName() + ").");
                }
            }
        }
        org.bukkit.Bukkit.getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
    }

    public void register(Command... command) {
        commands.addAll(List.of(command));
    }

    public Optional<Command> byCommand(String command) {
        return commands.stream().filter(all -> {
            if (all.getName().equalsIgnoreCase(command)) {
                return true;
            } else {
                for (String alias : all.getNameList()) {
                    if (alias.equalsIgnoreCase(command)) {
                        return true;
                    }
                }
                return false;
            }
        }).findFirst();
    }
}