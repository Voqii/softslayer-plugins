package com.voqii.discordmentions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites "@name" in a legacy-formatted chat line. Each mention either becomes a Discord
 * "<@id>" ping or is defused with a zero-width space after the "@", so DiscordSRV's own
 * nickname matching (which also hits prefixes: nickname "Vo" matches "@Voqii") can't guess.
 */
final class MentionRewriter {

    /** Invisible on Discord; breaks "@name" so nothing downstream can turn it into a ping. */
    static final String DEFUSE = "@\u200B";

    // Every "@" except existing "<@id>" mentions.
    private static final Pattern AT = Pattern.compile("(?<!<)@");
    // An "@" glued to a preceding word ("me@voqii.com") is never a mention, but DiscordSRV would
    // still match a nickname inside it, so it only gets defused. A legacy colour code like "§a"
    // right before the "@" doesn't count as a word.
    private static final Pattern GLUED = Pattern.compile("(?<!§)\\w");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    /** What to do with one mention. */
    record Outcome(String discordId, int consumed) {
        /** Ping this Discord user, replacing "@" plus {@code consumed} characters after it. */
        static Outcome ping(String discordId, int consumed) { return new Outcome(discordId, consumed); }
        /** Leave the text as typed but make sure nobody gets pinged. */
        static final Outcome DEFUSE = new Outcome(null, 0);
    }

    interface Resolver {
        /**
         * @param token the [A-Za-z0-9_.] name right after the "@" (trailing dots removed)
         * @param after everything after the "@", for nicknames with spaces or symbols
         */
        Outcome resolve(String token, String after);
    }

    private MentionRewriter() {}

    /** @return the rewritten line, or the same instance if nothing changed */
    static String rewrite(String legacy, Resolver resolver) {
        if (legacy.indexOf('@') < 0) return legacy;
        Matcher at = AT.matcher(legacy);
        StringBuilder out = new StringBuilder(legacy.length() + 8);
        int copied = 0;
        boolean changed = false;
        while (at.find()) {
            int start = at.start();
            if (start < copied) continue; // inside text a previous mention consumed
            Matcher t = TOKEN.matcher(legacy).region(start + 1, legacy.length());
            if (!t.lookingAt()) continue;
            String token = stripTrailingDots(t.group()); // "see you @Bob." -> "Bob"
            boolean glued = start > 0 && GLUED.matcher(legacy).region(start - 1, start).useTransparentBounds(true).lookingAt();
            Outcome o = glued || token.length() < 2 ? Outcome.DEFUSE : resolver.resolve(token, legacy.substring(start + 1));
            if (o == null) continue;
            out.append(legacy, copied, start);
            if (o.discordId() != null) {
                out.append("<@").append(o.discordId()).append('>');
                copied = start + 1 + o.consumed();
            } else {
                out.append(DEFUSE);
                copied = start + 1;
            }
            changed = true;
        }
        if (!changed) return legacy;
        out.append(legacy, copied, legacy.length());
        return out.toString();
    }

    private static String stripTrailingDots(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
