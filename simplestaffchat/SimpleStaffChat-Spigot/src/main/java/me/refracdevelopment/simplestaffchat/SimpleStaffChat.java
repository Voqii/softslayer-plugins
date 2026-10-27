package me.refracdevelopment.simplestaffchat;

import lombok.Getter;
import me.refracdevelopment.simplestaffchat.listeners.ChatListener;
import me.refracdevelopment.simplestaffchat.listeners.JoinListener;
import me.refracdevelopment.simplestaffchat.manager.CommandManager;
import me.refracdevelopment.simplestaffchat.manager.config.ConfigFile;
import me.refracdevelopment.simplestaffchat.manager.config.cache.Commands;
import me.refracdevelopment.simplestaffchat.manager.config.cache.Config;
import me.refracdevelopment.simplestaffchat.manager.config.cache.Discord;
import me.refracdevelopment.simplestaffchat.utilities.chat.RyMessageUtils;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class SimpleStaffChat extends JavaPlugin {

    @Getter
    private static SimpleStaffChat instance;

    private CommandManager commandManager;
    private ConfigFile configFile;
    private ConfigFile commandsFile;

    private ConfigFile discordFile;
    private ConfigFile localeFile;
    private Config settings;
    private Commands commands;
    private Discord discord;

    @Override
    public void onEnable() {
        instance = this;

        loadFiles();

        loadModules();
        loadHooks();
    }

    private void loadFiles() {
        this.configFile = new ConfigFile("config.yml");
        this.commandsFile = new ConfigFile("commands.yml");
        this.discordFile = new ConfigFile("discord.yml");
        this.localeFile = new ConfigFile("locale/" + getConfigFile().getString("locale") + ".yml");

        this.settings = new Config();
        this.commands = new Commands();
        this.discord = new Discord();

        RyMessageUtils.sendConsole(true, "&aLoaded all files.");
    }

    private void loadModules() {
        this.commandManager = new CommandManager(this);
        getCommandManager().registerAll();
        // After every plugin has enabled, take our command names back (see CommandManager#claimAll).
        getServer().getGlobalRegionScheduler().run(this, task -> getCommandManager().claimAll());

        if (getSettings().JOIN_ENABLED)
            getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        // Symbols (@msg, #msg) are handled by the chat listener too, so it must be registered for either module.
        if (getSettings().CHAT_TOGGLE_ENABLED || getSettings().SYMBOLS_ENABLED)
            getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        RyMessageUtils.sendConsole(true, "&aLoaded modules.");
    }

    private void loadHooks() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            RyMessageUtils.sendConsole(true, "&aHooked into PlaceholderAPI for placeholders.");
        }
    }
}
