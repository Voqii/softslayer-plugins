package net.voqii.chattranslate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-way chat translation: every message is delivered untouched, and non-English messages
 * get a translated line underneath, produced by the mc-translator service (English gate,
 * local LLM, LibreTranslate fallback).
 */
public final class ChatTranslate extends JavaPlugin implements Listener {

    private static final Gson GSON = new Gson();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ArrayDeque<String[]> recent = new ArrayDeque<>();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    private URI endpoint;
    private Duration timeout;
    private int contextLines;
    private String format;
    private String hover;
    private boolean showToSender;
    private boolean logToConsole;
    private File hiddenFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        hiddenFile = new File(getDataFolder(), "hidden.yml");
        for (String s : YamlConfiguration.loadConfiguration(hiddenFile).getStringList("hidden")) {
            try { hidden.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Translating via " + endpoint);
    }

    private void loadSettings() {
        reloadConfig();
        endpoint = URI.create(getConfig().getString("service-url", "http://127.0.0.1:5100").replaceAll("/+$", "") + "/translate");
        timeout = Duration.ofSeconds(getConfig().getInt("timeout-seconds", 20));
        contextLines = Math.max(0, getConfig().getInt("context-lines", 3));
        format = getConfig().getString("format", "<dark_gray> ↳ [<gray><lang></gray>] <gray><name></gray>:</dark_gray> <white><translation>");
        hover = getConfig().getString("hover", "<gray>Original:</gray> <white><original>");
        showToSender = getConfig().getBoolean("show-to-sender", false);
        logToConsole = getConfig().getBoolean("log-to-console", true);
    }

    // MONITOR + ignoreCancelled: only messages that actually went out (after mutes/filters)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (sender.hasPermission("chattranslate.bypass")) return;
        String text = PLAIN.serialize(event.message()).trim();
        if (text.isEmpty()) return;
        String name = PLAIN.serialize(sender.displayName());

        JsonArray context = new JsonArray();
        synchronized (recent) {
            for (String[] line : recent) {
                JsonObject o = new JsonObject();
                o.addProperty("name", line[0]);
                o.addProperty("text", line[1]);
                context.add(o);
            }
            recent.addLast(new String[]{name, text});
            while (recent.size() > contextLines) recent.removeFirst();
        }

        JsonObject body = new JsonObject();
        body.addProperty("uuid", sender.getUniqueId().toString());
        body.addProperty("name", name);
        body.addProperty("text", text);
        body.add("context", context);

        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        UUID senderId = sender.getUniqueId();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            if (err != null || resp.statusCode() != 200) {
                getLogger().fine("translator unavailable: " + (err != null ? err.getMessage() : resp.statusCode()));
                return;
            }
            JsonObject res;
            try { res = GSON.fromJson(resp.body(), JsonObject.class); } catch (RuntimeException e) { return; }
            if (res == null || !"translate".equals(str(res, "action"))) return;
            String translation = str(res, "translation");
            if (translation == null || translation.isBlank()) return;
            String lang = str(res, "lang");
            deliver(senderId, name, text, lang == null ? "??" : lang.toUpperCase(Locale.ROOT), translation, str(res, "backend"));
        });
    }

    private void deliver(UUID senderId, String name, String original, String lang, String translation, String backend) {
        Component line = MM.deserialize(format,
                Placeholder.unparsed("lang", lang),
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("translation", translation))
                .hoverEvent(MM.deserialize(hover, Placeholder.unparsed("original", original)));
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!showToSender && p.getUniqueId().equals(senderId)) continue;
                if (hidden.contains(p.getUniqueId()) || !p.hasPermission("chattranslate.see")) continue;
                p.sendMessage(line);
            }
            if (logToConsole) getLogger().info(name + " [" + lang + "] " + original + "  =>  " + translation + "  (" + backend + ")");
        });
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("chattranslate.admin")) { sender.sendMessage(MM.deserialize("<red>No permission.")); return true; }
                loadSettings();
                sender.sendMessage(MM.deserialize("<green>ChatTranslate reloaded. Service: <white>" + endpoint));
            }
            case "on", "off", "toggle" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                boolean show = sub.equals("on") || (sub.equals("toggle") && hidden.contains(p.getUniqueId()));
                if (show) hidden.remove(p.getUniqueId()); else hidden.add(p.getUniqueId());
                saveHidden();
                p.sendMessage(MM.deserialize(show ? "<green>Chat translations shown." : "<gray>Chat translations hidden. <white>/translations on</white> to show them again."));
            }
            default -> sender.sendMessage(MM.deserialize("<gray>/translations [on|off|toggle]" + (sender.hasPermission("chattranslate.admin") ? " | reload" : "")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>(List.of("on", "off", "toggle"));
        if (sender.hasPermission("chattranslate.admin")) out.add("reload");
        if (args.length != 1) return List.of();
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }

    private void saveHidden() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID u : new HashSet<>(hidden)) ids.add(u.toString());
        y.set("hidden", ids);
        try { y.save(hiddenFile); } catch (IOException e) { getLogger().warning("Could not save hidden.yml: " + e.getMessage()); }
    }
}
