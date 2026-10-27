package com.voqii.loresmith;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** /lore: a menu for naming, colouring and loring items, built on Minecraft's dialog screens. */
public final class LoreSmith extends JavaPlugin implements Listener {

    private static LoreSmith instance;
    private Settings settings;
    private Menus menus;

    static LoreSmith get() {
        return instance;
    }

    Settings settings() {
        return settings;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadSettings();
        menus = new Menus(this, new ItemEdits(this));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new Backdrop(), this);
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("blocked-materials")) {
            Material m = Material.matchMaterial(name);
            if (m == null) {
                getLogger().warning("blocked-materials: unknown material " + name);
            } else {
                materials.add(m);
            }
        }
        Set<String> namespaces = new HashSet<>();
        for (String ns : c.getStringList("blocked-namespaces")) {
            namespaces.add(ns.toLowerCase(Locale.ROOT));
        }
        settings = new Settings(Math.max(0, c.getInt("max-lines", 5)), Math.max(1, c.getInt("max-length", 48)),
                "editable".equalsIgnoreCase(c.getString("unmarked-lines", "locked")),
                materials, namespaces, c.getBoolean("menu-backdrop", true), c.getBoolean("log-edits", true));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("loresmith.admin")) {
                sender.sendMessage(Kit.t("&cYou can't do that."));
                return true;
            }
            loadSettings();
            sender.sendMessage(Kit.t("&aLoreSmith config reloaded."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can edit items.");
            return true;
        }
        if (!p.hasPermission("loresmith.use")) {
            p.sendMessage(Kit.t("&cYou can't use the item editor."));
            return true;
        }
        // Floodgate gives Bedrock players UUIDs whose top half is zero; Bedrock can't show dialogs.
        if (p.getUniqueId().getMostSignificantBits() == 0) {
            p.sendMessage(Kit.t("&cThe item editor only works on Java Edition for now."));
            return true;
        }
        menus.open(p);
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menus.forget(event.getPlayer().getUniqueId());
    }

    /** One line in plugins/LoreSmith/edits.log. */
    void log(@NotNull Player p, @NotNull ItemStack item, @NotNull String what) {
        if (!settings.log()) {
            return;
        }
        String itemName = item.getType().getKey().getKey();
        if (item.getItemMeta().customName() != null) {
            itemName += " \"" + PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName()) + "\"";
        }
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " "
                + p.getName() + " " + itemName + " x" + item.getAmount() + ": " + what + System.lineSeparator();
        Path file = getDataFolder().toPath().resolve("edits.log");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            synchronized (LoreSmith.class) {
                try {
                    Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    getLogger().warning("Couldn't write edits.log: " + ex.getMessage());
                }
            }
        });
    }
}
