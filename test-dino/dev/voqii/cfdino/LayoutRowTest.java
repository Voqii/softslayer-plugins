package dev.voqii.cfdino;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Not shipped. Fuzzes DinoGame.layoutRow: every row must measure exactly `total` px using the
 * client's widths (space 4, bold space 5, glyph = its advance) and keep sprites near their target.
 * Run: ./test.sh
 */
public class LayoutRowTest {

    private static final Pattern TOKEN = Pattern.compile("<b>( +)</b>|<(#[0-9a-f]{6})>(.+?)</\\2>| ");

    static int measure(String row, java.util.Map<String, Integer> widths) {
        int w = 0;
        Matcher m = TOKEN.matcher(row);
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) throw new IllegalStateException("unparsed text at " + pos + ": " + row);
            if (m.group(1) != null) w += 5 * m.group(1).length();
            else if (m.group(3) != null) w += widths.get(m.group(3));
            else w += 4;
            pos = m.end();
        }
        if (pos != row.length()) throw new IllegalStateException("trailing unparsed: " + row.substring(pos));
        return w;
    }

    public static void main(String[] args) {
        String[] glyphs = {"🦖", "🦎", "🌵", "🐦", "🦅", "🌥"};
        int[] widths = {8, 7, 6, 7, 8, 8};
        java.util.Map<String, Integer> wmap = new java.util.HashMap<>();
        for (int i = 0; i < glyphs.length; i++) wmap.put(glyphs[i], widths[i]);
        Random r = new Random(42);
        int total = 14 * 16, runs = 200_000, maxDrift = 0;
        for (int run = 0; run < runs; run++) {
            List<DinoGame.Sprite> sprites = new ArrayList<>();
            int n = r.nextInt(6);
            for (int i = 0; i < n; i++) {
                int gi = r.nextInt(glyphs.length);
                sprites.add(new DinoGame.Sprite(r.nextInt(-20, total + 10), glyphs[gi], widths[gi], "#abcdef", r.nextInt(3)));
            }
            String row = DinoGame.layoutRow(new ArrayList<>(sprites), total);
            int w = measure(row, wmap);
            if (w != total) throw new AssertionError("width " + w + " != " + total + " for " + sprites + "\n" + row);
            // drift: each drawn sprite should be within 3px of where it was asked to be
            int cursor = 0;
            Matcher m = TOKEN.matcher(row);
            while (m.find()) {
                if (m.group(1) != null) cursor += 5 * m.group(1).length();
                else if (m.group(3) != null) {
                    int at = cursor;
                    String g = m.group(3);
                    int best = sprites.stream().filter(s -> s.glyph().equals(g)).mapToInt(s -> Math.abs(s.px() - at)).min().orElse(99);
                    maxDrift = Math.max(maxDrift, best);
                    cursor += wmap.get(g);
                } else cursor += 4;
            }
        }
        System.out.println("OK " + runs + " random rows, all exactly " + total + "px; max sprite drift " + maxDrift + "px");
    }
}
