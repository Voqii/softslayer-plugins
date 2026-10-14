package com.voqii.discordmentions;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites "@name" in a legacy-formatted chat line into Discord "<@id>" mentions. */
final class MentionRewriter {

    // "@name" where the @ isn't glued to a preceding word or a "<" (emails, existing <@id> mentions).
    // A preceding legacy colour code like "§a" doesn't count as a word.
    private static final Pattern AT = Pattern.compile("(?<!(?<!§)[\\w<])@([A-Za-z0-9_.]{2,32})");

    private MentionRewriter() {}

    /**
     * @param resolve name -> Discord user id, or null to leave that mention alone
     * @return the rewritten line, or the same instance if nothing changed
     */
    static String rewrite(String legacy, Function<String, String> resolve) {
        if (legacy.indexOf('@') < 0) return legacy;
        Matcher m = AT.matcher(legacy);
        StringBuilder out = new StringBuilder(legacy.length());
        boolean changed = false;
        while (m.find()) {
            String raw = m.group(1);
            String name = stripTrailingDots(raw); // "see you @Bob." -> "Bob"
            String id = name.length() >= 2 ? resolve.apply(name) : null;
            String replacement = m.group();
            if (id != null) {
                replacement = "<@" + id + ">" + raw.substring(name.length());
                changed = true;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        if (!changed) return legacy;
        m.appendTail(out);
        return out.toString();
    }

    private static String stripTrailingDots(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
