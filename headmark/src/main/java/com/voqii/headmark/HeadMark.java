package com.voqii.headmark;

import com.github.joelgodofwar.mmh.events.MobHeadDropEvent;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HeadMark — appends a lore "watermark" to MoreMobHeads player heads that are
 * dropped by kills inside specific worlds (e.g. Death Valley, ClassPvP).
 *
 * Hook: MoreMobHeads fires {@link MobHeadDropEvent} right before it hands the
 * head to the killer. For PLAYER kills the head is NOT placed in
 * EntityDeathEvent.getDrops(); MMH delivers it via playerGiveOrDropHead(...)
 * using the same ItemStack reference the event was built with. So we mutate
 * that ItemStack in place (getHead().setItemMeta(...)) rather than relying on
 * event.setLore()/setHead(), which MMH ignores on the player path.
 */
public final class HeadMark extends JavaPlugin implements Listener {

    // Matches Bukkit/Spigot hex codes like &#9D9D97
    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");

    // worldName -> pre-colorized (section-sign) watermark line
    private final Map<String, String> watermarks = new LinkedHashMap<>();
    private boolean playerHeadsOnly = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadWatermarks();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("HeadMark enabled. Watermarked worlds: " + watermarks.keySet());
    }

    private void loadWatermarks() {
        watermarks.clear();
        reloadConfig();
        playerHeadsOnly = getConfig().getBoolean("player-heads-only", true);
        ConfigurationSection sec = getConfig().getConfigurationSection("watermarks");
        if (sec == null) {
            getLogger().warning("No 'watermarks' section in config.yml — nothing will be stamped.");
            return;
        }
        for (String world : sec.getKeys(false)) {
            String raw = sec.getString(world);
            if (raw != null && !raw.isEmpty()) {
                watermarks.put(world, colorize(raw));
            }
        }
    }

    /**
     * Runs at HIGHEST so our line is the final lore entry, and ignores the event
     * if another plugin already cancelled the drop.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobHeadDrop(MobHeadDropEvent event) {
        // Player kills only: the victim entity is a Player => the dropped head is that player's head.
        if (playerHeadsOnly && !(event.getEntity() instanceof Player)) {
            return;
        }

        // Anchor on where the kill happened (victim's world == kill location).
        String worldName = event.getEntity().getWorld().getName();
        String watermark = watermarks.get(worldName);
        if (watermark == null) {
            return; // not a watermarked world
        }

        // Same ItemStack object MMH will give/drop. Mutating it in place is what persists.
        ItemStack head = event.getHead();
        ItemMeta meta = head.getItemMeta();
        if (meta == null) {
            return;
        }

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

        // Idempotency guard: never stamp the same watermark twice.
        if (!lore.isEmpty() && watermark.equals(lore.get(lore.size() - 1))) {
            return;
        }

        lore.add(watermark);
        meta.setLore(lore);
        head.setItemMeta(meta); // writes back into the shared ItemStack -> survives on the player path
    }

    /** Converts &#RRGGBB hex codes and legacy &-codes into section-sign (§) form. */
    private String colorize(String input) {
        Matcher m = HEX.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("\u00a7x");
            for (char c : hex.toCharArray()) {
                rep.append('\u00a7').append(c);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("headmark")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("headmark.reload")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                    return true;
                }
                loadWatermarks();
                sender.sendMessage(ChatColor.GREEN + "HeadMark reloaded. Worlds: " + watermarks.keySet());
                return true;
            }
            sender.sendMessage(ChatColor.GRAY + "Usage: /headmark reload");
            return true;
        }
        return false;
    }
}
