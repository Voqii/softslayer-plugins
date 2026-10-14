package com.voqii.doorknob;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects the n-word and its common evasions (leetspeak, repeated letters, punctuation
 * between letters, spaced-out or unevenly split letters, l-for-i, a few Cyrillic look-alikes)
 * while letting the country names Niger / Nigeria / Nigerien / Nigerian through untouched.
 */
public final class SlurFilter {

    // Checked against the letters-only word BEFORE repeated letters are collapsed, so a
    // single-g "niger" passes but "nigger" (collapses to "niger") does not.
    private static final Pattern COUNTRY = Pattern.compile("^niger(?:s|ia|ias|ian|ians|ien|iens)?$");

    // Checked against the collapsed word (runs of the same letter squeezed to one).
    // Word-start forms: nigga, niggah, nigguh, nigger, niggur, niglet, nignog, nigs, nig ...
    private static final Pattern AT_START = Pattern.compile("^(?:nig(?:$|s$|a|uh|er|ur|or|let|nog)|nib(?:a|er))");
    // Anywhere in the word, to catch things glued onto other words ("dumbnigger").
    private static final Pattern ANYWHERE = Pattern.compile("niger|nigah|niguh");
    // Real words that would otherwise trip the checks above (collapsed form).
    private static final Pattern INNOCENT = Pattern.compile("sniger|nigard|panigal|nigaud|nigori");

    // A split-up word ("ni gger", "n igger") only counts if the joined letters have a doubled
    // g or b. Keeps innocent pairs like Spanish "ni ganas" -> "niganas" from matching.
    private static final Pattern DOUBLED = Pattern.compile("gg|bb");

    private SlurFilter() {}

    public static boolean containsSlur(String message) {
        List<String> words = words(message);
        for (String word : words) {
            if (isSlur(word) || isSlur(lForI(word))) return true;
        }
        // Words split at an odd spot: join each word with the next one or two. Only the
        // word-start patterns apply here, since ANYWHERE would match across innocent pairs
        // ("mini germ" -> "minigerm").
        for (int i = 0; i < words.size(); i++) {
            String first = words.get(i), joined = first;
            for (int j = i + 1; j < words.size() && j <= i + 2; j++) {
                joined += words.get(j);
                if (isSplitSlur(joined)) return true;
                // "nl gger" yes, but not "n ligger" where the l starts a separate word.
                if (first.startsWith("nl") && isSplitSlur(lForI(joined))) return true;
            }
        }
        return false;
    }

    // "nlgger": an l standing in for the i. Only swapped right after a leading n, so real
    // words with an l further in stay intact (Swedish "ni ligger", "nilgai").
    private static String lForI(String word) {
        return word.startsWith("nl") ? "ni" + word.substring(2) : word;
    }

    private static boolean isSlur(String word) {
        if (COUNTRY.matcher(word).matches()) return false;
        String c = collapse(word);
        if (INNOCENT.matcher(c).find()) return false;
        return AT_START.matcher(c).find() || ANYWHERE.matcher(c).find();
    }

    private static boolean isSplitSlur(String joined) {
        if (!DOUBLED.matcher(joined).find()) return false;
        String c = collapse(joined);
        return !INNOCENT.matcher(c).find() && AT_START.matcher(c).find();
    }

    /**
     * Normalised, letters-only words; runs of single letters ("n i g g a") are joined into one
     * word. A lone single letter is kept as its own word so "n igger" can be re-joined later.
     */
    private static List<String> words(String message) {
        String text = Normalizer.normalize(message, Normalizer.Form.NFKD).toLowerCase();
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) sb.append(unleet(text.charAt(i)));

        List<String> out = new ArrayList<>();
        StringBuilder spaced = new StringBuilder();
        for (String raw : sb.toString().split("\\s+")) {
            String w = raw.replaceAll("[^a-z]", "");
            if (w.length() == 1) {
                spaced.append(w);
                continue;
            }
            if (!spaced.isEmpty()) out.add(spaced.toString());
            spaced.setLength(0);
            if (!w.isEmpty()) out.add(w);
        }
        if (!spaced.isEmpty()) out.add(spaced.toString());
        return out;
    }

    private static char unleet(char ch) {
        return switch (ch) {
            case '1', '!', '|', 'і', 'ı' -> 'i'; // і (Cyrillic), ı (dotless)
            case '3', 'е' -> 'e';           // е (Cyrillic)
            case '4', '@', 'а' -> 'a';      // а (Cyrillic)
            case '0', 'о' -> 'o';           // о (Cyrillic)
            case '6', '9', 'q', 'ɡ' -> 'g'; // ɡ (Latin script g)
            case 'ո' -> 'n';                // ո (Armenian)
            default -> ch;
        };
    }

    private static String collapse(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (sb.isEmpty() || sb.charAt(sb.length() - 1) != ch) sb.append(ch);
        }
        return sb.toString();
    }
}
