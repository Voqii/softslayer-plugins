package com.voqii.headmark;

import com.github.joelgodofwar.mmh.events.MobHeadDropEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    // &-codes plus hex colours written as &#9D9D97
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    // worldName -> parsed watermark line
    private final Map<String, Component> watermarks = new LinkedHashMap<>();
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
        Component watermark = watermarks.get(worldName);
        if (watermark == null) {
            return; // not a watermarked world
        }

        // Same ItemStack object MMH will give/drop. Mutating it in place is what persists.
        ItemStack head = event.getHead();
        ItemMeta meta = head.getItemMeta();
        if (meta == null) {
            return;
        }

        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();

        // Idempotency guard: never stamp the same watermark twice.
        if (!lore.isEmpty() && watermark.equals(lore.get(lore.size() - 1))) {
            return;
        }

        lore.add(watermark);
        meta.lore(lore);
        head.setItemMeta(meta); // writes back into the shared ItemStack -> survives on the player path
    }

    /**
     * Parses &#RRGGBB hex codes and legacy &-codes. Lore is italic unless told otherwise, so
     * italic is switched off here and only &o turns it on, as the config comments promise.
     */
    private Component colorize(String input) {
        return LEGACY.deserialize(input).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("headmark")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("headmark.reload")) {
                    sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                    return true;
                }
                loadWatermarks();
                sender.sendMessage(Component.text("HeadMark reloaded. Worlds: " + watermarks.keySet(), NamedTextColor.GREEN));
                return true;
            }
            sender.sendMessage(Component.text("Usage: /headmark reload", NamedTextColor.GRAY));
            return true;
        }
        return false;
    }
}
