import com.voqii.slayerbot.Matcher;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Offline check of the matcher against config.yml.
 * Usage: java MatchCheck config.yml cases.txt
 * cases.txt lines: "<expected-topic or -> | <chat message>"
 * Also checks every stored question still lands on its own topic.
 */
public class MatchCheck {
    public static void main(String[] args) throws Exception {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File(args[0]));
        Map<String, String> aliases = new HashMap<>();
        ConfigurationSection a = c.getConfigurationSection("matching.aliases");
        for (String k : a.getKeys(false)) aliases.put(k, a.getString(k));
        List<Matcher.Topic> topics = new ArrayList<>();
        ConfigurationSection t = c.getConfigurationSection("topics");
        for (String id : t.getKeys(false))
            topics.add(new Matcher.Topic(id, t.getString(id + ".answer"), t.getStringList(id + ".questions"),
                    t.getBoolean(id + ".exact"), t.getBoolean(id + ".enabled", true)));
        if (System.getenv("SWEEP") != null) {
            sweep(c, aliases, topics, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Matcher m = new Matcher(aliases, new HashSet<>(c.getStringList("matching.stop-words")),
                new HashSet<>(c.getStringList("matching.filler-words")), c.getDouble("matching.stop-word-weight"), topics);
        m.warnings().forEach(w -> System.out.println("WARN " + w));

        Set<String> qWords = new HashSet<>();
        for (String w : c.getStringList("settings.question-words")) qWords.add(w.toLowerCase());
        double th = c.getDouble("settings.threshold") / 100, sth = c.getDouble("settings.statement-threshold") / 100;
        int minWords = c.getInt("settings.min-words");

        int bad = 0;
        // 1. every stored question → its own topic
        for (Matcher.Topic topic : topics) {
            for (String q : topic.questions()) {
                String got = decide(m, q, qWords, th, sth, minWords);
                if (!topic.id().equals(got)) {
                    bad++;
                    System.out.printf("SELF  %-20s got %-20s %s%n", topic.id(), got, q);
                }
            }
        }
        // 2. hand-written cases
        int n = 0;
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", 2);
            String want = parts[0].trim(), msg = parts[1].trim();
            String got = decide(m, msg, qWords, th, sth, minWords);
            Matcher.Result best = m.rank(msg, 1).stream().findFirst().orElse(null);
            boolean ok = want.equals(got == null ? "-" : got);
            n++;
            if (!ok) bad++;
            System.out.printf("%s %3.0f%% want %-18s got %-18s %s  [%s]%n", ok ? "ok  " : "FAIL",
                    best == null ? 0 : best.score() * 100, want, got == null ? "-" : got, msg,
                    best == null ? "" : best.topic().id() + ": " + best.phrasing());
        }
        System.out.println(bad + " problems, " + n + " cases");
    }

    /** Misses / false positives over all case files for a grid of stop-word weights and thresholds. */
    static void sweep(YamlConfiguration c, Map<String, String> aliases, List<Matcher.Topic> topics, String[] files) throws Exception {
        List<String[]> cases = new ArrayList<>();
        for (String f : files)
            for (String line : Files.readAllLines(Path.of(f)))
                if (!line.isBlank() && !line.startsWith("#")) cases.add(line.split("\\|", 2));
        Set<String> qWords = new HashSet<>(c.getStringList("settings.question-words"));
        double sth = c.getDouble("settings.statement-threshold") / 100;
        int minWords = c.getInt("settings.min-words");
        System.out.println("weight thresh  miss  falsepos  wrongtopic");
        for (double sw : new double[]{0.15, 0.2, 0.3, 0.4, 0.5}) {
            Matcher m = new Matcher(aliases, new HashSet<>(c.getStringList("matching.stop-words")),
                    new HashSet<>(c.getStringList("matching.filler-words")), sw, topics);
            for (int th = 60; th <= 85; th += 5) {
                int miss = 0, fp = 0, wrong = 0;
                for (String[] cs : cases) {
                    String want = cs[0].trim();
                    String got = decide(m, cs[1].trim(), qWords, th / 100.0, sth, minWords);
                    if (want.equals("-")) { if (got != null) fp++; }
                    else if (got == null) miss++;
                    else if (!got.equals(want)) wrong++;
                }
                System.out.printf("%5.2f  %3d   %4d  %8d  %10d%n", sw, th, miss, fp, wrong);
            }
        }
    }

    static String decide(Matcher m, String msg, Set<String> qWords, double th, double sth, int minWords) throws Exception {
        // Reports the topic even when it's disabled: this checks matching, not what the live config says.
        Optional<Matcher.Result> ex = m.exact(msg);
        if (ex.isPresent()) return ex.get().topic().id();
        if (m.wordCount(msg) < minWords) return null;
        boolean q = m.looksLikeQuestion(msg, qWords);
        List<Matcher.Result> r = m.rank(msg, 1);
        if (r.isEmpty()) return null;
        return r.get(0).score() >= (q ? th : sth) ? r.get(0).topic().id() : null;
    }
}
