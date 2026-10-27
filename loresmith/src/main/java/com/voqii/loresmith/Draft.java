package com.voqii.loresmith;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What the player is editing in the text editor: the text, how it's coloured and styled, and which
 * part of the item it goes to. The same settings are saved on the item ({@link #recipe()}) so
 * reopening a gradient line brings back the gradient, not a wall of colour codes.
 */
final class Draft {

    enum Target { NAME, LINE, NEW_LINE }

    enum Mode {
        CODES("Codes", "loresmith.color.codes", 0),
        SOLID("Solid", "loresmith.color.solid", 1),
        GRADIENT("Gradient", "loresmith.color.gradient", 3),
        RAINBOW("Rainbow", "loresmith.color.rainbow", 0),
        ALTERNATING("Alternating", "loresmith.color.alternating", 3);

        final String label;
        final String permission;
        /** How many colour slots this mode uses. */
        final int colorSlots;

        Mode(String label, String permission, int colorSlots) {
            this.label = label;
            this.permission = permission;
            this.colorSlots = colorSlots;
        }
    }

    Target target = Target.NEW_LINE;
    /** Index in the item's lore when {@link #target} is LINE. */
    int line = -1;
    /** The line as it was when the editor opened (to find it again if other lines moved). */
    @Nullable String originalJson;

    Mode mode = Mode.SOLID;
    String text = "";
    final String[] colors = {"#FFFFFF", "#FFAA00", "#FF5555"};
    boolean bold, italic, underline, strikethrough, obfuscated;
    float hue = 0, spread = 12, saturation = 80, brightness = 100;
    int every = 1;

    /** One-off message (error or hint) shown at the top of the next screen. */
    @Nullable String notice;
    /** Show the rendered preview at the top of the editor. */
    boolean preview;

    @NotNull
    String recipe() {
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String styles = "" + bit(bold) + bit(italic) + bit(underline) + bit(strikethrough) + bit(obfuscated);
        return String.join("|", "1", mode.name(), String.join(",", colors), styles,
                f(hue), f(spread), f(saturation), f(brightness), Integer.toString(every), b64);
    }

    /** Fills this draft from a saved recipe. Returns false (and leaves it untouched) if it can't be read. */
    boolean loadRecipe(@Nullable String recipe) {
        if (recipe == null) {
            return false;
        }
        String[] p = recipe.split("\\|", -1);
        if (p.length != 10 || !p[0].equals("1")) {
            return false;
        }
        try {
            Mode m = Mode.valueOf(p[1]);
            String[] c = p[2].split(",", -1);
            String s = p[3];
            String t = new String(Base64.getUrlDecoder().decode(p[9]), StandardCharsets.UTF_8);
            float h = Float.parseFloat(p[4]), sp = Float.parseFloat(p[5]), sa = Float.parseFloat(p[6]),
                    br = Float.parseFloat(p[7]);
            int ev = Integer.parseInt(p[8]);
            if (c.length != 3 || s.length() != 5) {
                return false;
            }
            mode = m;
            System.arraycopy(c, 0, colors, 0, 3);
            bold = s.charAt(0) == '1';
            italic = s.charAt(1) == '1';
            underline = s.charAt(2) == '1';
            strikethrough = s.charAt(3) == '1';
            obfuscated = s.charAt(4) == '1';
            hue = h;
            spread = sp;
            saturation = sa;
            brightness = br;
            every = ev;
            text = t;
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static char bit(boolean b) {
        return b ? '1' : '0';
    }

    private static String f(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(v);
    }
}
