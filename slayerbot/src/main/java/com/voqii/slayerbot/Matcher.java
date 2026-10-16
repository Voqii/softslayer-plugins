package com.voqii.slayerbot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores a chat message against every topic's example questions.
 *
 * Words are normalized (lowercase, apostrophes dropped, aliases expanded, plural "s" and "ing"
 * stripped), filler words are thrown away, and common words like "how"/"the"/"is" count for less
 * than content words like "money"/"discord". Two words also match if they're one typo apart
 * (two for long words). The score is the F1 of "how much of the message is explained by the
 * question" and "how much of the question shows up in the message", so long rambling messages and
 * one-word messages both score low.
 *
 * No Bukkit imports on purpose: the whole thing can be tested without a server.
 */
public final class Matcher {

    /**
     * Any message that is exactly one of a topic's questions (after normalizing) is a hit, however short
     * ("discord?", "what do i do"). {@code exact}: only answers when the whole message is one of the questions (after normalizing), for
     * call-and-response jokes like "IBMOC". {@code enabled = false}: still matched, so the question doesn't
     * fall through to a worse topic, but the plugin stays silent.
     */
    public record Topic(String id, String answer, List<String> questions, boolean exact, boolean enabled) {
        public Topic(String id, String answer, List<String> questions) {
            this(id, answer, questions, false, true);
        }
    }

    public record Result(Topic topic, String phrasing, double score) {}

    private record Phrasing(Topic topic, String text, List<String> words) {}

    private final Map<String, String> aliases;
    private final Set<String> stopWords;
    private final Set<String> fillerWords;
    private final double stopWeight;
    private final List<Topic> topics;
    private final List<Phrasing> phrasings = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Phrasing> exact = new java.util.HashMap<>();

    public Matcher(Map<String, String> aliases, Set<String> stopWords, Set<String> fillerWords,
                   double stopWeight, List<Topic> topics) {
        this.aliases = new java.util.HashMap<>();
        // Keys are matched against raw words, so "what's" in the config has to become "whats".
        aliases.forEach((k, v) -> this.aliases.put(String.join("", rawWords(k)), v));
        this.stopWords = normalizeSet(stopWords);
        this.fillerWords = normalizeSet(fillerWords);
        this.stopWeight = stopWeight;
        this.topics = List.copyOf(topics);

        Map<String, Topic> seen = new java.util.HashMap<>();
        for (Topic topic : topics) {
            for (String q : topic.questions()) {
                String key = exactKey(q);
                if (key.isEmpty()) continue;
                Phrasing other = exact.putIfAbsent(key, new Phrasing(topic, q, List.of()));
                if (topic.exact() && other != null && other.topic() != topic)
                    warnings.add("Exact question '" + q + "' is in both '" + other.topic().id() + "' and '" + topic.id() + "'");
            }
            if (topic.exact()) continue;
            for (String q : topic.questions()) {
                List<String> words = words(q);
                if (words.isEmpty()) {
                    warnings.add("Topic '" + topic.id() + "': question '" + q + "' has no usable words");
                    continue;
                }
                String key = String.join(" ", words);
                Topic other = seen.putIfAbsent(key, topic);
                if (other != null) {
                    // Same topic is harmless ("dv" and "death valley" are the same words after aliases).
                    if (other != topic) {
                        warnings.add("Question '" + q + "' is in both '" + other.id() + "' and '" + topic.id() + "'");
                    }
                    continue;
                }
                phrasings.add(new Phrasing(topic, q, words));
            }
        }
    }

    public List<Topic> topics() {
        return topics;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** The topic whose question is exactly this message (after normalizing), if any. */
    public java.util.Optional<Result> exact(String message) {
        Phrasing p = exact.get(exactKey(message));
        return p == null ? java.util.Optional.empty() : java.util.Optional.of(new Result(p.topic(), p.text(), 1.0));
    }

    /** Every word kept, in order, so "yo yo" and "yo" stay different and "hey ibmoc" isn't "ibmoc". */
    private String exactKey(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : rawWords(text)) {
            for (String w : aliases.getOrDefault(raw, raw).split(" ")) if (!w.isEmpty()) out.add(stem(w));
        }
        return String.join(" ", out);
    }

    /** Best-scoring question per topic, highest first, at most {@code limit} entries. */
    public List<Result> rank(String message, int limit) {
        List<String> msg = words(message);
        Map<Topic, Result> best = new java.util.LinkedHashMap<>();
        if (msg.isEmpty()) return List.of();
        for (Phrasing p : phrasings) {
            double score = score(msg, p.words());
            Result cur = best.get(p.topic());
            if (cur == null || score > cur.score()) best.put(p.topic(), new Result(p.topic(), p.text(), score));
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(Result::score).reversed())
                .limit(limit)
                .toList();
    }

    /** Number of non-filler words in the message, repeats included ("yo yo" is 2). */
    public int wordCount(String message) {
        int n = 0;
        for (String raw : rawWords(message)) {
            for (String w : aliases.getOrDefault(raw, raw).split(" ")) {
                if (!w.isEmpty() && !fillerWords.contains(stem(w))) n++;
            }
        }
        return n;
    }

    /** Has a "?", or one of the first three non-filler words is a question word ("hey guys wheres...", "im new what..."). */
    public boolean looksLikeQuestion(String message, Set<String> questionWords) {
        if (message.contains("?")) return true;
        int seen = 0;
        for (String w : rawWords(message)) {
            if (fillerWords.contains(stem(w))) continue;
            if (questionWords.contains(w)) return true;
            if (++seen == 3) break;
        }
        return false;
    }

    private double score(List<String> msg, List<String> question) {
        double msgTotal = 0, msgHit = 0, qTotal = 0, qHit = 0;
        boolean contentHit = false;
        for (String w : msg) {
            double weight = weight(w);
            msgTotal += weight;
            if (containsFuzzy(question, w)) {
                msgHit += weight;
                if (weight >= 1) contentHit = true;
            }
        }
        for (String w : question) {
            double weight = weight(w);
            qTotal += weight;
            if (containsFuzzy(msg, w)) qHit += weight;
        }
        // Matching only "how do i" isn't a match, however short the message is.
        if (!contentHit || msgTotal == 0 || qTotal == 0) return 0;
        double precision = msgHit / msgTotal;
        double recall = qHit / qTotal;
        if (precision + recall == 0) return 0;
        return 2 * precision * recall / (precision + recall);
    }

    private double weight(String word) {
        return stopWords.contains(word) ? stopWeight : 1.0;
    }

    private static boolean containsFuzzy(List<String> words, String word) {
        for (String w : words) {
            if (w.equals(word) || closeEnough(w, word)) return true;
        }
        return false;
    }

    private static boolean closeEnough(String a, String b) {
        int shorter = Math.min(a.length(), b.length());
        // 5-letter words are too close to each other ("stuff"/"staff", "homes"/"comes") to forgive a typo.
        if (shorter < 6) return false;
        int allowed = shorter >= 8 ? 2 : 1;
        if (Math.abs(a.length() - b.length()) > allowed) return false;
        return levenshtein(a, b, allowed) <= allowed;
    }

    /** Edit distance where swapping two neighbouring letters ("discrod") counts as one edit. Gives up early past {@code max}. */
    private static int levenshtein(String a, String b, int max) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i][j - 1] + 1, d[i - 1][j] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
                rowMin = Math.min(rowMin, d[i][j]);
            }
            if (rowMin > max) return max + 1;
        }
        return d[a.length()][b.length()];
    }

    /** Normalized, de-duplicated, filler-free words. */
    List<String> words(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawWords(text)) {
            String expanded = aliases.getOrDefault(raw, raw);
            for (String w : expanded.split(" ")) {
                if (w.isEmpty()) continue;
                w = stem(w);
                if (!fillerWords.contains(w)) out.add(w);
            }
        }
        return new ArrayList<>(out);
    }

    /** Lowercase words with apostrophes removed and other punctuation as spaces. "+" is kept for "ss+"/"18+". */
    static List<String> rawWords(String text) {
        String s = text.toLowerCase(java.util.Locale.ROOT)
                .replace("'", "").replace("’", "")
                .replaceAll("[^a-z0-9+]+", " ")
                .trim();
        return s.isEmpty() ? List.of() : List.of(s.split(" "));
    }

    /** Crude, but applied the same way to chat and to questions, so "dupers"/"duping"/"duper" all become "dup". */
    static String stem(String w) {
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("er")) return w.substring(0, w.length() - 2);
        return w;
    }

    private static Set<String> normalizeSet(Set<String> words) {
        Set<String> out = new java.util.HashSet<>();
        for (String w : words) for (String r : rawWords(w)) out.add(stem(r));
        return out;
    }
}
