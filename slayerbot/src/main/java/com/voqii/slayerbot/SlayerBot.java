package com.voqii.slayerbot;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class SlayerBot extends JavaPlugin implements Listener {

    private static final Pattern COMMAND = Pattern.compile("\\[(/[^\\]]+)]");
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern YEARS_SINCE = Pattern.compile("\\{years-since:(\\d{4})(?:-(\\d{2})-(\\d{2}))?}");
    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    /** Everything read from config.yml, swapped in one go on reload so the async chat thread never sees half a config. */
    private record Settings(Matcher matcher, boolean broadcast, long delayTicks, int minWords,
                            double threshold, double statementThreshold, java.util.Set<String> questionWords,
                            long topicCooldownMs, long playerCooldownMs, String format, String commandFormat,
                            boolean logMisses, double missMin) {}

    private volatile Settings settings;
    private final Map<String, Long> topicCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();
    private Method papiSetPlaceholders;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                papiSetPlaceholders = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (ReflectiveOperationException e) {
                getLogger().warning("PlaceholderAPI found but couldn't hook it: " + e);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void load() {
        reloadConfig();
        FileConfiguration c = getConfig();

        Map<String, String> aliases = new HashMap<>();
        ConfigurationSection aliasSection = c.getConfigurationSection("matching.aliases");
        if (aliasSection != null) {
            for (String key : aliasSection.getKeys(false)) aliases.put(key, aliasSection.getString(key, ""));
        }

        List<Matcher.Topic> topics = new ArrayList<>();
        ConfigurationSection topicSection = c.getConfigurationSection("topics");
        if (topicSection != null) {
            for (String id : topicSection.getKeys(false)) {
                String answer = topicSection.getString(id + ".answer");
                List<String> questions = topicSection.getStringList(id + ".questions");
                if (answer == null || questions.isEmpty()) {
                    getLogger().warning("Topic '" + id + "' needs an answer and at least one question, skipping");
                    continue;
                }
                topics.add(new Matcher.Topic(id, answer, questions,
                        topicSection.getBoolean(id + ".exact", false),
                        topicSection.getBoolean(id + ".enabled", true)));
            }
        }

        Matcher matcher = new Matcher(aliases,
                new HashSet<>(c.getStringList("matching.stop-words")),
                new HashSet<>(c.getStringList("matching.filler-words")),
                c.getDouble("matching.stop-word-weight", 0.3),
                topics);
        matcher.warnings().forEach(w -> getLogger().warning(w));

        java.util.Set<String> questionWords = new HashSet<>();
        for (String w : c.getStringList("settings.question-words")) questionWords.addAll(Matcher.rawWords(w));

        settings = new Settings(matcher,
                c.getBoolean("settings.broadcast", true),
                c.getLong("settings.delay-ticks", 10),
                c.getInt("settings.min-words", 2),
                c.getDouble("settings.threshold", 75) / 100.0,
                c.getDouble("settings.statement-threshold", 95) / 100.0,
                questionWords,
                c.getLong("settings.cooldown.topic-seconds", 30) * 1000,
                c.getLong("settings.cooldown.player-seconds", 5) * 1000,
                c.getString("settings.format", "{answer}"),
                c.getString("settings.command-format", "{command}"),
                c.getBoolean("settings.log-near-misses", true),
                c.getDouble("settings.near-miss-score", 55) / 100.0);
        long disabled = topics.stream().filter(t -> !t.enabled()).count();
        getLogger().info("Loaded " + topics.size() + " topics" + (disabled > 0 ? " (" + disabled + " disabled)" : ""));
    }

    /** MONITOR + ignoreCancelled: muted players, filtered messages etc. never trigger an answer. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("slayerbot.ignore")) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        Settings s = settings;

        // The whole message is one of the questions: answer even if it's one word ("discord?", "IBMOC").
        java.util.Optional<Matcher.Result> exactHit = s.matcher().exact(message);
        if (exactHit.isPresent()) {
            if (exactHit.get().topic().enabled()) answer(s, player, exactHit.get());
            return;
        }
        if (s.matcher().wordCount(message) < s.minWords()) return;
        boolean question = s.matcher().looksLikeQuestion(message, s.questionWords());
        double needed = question ? s.threshold() : s.statementThreshold();

        List<Matcher.Result> ranked = s.matcher().rank(message, 1);
        if (ranked.isEmpty()) return;
        Matcher.Result best = ranked.getFirst();

        if (best.score() < needed) {
            if (s.logMisses() && question && best.score() >= s.missMin()) logMiss(player, message, best);
            return;
        }
        // A disabled topic still "owns" its questions, so they don't get a wrong answer from the next-best topic.
        if (best.topic().enabled()) answer(s, player, best);
    }

    private void answer(Settings s, Player player, Matcher.Result best) {
        long now = System.currentTimeMillis();
        String topicKey = s.broadcast() ? best.topic().id() : best.topic().id() + "/" + player.getUniqueId();
        Long topicUntil = topicCooldowns.get(topicKey);
        Long playerUntil = playerCooldowns.get(player.getUniqueId());
        if ((topicUntil != null && topicUntil > now) || (playerUntil != null && playerUntil > now)) return;
        topicCooldowns.put(topicKey, now + s.topicCooldownMs());
        playerCooldowns.put(player.getUniqueId(), now + s.playerCooldownMs());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            Component answer = render(s, best, player);
            if (s.broadcast()) {
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(answer));
                Bukkit.getConsoleSender().sendMessage(answer);
            } else {
                player.sendMessage(answer);
            }
        }, s.delayTicks());
    }

    private Component render(Settings s, Matcher.Result result, Player player) {
        String text = s.format()
                .replace("{answer}", result.topic().answer())
                .replace("{question}", result.phrasing())
                .replace("{player}", player.getName())
                .replace("{newline}", "<newline>");
        text = yearsSince(text);
        if (papiSetPlaceholders != null) {
            try {
                text = (String) papiSetPlaceholders.invoke(null, player, text);
            } catch (ReflectiveOperationException e) {
                getLogger().warning("PlaceholderAPI failed: " + e);
            }
        }
        text = legacyToMini(text);
        text = commandsToMini(text, s.commandFormat());
        return MiniMessage.miniMessage().deserialize(text);
    }

    /** "[/vote]" → clickable "/vote" that types the command into the chat box (never runs it). */
    static String commandsToMini(String text, String commandFormat) {
        java.util.regex.Matcher m = COMMAND.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String command = m.group(1);
            String quoted = command.replace("\\", "\\\\").replace("'", "\\'");
            String replacement = commandFormat
                    .replace("{command-raw}", quoted)
                    .replace("{command}", command.strip());
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** &c → <reset><red> (legacy colour codes reset formatting), &l → <bold>, &r → <reset>. */
    static String legacyToMini(String text) {
        java.util.regex.Matcher m = LEGACY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            char code = Character.toLowerCase(m.group(1).charAt(0));
            String tag = LEGACY_TAGS.get(code);
            boolean colour = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
            m.appendReplacement(out, colour ? "<reset><" + tag + ">" : "<" + tag + ">");
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {years-since:2013} or {years-since:2013-06-01} → whole years until today. */
    static String yearsSince(String text) {
        java.util.regex.Matcher m = YEARS_SINCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            int years = Period.between(LocalDate.of(year, month, day), LocalDate.now()).getYears();
            m.appendReplacement(out, Integer.toString(years));
        }
        m.appendTail(out);
        return out.toString();
    }

    private void logMiss(Player player, String message, Matcher.Result best) {
        String line = String.format(Locale.ROOT, "%s\t%s\t%.0f%%\t%s\t%s%n",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19),
                player.getName(), best.score() * 100, best.topic().id(), message.replace('\t', ' '));
        Path file = getDataFolder().toPath().resolve("near-misses.tsv");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            synchronized (this) {
                try {
                    Files.writeString(file, line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    getLogger().warning("Couldn't write near-misses.tsv: " + e);
                }
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(mm("<gray>/" + label + " reload <dark_gray>- <white>reload config.yml"));
            sender.sendMessage(mm("<gray>/" + label + " test <message> <dark_gray>- <white>show how a message would be scored"));
            sender.sendMessage(mm("<gray>/" + label + " topics <dark_gray>- <white>list topics"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                load();
                topicCooldowns.clear();
                List<String> warnings = settings.matcher().warnings();
                sender.sendMessage(mm("<green>SlayerBot reloaded: " + settings.matcher().topics().size() + " topics"
                        + (warnings.isEmpty() ? "" : ", <yellow>" + warnings.size() + " warning(s) in console")));
            }
            case "test" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm("<red>Usage: /" + label + " test <message>"));
                    return true;
                }
                test(sender, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "topics" -> {
                for (Matcher.Topic t : settings.matcher().topics()) {
                    sender.sendMessage(mm("<gray>" + t.id() + " <dark_gray>(" + t.questions().size() + " questions"
                            + (t.exact() ? ", exact" : "") + (t.enabled() ? "" : ", <red>disabled<dark_gray>") + ")"));
                }
            }
            default -> sender.sendMessage(mm("<red>Unknown subcommand. /" + label + " for help."));
        }
        return true;
    }

    private void test(CommandSender sender, String message) {
        Settings s = settings;
        java.util.Optional<Matcher.Result> exactHit = s.matcher().exact(message);
        if (exactHit.isPresent()) {
            Matcher.Topic t = exactHit.get().topic();
            sender.sendMessage(mm((t.enabled() ? "<green>✔ exact match: <white>" : "<gray>exact match (disabled): <white>") + t.id()));
            return;
        }
        int words = s.matcher().wordCount(message);
        boolean question = s.matcher().looksLikeQuestion(message, s.questionWords());
        double needed = question ? s.threshold() : s.statementThreshold();
        sender.sendMessage(mm("<gray>Words: <white>" + words + (words < s.minWords() ? " <red>(too short)" : "")
                + "<gray>  Question: <white>" + (question ? "yes" : "no")
                + "<gray>  Needs: <white>" + Math.round(needed * 100) + "%"));
        List<Matcher.Result> ranked = s.matcher().rank(message, 3);
        if (ranked.isEmpty() || ranked.getFirst().score() == 0) {
            sender.sendMessage(mm("<gray>No topic matched at all."));
            return;
        }
        for (Matcher.Result r : ranked) {
            if (r.score() == 0) break;
            boolean hit = r == ranked.getFirst() && r.score() >= needed && words >= s.minWords();
            sender.sendMessage(mm((hit && r.topic().enabled() ? "<green>✔ " : "<gray>  ") + Math.round(r.score() * 100) + "% <white>"
                    + r.topic().id() + (r.topic().enabled() ? "" : " <red>(disabled)") + " <dark_gray>via \"" + MiniMessage.miniMessage().escapeTags(r.phrasing()) + "\""));
        }
    }

    private static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }
}
