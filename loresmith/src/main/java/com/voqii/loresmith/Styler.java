package com.voqii.loresmith;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a {@link Draft} into the finished text, checking the player's permissions on the way.
 * Colour modes follow FZMM's text formatter (solid, gradient, rainbow, interleaved); gradients are
 * blended in OKLab instead of plain RGB so red to green goes through yellow, not mud.
 */
final class Styler {

    record Result(@Nullable Component text, @Nullable String error) {
        static Result fail(String error) {
            return new Result(null, error);
        }
    }

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final Pattern HEX_CODE = Pattern.compile("(?i)&#[0-9a-f]{6}");
    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)&[lmno]");
    private static final Pattern MAGIC_CODE = Pattern.compile("(?i)&k");
    private static final Pattern ANY_CODE = Pattern.compile("(?i)&(#[0-9a-f]{6}|[0-9a-fk-or])");

    private Styler() {
    }

    @NotNull
    static Result render(@NotNull Draft d, @NotNull Player p, int maxLength) {
        if (!p.hasPermission(d.mode.permission)) {
            return Result.fail("You can't use " + d.mode.label + " mode.");
        }
        boolean format = p.hasPermission("loresmith.format");
        boolean magic = p.hasPermission("loresmith.format.obfuscated");
        boolean hex = p.hasPermission("loresmith.color.hex");

        String text = d.text;
        if (d.mode == Draft.Mode.CODES) {
            if (!hex) text = HEX_CODE.matcher(text).replaceAll("");
            if (!format) text = FORMAT_CODE.matcher(text).replaceAll("");
            if (!magic) text = MAGIC_CODE.matcher(text).replaceAll("");
        } else {
            text = ANY_CODE.matcher(text).replaceAll("");
        }
        if (text.isBlank()) {
            return Result.fail("Type some text first.");
        }

        List<TextColor> colors = new ArrayList<>();
        for (int i = 0; i < d.mode.colorSlots; i++) {
            String raw = d.colors[i];
            if (raw == null || raw.isBlank()) {
                continue;
            }
            TextColor c = parseColor(raw);
            if (c == null) {
                return Result.fail("Colour " + (i + 1) + " (\"" + raw + "\") isn't a colour. Use a hex code like #FF8800.");
            }
            if (!hex && NamedTextColor.namedColor(c.value()) == null) {
                return Result.fail("Colour " + (i + 1) + " must be one of the 16 standard colours.");
            }
            colors.add(c);
        }

        Component body = switch (d.mode) {
            case CODES -> AMP.deserialize(text);
            case SOLID -> {
                if (colors.isEmpty()) yield null;
                yield Component.text(text, colors.getFirst());
            }
            case GRADIENT -> {
                if (colors.size() < 2) yield null;
                yield perChar(text, gradient(colors, codePoints(text).size()));
            }
            case RAINBOW -> perChar(text, rainbow(d, codePoints(text).size()));
            case ALTERNATING -> {
                if (colors.isEmpty()) yield null;
                yield alternating(text, colors, Math.max(1, d.every));
            }
        };
        if (body == null) {
            return Result.fail(d.mode == Draft.Mode.GRADIENT ? "A gradient needs at least two colours."
                    : "Pick a colour first.");
        }

        TextComponent.Builder out = Component.text().color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, format && d.italic);
        if (d.mode != Draft.Mode.CODES) {
            if (format && d.bold) out.decoration(TextDecoration.BOLD, true);
            if (format && d.underline) out.decoration(TextDecoration.UNDERLINED, true);
            if (format && d.strikethrough) out.decoration(TextDecoration.STRIKETHROUGH, true);
            if (magic && d.obfuscated) out.decoration(TextDecoration.OBFUSCATED, true);
        }
        Component result = out.append(body).build();

        int length = visibleLength(result);
        if (length > maxLength && !p.hasPermission("loresmith.admin")) {
            return Result.fail("Too long: " + length + " characters, the limit is " + maxLength + ".");
        }
        return new Result(result, null);
    }

    static int visibleLength(@NotNull Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c).codePointCount(0,
                PlainTextComponentSerializer.plainText().serialize(c).length());
    }

    /** {@code #rgb}, {@code #rrggbb} (with or without #) or a colour name like {@code gold}. */
    @Nullable
    static TextColor parseColor(@NotNull String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        NamedTextColor named = NamedTextColor.NAMES.value(s);
        if (named != null) {
            return named;
        }
        if (s.startsWith("&#")) s = s.substring(2);
        else if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (!s.matches("[0-9a-f]{6}")) {
            return null;
        }
        return TextColor.color(Integer.parseInt(s, 16));
    }

    @NotNull
    static String hex(@NotNull TextColor c) {
        return String.format("#%06X", c.value());
    }

    /** Back to {@code &} codes, for editing a line LoreSmith has no saved settings for. */
    @NotNull
    static String toCodes(@NotNull Component c) {
        return AMP.serialize(c);
    }

    // ---------------------------------------------------------------- colour modes

    private static List<String> codePoints(String s) {
        List<String> out = new ArrayList<>();
        s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
        return out;
    }

    private static Component perChar(String text, List<TextColor> colors) {
        TextComponent.Builder b = Component.text();
        List<String> chars = codePoints(text);
        for (int i = 0; i < chars.size(); i++) {
            b.append(Component.text(chars.get(i), colors.get(Math.min(i, colors.size() - 1))));
        }
        return b.build();
    }

    /** Colours for {@code n} characters spread evenly across the stops. */
    static List<TextColor> gradient(List<TextColor> stops, int n) {
        List<TextColor> out = new ArrayList<>(n);
        if (n <= 1) {
            out.add(stops.getFirst());
            return out;
        }
        for (int i = 0; i < n; i++) {
            double pos = (double) i / (n - 1) * (stops.size() - 1);
            int seg = Math.min((int) pos, stops.size() - 2);
            out.add(mixOklab(stops.get(seg), stops.get(seg + 1), pos - seg));
        }
        return out;
    }

    private static List<TextColor> rainbow(Draft d, int n) {
        List<TextColor> out = new ArrayList<>(n);
        float s = Math.clamp(d.saturation / 100f, 0f, 1f);
        float v = Math.clamp(d.brightness / 100f, 0f, 1f);
        for (int i = 0; i < n; i++) {
            float h = ((d.hue + d.spread * i) % 360f) / 360f;
            out.add(TextColor.color(Color.HSBtoRGB(h, s, v) & 0xFFFFFF));
        }
        return out;
    }

    /** Colours take turns every {@code every} characters (FZMM's "interleaved"). */
    private static Component alternating(String text, List<TextColor> colors, int every) {
        TextComponent.Builder b = Component.text();
        List<String> chars = codePoints(text);
        for (int start = 0, part = 0; start < chars.size(); start += every, part++) {
            String chunk = String.join("", chars.subList(start, Math.min(chars.size(), start + every)));
            b.append(Component.text(chunk, colors.get(part % colors.size())));
        }
        return b.build();
    }

    // OKLab (Björn Ottosson): perceptually even blending.
    private static TextColor mixOklab(TextColor a, TextColor b, double t) {
        double[] la = toOklab(a.value()), lb = toOklab(b.value());
        double[] m = {la[0] + (lb[0] - la[0]) * t, la[1] + (lb[1] - la[1]) * t, la[2] + (lb[2] - la[2]) * t};
        return TextColor.color(fromOklab(m));
    }

    private static double toLinear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int toSrgb(double v) {
        v = Math.clamp(v, 0, 1);
        double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(s, 0, 1) * 255);
    }

    private static double[] toOklab(int rgb) {
        double r = toLinear(rgb >> 16 & 0xFF), g = toLinear(rgb >> 8 & 0xFF), b = toLinear(rgb & 0xFF);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static int fromOklab(double[] lab) {
        double l = lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2];
        double m = lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2];
        double s = lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2];
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        int r = toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        int g = toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        int b = toSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
        return r << 16 | g << 8 | b;
    }
}
