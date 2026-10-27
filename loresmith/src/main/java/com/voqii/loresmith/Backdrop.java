package com.voqii.loresmith;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Darkens the world behind an open menu with a hidden blindness effect (no particles, no icon).
 * The client only dims the world 25% behind a dialog. Every menu has an exit action, so Esc reaches
 * the server and the effect always comes off. A blindness the player already had is left alone.
 */
final class Backdrop implements Listener {

    private static final int DURATION_TICKS = 20 * 60 * 10;
    private static final Set<UUID> DARKENED = new HashSet<>();

    static void show(@NotNull Player player) {
        if (DARKENED.contains(player.getUniqueId())
                || !LoreSmith.get().settings().backdrop()
                || player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, DURATION_TICKS, 0, false, false, false));
        DARKENED.add(player.getUniqueId());
    }

    static void hide(@NotNull Player player) {
        if (DARKENED.remove(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hide(event.getPlayer());
    }
}
