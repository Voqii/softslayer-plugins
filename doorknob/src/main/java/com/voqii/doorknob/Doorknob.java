package com.voqii.doorknob;

import java.util.ArrayList;
import java.util.List;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class Doorknob extends JavaPlugin implements Listener {

    private static final Component DOORKNOB = Component.text("I'm a doorknob!");
    // Players with this permission see what was really typed, marked so they can tell a filtered
    // message apart from someone actually typing "I'm a doorknob!".
    private static final String SEE_PERMISSION = "doorknob.see";
    private static final Component FILTERED_MARK = Component.text("[Filtered] ", NamedTextColor.RED)
            .hoverEvent(HoverEvent.showText(Component.text("Other players saw: I'm a doorknob!")));
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    // MONITOR so we see the final renderer after chat-format plugins (EssentialsX swaps its own in
    // at HIGHEST) and use their formatting for the doorknob line.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String plain = PLAIN.serialize(event.message());
        if (!SlurFilter.containsSlur(plain)) return;

        Player source = event.getPlayer();
        ChatRenderer renderer = event.renderer();
        Component name = source.displayName();

        // Swapping the text inside the chat message isn't enough: chat is signed, and the client
        // may show the original signed text instead (Only Show Secure Chat, ViaVersion). So other
        // players are taken off the chat message entirely and get the doorknob as a system message.
        // The sender and the console still receive the real message, and so does staff (marked).
        Component message = event.message();
        List<Player> others = new ArrayList<>();
        event.viewers().removeIf(viewer -> {
            if (viewer instanceof Player p && !p.equals(source)) {
                others.add(p);
                return true;
            }
            return false;
        });
        for (Player p : others) {
            if (p.hasPermission(SEE_PERMISSION)) {
                p.sendMessage(FILTERED_MARK.append(renderer.render(source, name, message, p)));
            } else {
                p.sendMessage(doorknobLine(renderer, source, name, plain, p));
            }
        }
    }

    // Renders the doorknob line in the server's chat format. Some renderers ignore the message
    // they're handed: EssentialsX bakes the original text into its renderer at HIGHEST and fills
    // its format from that. So the original text is also swapped out of whatever comes back,
    // and if any slur still survives, fall back to a plain vanilla-style line.
    private static Component doorknobLine(ChatRenderer renderer, Player source, Component name,
                                          String plain, Player viewer) {
        Component line = renderer.render(source, name, DOORKNOB, viewer)
                .replaceText(b -> b.matchLiteral(plain).replacement(DOORKNOB));
        if (SlurFilter.containsSlur(PLAIN.serialize(line))) {
            line = Component.translatable("chat.type.text", name, DOORKNOB);
        }
        return line;
    }
}
