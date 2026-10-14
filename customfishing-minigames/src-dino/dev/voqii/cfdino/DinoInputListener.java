package dev.voqii.cfdino;

import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feeds movement-key input (W/S/space/shift, whatever the player has them bound to) to running
 * dino games. CustomFishing doesn't forward movement keys to minigames, so the expansion listens itself.
 */
final class DinoInputListener implements Listener {

    interface InputSink {
        void onInput(Input input);
    }

    static final Map<UUID, InputSink> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean registered;

    private DinoInputListener() {
    }

    /** Registers once per class loader; drops listeners left behind by older expansion loads (/cfishing reload). */
    static synchronized void ensureRegistered() {
        if (registered) return;
        Plugin plugin = plugin();
        if (plugin == null || !plugin.isEnabled()) return;
        for (RegisteredListener rl : HandlerList.getRegisteredListeners(plugin)) {
            Listener l = rl.getListener();
            if (l.getClass().getName().equals(DinoInputListener.class.getName()) && l.getClass() != DinoInputListener.class) {
                HandlerList.unregisterAll(l);
            }
        }
        Bukkit.getPluginManager().registerEvents(new DinoInputListener(), plugin);
        registered = true;
    }

    static Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin("CustomFishing");
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        InputSink sink = ACTIVE.get(event.getPlayer().getUniqueId());
        if (sink != null) sink.onInput(event.getInput());
    }
}
