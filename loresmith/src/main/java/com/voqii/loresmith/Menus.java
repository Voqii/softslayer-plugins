package com.voqii.loresmith;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The three screens: the item overview, the text editor and the colour picker.
 * One {@link Session} per player remembers which inventory slot is being edited and the editor's
 * unsaved settings, so moving between screens never loses what was typed.
 */
final class Menus {

    private static final int UNDO_LIMIT = 10;
    private static final int FAVOURITES_LIMIT = 8;

    static final class Session {
        final int slot;
        final Material material;
        Draft draft = new Draft();
        final Deque<ItemEdits.Snapshot> undo = new ArrayDeque<>();
        @Nullable String notice;

        Session(int slot, Material material) {
            this.slot = slot;
            this.material = material;
        }
    }

    /** The 16 standard colours, then extra ones for players with custom colours. */
    private static final Map<String, Integer> STANDARD = new LinkedHashMap<>();
    private static final Map<String, Integer> EXTRA = new LinkedHashMap<>();

    static {
        for (NamedTextColor c : List.of(NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN,
                NamedTextColor.DARK_AQUA, NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD,
                NamedTextColor.GRAY, NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN,
                NamedTextColor.AQUA, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW,
                NamedTextColor.WHITE)) {
            STANDARD.put(nice(c.toString()), c.value());
        }
        EXTRA.put("Crimson", 0xDC143C);
        EXTRA.put("Coral", 0xFF7F50);
        EXTRA.put("Orange", 0xFF8C00);
        EXTRA.put("Amber", 0xFFBF00);
        EXTRA.put("Lime", 0x9AFF3C);
        EXTRA.put("Mint", 0x98FFB3);
        EXTRA.put("Teal", 0x1ABC9C);
        EXTRA.put("Sky", 0x87CEEB);
        EXTRA.put("Ocean", 0x1E6FD9);
        EXTRA.put("Indigo", 0x5B3FD9);
        EXTRA.put("Violet", 0x9B59F6);
        EXTRA.put("Magenta", 0xFF2BD6);
        EXTRA.put("Pink", 0xFF9ECF);
        EXTRA.put("Peach", 0xFFCBA4);
        EXTRA.put("Brown", 0x8B5A2B);
        EXTRA.put("Silver", 0xC9CED6);
    }

    private final LoreSmith plugin;
    private final ItemEdits edits;
    private final NamespacedKey favouritesKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    Menus(@NotNull LoreSmith plugin, @NotNull ItemEdits edits) {
        this.plugin = plugin;
        this.edits = edits;
        this.favouritesKey = new NamespacedKey(plugin, "favourites");
    }

    void forget(@NotNull UUID player) {
        sessions.remove(player);
    }

    // ================================================================ entry

    /** /lore: starts a session on the item in the player's main hand. */
    void open(@NotNull Player p) {
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            p.sendMessage(Kit.t("&cHold the item you want to edit, then type &f/lore&c."));
            return;
        }
        String blocked = blockedReason(p, item);
        if (blocked != null) {
            p.sendMessage(Kit.t("&c" + blocked));
            return;
        }
        Session s = new Session(p.getInventory().getHeldItemSlot(), item.getType());
        sessions.put(p.getUniqueId(), s);
        showMain(p);
    }

    @Nullable
    private String blockedReason(Player p, ItemStack item) {
        if (p.hasPermission("loresmith.admin")) {
            return null;
        }
        Settings cfg = plugin.settings();
        if (cfg.blockedMaterials().contains(item.getType())) {
            return "This item can't be renamed or lored.";
        }
        if (!cfg.blockedNamespaces().isEmpty() && item.hasItemMeta()) {
            for (NamespacedKey key : item.getItemMeta().getPersistentDataContainer().getKeys()) {
                if (cfg.blockedNamespaces().contains(key.getNamespace())) {
                    return "This is a special item, so it can't be renamed or lored.";
                }
            }
        }
        return null;
    }

    /** The item being edited, or null (and the menu closed) if the player no longer holds it. */
    @Nullable
    private ItemStack target(Player p, Session s) {
        ItemStack item = p.getInventory().getItem(s.slot);
        if (item == null || item.getType() != s.material || p.getInventory().getHeldItemSlot() != s.slot) {
            p.sendMessage(Kit.t("&cThe item moved. Hold it and type &f/lore&c again."));
            sessions.remove(p.getUniqueId());
            return null;
        }
        return item;
    }

    @Nullable
    private Session session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    // ================================================================ overview

    void showMain(@NotNull Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        List<ItemEdits.Line> lines = edits.lines(meta);
        boolean admin = p.hasPermission("loresmith.admin");
        boolean unmarkedOk = plugin.settings().unmarkedEditable() || admin;

        List<DialogBody> body = new ArrayList<>();
        if (s.notice != null) {
            body.add(Kit.body(s.notice));
            s.notice = null;
        }
        ItemStack shown = item.clone();
        shown.setAmount(1);
        body.add(Kit.item(shown, Kit.t("&7Hover the item to see how it looks. Click a line below to change it.")));

        List<ActionButton> buttons = new ArrayList<>();
        int w = 300;
        if (p.hasPermission("loresmith.name")) {
            Component name = meta.customName();
            buttons.add(Kit.button(Kit.t("&e✎ Name: ").append(name != null ? name : Kit.t("&8(not named)")),
                    "Rename the item", w, (pl, r) -> editName(pl)));
        }
        int ours = 0;
        for (int i = 0; i < lines.size(); i++) {
            ItemEdits.Line l = lines.get(i);
            if (l.ours) ours++;
            boolean editable = l.ours || unmarkedOk;
            final int index = i;
            if (editable && p.hasPermission("loresmith.lore")) {
                buttons.add(Kit.button(Kit.t("&e✎ ").append(l.text), "Edit line " + (i + 1), w,
                        (pl, r) -> editLine(pl, index)));
            } else {
                buttons.add(Kit.button(Kit.t("&8⛓ ").append(l.text),
                        "&7Added by the server. You can't change this line.", w,
                        (pl, r) -> back(pl, "&7That line was added by the server, so it's locked.")));
            }
        }
        int limit = lineLimit(p);
        if (p.hasPermission("loresmith.lore")) {
            if (ours < limit || admin) {
                buttons.add(Kit.button("&a+ Add a line", "You've used " + ours + " of " + limit + " lines", w,
                        (pl, r) -> newLine(pl)));
            } else {
                buttons.add(Kit.button("&8+ Add a line", "&7You've used all " + limit + " of your lines", w,
                        (pl, r) -> back(pl, "&cYou've used all " + limit + " of your lines on this item.")));
            }
        }
        if (p.hasPermission("loresmith.glint")) {
            Boolean glint = meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null;
            String state = glint == null ? "&7Normal" : glint ? "&bAlways" : "&8Never";
            buttons.add(Kit.button("&d✦ Shine: " + state,
                    "Normal: only enchanted items shine. Click to change.", w, (pl, r) -> cycleGlint(pl)));
        }
        if (!s.undo.isEmpty()) {
            buttons.add(Kit.button("&6↶ Undo", "Undo your last change (" + s.undo.size() + " left)", w,
                    (pl, r) -> undo(pl)));
        }
        if (ours > 0) {
            buttons.add(Kit.button("&c✖ Remove all my lines", "Takes off every line you added", w,
                    (pl, r) -> confirmClear(pl)));
        }
        ActionButton done = Kit.button("Done", null, 200, (pl, r) -> sessions.remove(pl.getUniqueId()));
        Kit.show(p, Kit.menu(Kit.t("Item editor"), body, List.of(), buttons, 1, done));
    }

    private void back(Player p, String notice) {
        Session s = session(p);
        if (s == null) return;
        s.notice = notice;
        showMain(p);
    }

    private int lineLimit(Player p) {
        int best = plugin.settings().maxLines();
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("loresmith.lines.")) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring("loresmith.lines.".length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    // ================================================================ starting an edit

    private void editName(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        Draft d = freshDraft(p);
        d.target = Draft.Target.NAME;
        Component name = meta.customName();
        if (!d.loadRecipe(edits.nameRecipe(meta)) && name != null) {
            loadAsCodes(p, d, name);
        }
        s.draft = d;
        showEditor(p);
    }

    private void editLine(Player p, int index) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        List<ItemEdits.Line> lines = edits.lines(item.getItemMeta());
        if (index >= lines.size()) {
            back(p, "&cThat line is gone.");
            return;
        }
        ItemEdits.Line l = lines.get(index);
        Draft d = freshDraft(p);
        d.target = Draft.Target.LINE;
        d.line = index;
        d.originalJson = ItemEdits.json(l.text);
        if (!d.loadRecipe(l.recipe)) {
            loadAsCodes(p, d, l.text);
        }
        s.draft = d;
        showEditor(p);
    }

    private void newLine(Player p) {
        Session s = session(p);
        if (s == null) return;
        Draft d = freshDraft(p);
        d.target = Draft.Target.NEW_LINE;
        s.draft = d;
        showEditor(p);
    }

    /** A new draft in the first colour mode the player is allowed to use. */
    private Draft freshDraft(Player p) {
        Draft d = new Draft();
        for (Draft.Mode m : new Draft.Mode[]{Draft.Mode.SOLID, Draft.Mode.GRADIENT, Draft.Mode.CODES,
                Draft.Mode.RAINBOW, Draft.Mode.ALTERNATING}) {
            if (p.hasPermission(m.permission)) {
                d.mode = m;
                break;
            }
        }
        return d;
    }

    /** Text LoreSmith didn't make opens in Codes mode (or as plain text if Codes isn't allowed). */
    private void loadAsCodes(Player p, Draft d, Component text) {
        if (p.hasPermission(Draft.Mode.CODES.permission)) {
            d.mode = Draft.Mode.CODES;
            d.text = Styler.toCodes(text);
        } else {
            d.text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
        }
    }

    // ================================================================ editor

    void showEditor(@NotNull Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        Draft d = s.draft;
        boolean format = p.hasPermission("loresmith.format");
        boolean magic = p.hasPermission("loresmith.format.obfuscated");
        Settings cfg = plugin.settings();

        Styler.Result rendered = Styler.render(d, p, cfg.maxLength());
        List<DialogBody> body = new ArrayList<>();
        if (d.notice != null) {
            body.add(Kit.body(d.notice));
            d.notice = null;
        }
        ItemStack preview = item.clone();
        preview.setAmount(1);
        Component previewLine;
        if (rendered.text() != null) {
            previewLine = Kit.t("&7Preview: ").append(rendered.text());
            applyPreview(preview, d, rendered.text());
        } else {
            previewLine = Kit.t("&7Preview: &8" + (d.text.isBlank() ? "(type some text)" : rendered.error()));
        }
        body.add(Kit.item(preview, previewLine));
        body.add(Kit.body(hint(d.mode, p)));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(Kit.text("text", d.target == Draft.Target.NAME ? "Name" : "Text", d.text, 256, Kit.INPUT_WIDTH));
        for (int i = 0; i < d.mode.colorSlots; i++) {
            String label = "Colour " + (i + 1) + (d.mode == Draft.Mode.GRADIENT && i == 2 ? " (optional)" : "")
                    + (d.mode == Draft.Mode.ALTERNATING && i > 0 ? " (optional)" : "");
            inputs.add(Kit.text("c" + i, label, d.colors[i], 16, 120));
        }
        if (d.mode == Draft.Mode.RAINBOW) {
            inputs.add(Kit.slider("hue", "Starting colour", 0, 355, 5, d.hue));
            inputs.add(Kit.slider("spread", "Colour change per letter", 2, 60, 1, d.spread));
            inputs.add(Kit.slider("sat", "Colourfulness %", 0, 100, 5, d.saturation));
            inputs.add(Kit.slider("bright", "Brightness %", 10, 100, 5, d.brightness));
        }
        if (d.mode == Draft.Mode.ALTERNATING) {
            inputs.add(Kit.slider("every", "Switch colour every N letters", 1, 8, 1, d.every));
        }
        if (d.mode != Draft.Mode.CODES && format) {
            inputs.add(Kit.toggle("bold", "Bold", d.bold));
            inputs.add(Kit.toggle("italic", "Italic", d.italic));
            inputs.add(Kit.toggle("underline", "Underline", d.underline));
            inputs.add(Kit.toggle("strike", "Strikethrough", d.strikethrough));
            if (magic) {
                inputs.add(Kit.toggle("magic", "Scrambled (obfuscated)", d.obfuscated));
            }
        }

        List<ActionButton> buttons = new ArrayList<>();
        int w = 96;
        for (Draft.Mode m : Draft.Mode.values()) {
            if (!p.hasPermission(m.permission)) continue;
            String label = m == d.mode ? "&e▶ " + m.label : "&7" + m.label;
            buttons.add(Kit.button(label, modeTooltip(m), w, (pl, r) -> {
                capture(pl, r);
                Session ss = session(pl);
                if (ss == null) return;
                ss.draft.mode = m;
                showEditor(pl);
            }));
        }
        for (int i = 0; i < d.mode.colorSlots; i++) {
            final int slot = i;
            TextColor current = Styler.parseColor(d.colors[i]);
            Component label = Component.text("■ ", current != null ? current : NamedTextColor.DARK_GRAY)
                    .append(Kit.t("&fColour " + (i + 1)));
            buttons.add(Kit.button(label, "Pick colour " + (i + 1) + " from a palette", w, (pl, r) -> {
                capture(pl, r);
                showPalette(pl, slot);
            }));
        }
        buttons.add(Kit.button("&b⟳ Preview", "Update the preview with what you typed", w, (pl, r) -> {
            capture(pl, r);
            showEditor(pl);
        }));
        buttons.add(Kit.button("&a✔ Save", "Put this on the item", w, (pl, r) -> {
            capture(pl, r);
            save(pl);
        }));
        if (d.target == Draft.Target.LINE) {
            buttons.add(Kit.button("&7▲ Move up", null, w, (pl, r) -> {
                capture(pl, r);
                move(pl, -1);
            }));
            buttons.add(Kit.button("&7▼ Move down", null, w, (pl, r) -> {
                capture(pl, r);
                move(pl, 1);
            }));
            buttons.add(Kit.button("&c✖ Delete line", null, w, (pl, r) -> deleteLine(pl)));
        } else if (d.target == Draft.Target.NAME && item.getItemMeta().customName() != null) {
            buttons.add(Kit.button("&c✖ Remove name", "Back to the item's normal name", w, (pl, r) -> removeName(pl)));
        }
        String title = switch (d.target) {
            case NAME -> "Rename item";
            case LINE -> "Edit line " + (d.line + 1);
            case NEW_LINE -> "New lore line";
        };
        ActionButton back = Kit.button("Back (don't save)", null, 200, (pl, r) -> showMain(pl));
        Kit.show(p, Kit.menu(Kit.t(title), body, inputs, buttons, 3, back));
    }

    private static Component hint(Draft.Mode mode, Player p) {
        TextComponent.Builder b = Component.text().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
        switch (mode) {
            case CODES -> {
                b.append(Component.text("Type colour codes in the text: "));
                String[] codes = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
                for (String c : codes) {
                    b.append(Component.text("&" + c + " ", TextColor.color(STANDARD.values().toArray(new Integer[0])
                            [Integer.parseInt(c, 16)])));
                }
                if (p.hasPermission("loresmith.format")) {
                    b.append(Component.text("&l", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                            .append(Component.text(" bold  "))
                            .append(Component.text("&o", NamedTextColor.WHITE).decorate(TextDecoration.ITALIC))
                            .append(Component.text(" italic  "))
                            .append(Component.text("&n", NamedTextColor.WHITE).decorate(TextDecoration.UNDERLINED))
                            .append(Component.text(" underline  "))
                            .append(Component.text("&m", NamedTextColor.WHITE).decorate(TextDecoration.STRIKETHROUGH))
                            .append(Component.text(" strike  "));
                }
                if (p.hasPermission("loresmith.color.hex")) {
                    b.append(Component.text("&#FF8800", TextColor.color(0xFF8800))).append(Component.text(" any colour  "));
                }
                b.append(Component.text("&r", NamedTextColor.WHITE)).append(Component.text(" reset"));
            }
            case SOLID -> b.append(Component.text("One colour for the whole text. Type a hex code or click Colour 1."));
            case GRADIENT -> b.append(Component.text("Fades from colour 1 to colour 2 (and on to colour 3 if you set it)."));
            case RAINBOW -> b.append(Component.text("Every letter a little further round the colour wheel. Use the sliders, then Preview."));
            case ALTERNATING -> b.append(Component.text("The colours take turns, every N letters."));
        }
        return b.build();
    }

    private static String modeTooltip(Draft.Mode m) {
        return switch (m) {
            case CODES -> "Type & colour codes yourself";
            case SOLID -> "One colour";
            case GRADIENT -> "Fade between 2 or 3 colours";
            case RAINBOW -> "Rainbow across the letters";
            case ALTERNATING -> "Colours take turns";
        };
    }

    /** Copies what's in the editor's boxes into the draft (only the boxes that were on screen). */
    private void capture(Player p, DialogResponseView r) {
        Session s = session(p);
        if (s == null) return;
        Draft d = s.draft;
        String text = r.getText("text");
        if (text != null) d.text = Kit.read(r, "text");
        for (int i = 0; i < 3; i++) {
            String c = r.getText("c" + i);
            if (c != null) d.colors[i] = c.trim();
        }
        Float f;
        if ((f = Kit.readFloat(r, "hue")) != null) d.hue = f;
        if ((f = Kit.readFloat(r, "spread")) != null) d.spread = f;
        if ((f = Kit.readFloat(r, "sat")) != null) d.saturation = f;
        if ((f = Kit.readFloat(r, "bright")) != null) d.brightness = f;
        if ((f = Kit.readFloat(r, "every")) != null) d.every = Math.round(f);
        Boolean b;
        if ((b = Kit.readBool(r, "bold")) != null) d.bold = b;
        if ((b = Kit.readBool(r, "italic")) != null) d.italic = b;
        if ((b = Kit.readBool(r, "underline")) != null) d.underline = b;
        if ((b = Kit.readBool(r, "strike")) != null) d.strikethrough = b;
        if ((b = Kit.readBool(r, "magic")) != null) d.obfuscated = b;
    }

    /** Shows the draft on a copy of the item, so its tooltip in the editor is a real preview. */
    private void applyPreview(ItemStack preview, Draft d, Component text) {
        ItemMeta meta = preview.getItemMeta();
        if (d.target == Draft.Target.NAME) {
            meta.customName(text);
        } else {
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            if (d.target == Draft.Target.LINE && d.line < lore.size()) {
                lore.set(d.line, text);
            } else {
                lore.add(text);
            }
            meta.lore(lore);
        }
        preview.setItemMeta(meta);
    }

    // ================================================================ saving

    private void save(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        Draft d = s.draft;
        Styler.Result r = Styler.render(d, p, plugin.settings().maxLength());
        if (r.text() == null) {
            d.notice = "&c" + r.error();
            showEditor(p);
            return;
        }
        Component text = r.text();
        ItemEdits.Snapshot before = edits.snapshot(item);
        switch (d.target) {
            case NAME -> {
                if (!p.hasPermission("loresmith.name")) {
                    d.notice = "&cYou can't rename items.";
                    showEditor(p);
                    return;
                }
                edits.writeName(item, text, d.recipe());
            }
            case NEW_LINE -> {
                List<ItemEdits.Line> lines = edits.lines(item.getItemMeta());
                long ours = lines.stream().filter(l -> l.ours).count();
                int limit = lineLimit(p);
                if (ours >= limit && !p.hasPermission("loresmith.admin")) {
                    back(p, "&cYou've used all " + limit + " of your lines on this item.");
                    return;
                }
                lines.add(new ItemEdits.Line(text, true, d.recipe()));
                edits.writeLines(item, lines);
                // Later saves edit this line instead of adding another.
                d.target = Draft.Target.LINE;
                d.line = lines.size() - 1;
            }
            case LINE -> {
                List<ItemEdits.Line> lines = edits.lines(item.getItemMeta());
                int i = locate(p, lines, d);
                if (i < 0) return;
                ItemEdits.Line l = lines.get(i);
                l.text = text;
                l.ours = true;
                l.recipe = d.recipe();
                edits.writeLines(item, lines);
            }
        }
        remember(s, before);
        plugin.log(p, item, (d.target == Draft.Target.NAME ? "name" : "lore line " + (d.line + 1)) + " = "
                + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.4f, 1.6f);
        back(p, "&aSaved.");
    }

    /** Finds the line the editor is working on, even if lines moved. Returns -1 (and explains) if it can't. */
    private int locate(Player p, List<ItemEdits.Line> lines, Draft d) {
        int i = -1;
        if (d.line >= 0 && d.line < lines.size() && ItemEdits.json(lines.get(d.line).text).equals(d.originalJson)) {
            i = d.line;
        } else {
            for (int j = 0; j < lines.size(); j++) {
                if (ItemEdits.json(lines.get(j).text).equals(d.originalJson)) {
                    i = j;
                    break;
                }
            }
        }
        if (i < 0) {
            // Saved once already in this editor: the line now holds the last saved text.
            if (d.line >= 0 && d.line < lines.size() && lines.get(d.line).ours) {
                i = d.line;
            } else {
                back(p, "&cThat line changed while you were editing it. Try again.");
                return -1;
            }
        }
        ItemEdits.Line l = lines.get(i);
        if (!l.ours && !plugin.settings().unmarkedEditable() && !p.hasPermission("loresmith.admin")) {
            back(p, "&7That line was added by the server, so it's locked.");
            return -1;
        }
        d.line = i;
        return i;
    }

    private void move(Player p, int dir) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        Draft d = s.draft;
        List<ItemEdits.Line> lines = edits.lines(item.getItemMeta());
        int i = locate(p, lines, d);
        if (i < 0) return;
        int j = i + dir;
        if (j < 0 || j >= lines.size()) {
            d.notice = "&7It's already at the " + (dir < 0 ? "top" : "bottom") + ".";
            showEditor(p);
            return;
        }
        boolean admin = p.hasPermission("loresmith.admin");
        if (!lines.get(j).ours && !plugin.settings().unmarkedEditable() && !admin) {
            d.notice = "&7Your line can't move past a locked line.";
            showEditor(p);
            return;
        }
        ItemEdits.Snapshot before = edits.snapshot(item);
        ItemEdits.Line tmp = lines.get(i);
        lines.set(i, lines.get(j));
        lines.set(j, tmp);
        edits.writeLines(item, lines);
        remember(s, before);
        d.line = j;
        d.originalJson = ItemEdits.json(edits.lines(item.getItemMeta()).get(j).text);
        d.notice = "&aMoved " + (dir < 0 ? "up" : "down") + ".";
        showEditor(p);
    }

    private void deleteLine(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        Draft d = s.draft;
        List<ItemEdits.Line> lines = edits.lines(item.getItemMeta());
        int i = locate(p, lines, d);
        if (i < 0) return;
        ItemEdits.Snapshot before = edits.snapshot(item);
        lines.remove(i);
        edits.writeLines(item, lines);
        remember(s, before);
        plugin.log(p, item, "deleted lore line " + (i + 1));
        back(p, "&aLine deleted. &7(Undo brings it back.)");
    }

    private void removeName(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        ItemEdits.Snapshot before = edits.snapshot(item);
        edits.writeName(item, null, null);
        remember(s, before);
        plugin.log(p, item, "removed name");
        back(p, "&aName removed.");
    }

    private void cycleGlint(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        Boolean now = meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null;
        Boolean next = now == null ? Boolean.TRUE : now ? Boolean.FALSE : null;
        ItemEdits.Snapshot before = edits.snapshot(item);
        edits.writeGlint(item, next);
        remember(s, before);
        showMain(p);
    }

    private void confirmClear(Player p) {
        ActionButton yes = Kit.button("&cRemove them", null, 150, (pl, r) -> clearOurs(pl));
        ActionButton no = Kit.button("Keep them", null, 150, (pl, r) -> showMain(pl));
        Kit.show(p, Kit.confirm(Kit.t("Remove your lines?"),
                List.of(Kit.body("Take off every lore line you added to this item? Lines added by the server stay. "
                        + "&7(Undo can bring them back.)")), yes, no));
    }

    private void clearOurs(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        ItemEdits.Snapshot before = edits.snapshot(item);
        List<ItemEdits.Line> lines = edits.lines(item.getItemMeta());
        lines.removeIf(l -> l.ours);
        edits.writeLines(item, lines);
        remember(s, before);
        plugin.log(p, item, "removed all their lines");
        back(p, "&aYour lines are gone.");
    }

    private void undo(Player p) {
        Session s = session(p);
        if (s == null) return;
        ItemStack item = target(p, s);
        if (item == null) return;
        ItemEdits.Snapshot last = s.undo.pollFirst();
        if (last == null) {
            back(p, "&7Nothing to undo.");
            return;
        }
        edits.restore(item, last);
        plugin.log(p, item, "undo");
        back(p, "&aUndone.");
    }

    private void remember(Session s, ItemEdits.Snapshot before) {
        s.undo.addFirst(before);
        while (s.undo.size() > UNDO_LIMIT) {
            s.undo.removeLast();
        }
    }

    // ================================================================ colour picker

    void showPalette(@NotNull Player p, int slot) {
        Session s = session(p);
        if (s == null) return;
        Draft d = s.draft;
        boolean hex = p.hasPermission("loresmith.color.hex");
        TextColor current = Styler.parseColor(d.colors[slot]);
        if (current == null) current = NamedTextColor.WHITE;

        List<DialogBody> body = new ArrayList<>();
        if (d.notice != null) {
            body.add(Kit.body(d.notice));
            d.notice = null;
        }
        body.add(Kit.body(Kit.t("Colour " + (slot + 1) + " is now ")
                .append(Component.text("██████ " + Styler.hex(current), current))
                .append(Kit.t(hex ? "&7. Click a colour, or type a hex code / drag the sliders and click Use."
                        : "&7. Click a colour."))));

        List<DialogInput> inputs = new ArrayList<>();
        if (hex) {
            float[] hsb = Color.RGBtoHSB(current.red(), current.green(), current.blue(), null);
            inputs.add(Kit.text("hex", "Hex code", Styler.hex(current), 16, 120));
            inputs.add(Kit.slider("h", "Hue", 0, 359, 1, Math.round(hsb[0] * 360)));
            inputs.add(Kit.slider("s", "Colourfulness %", 0, 100, 1, Math.round(hsb[1] * 100)));
            inputs.add(Kit.slider("v", "Brightness %", 0, 100, 1, Math.round(hsb[2] * 100)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        int w = 72;
        STANDARD.forEach((name, rgb) -> buttons.add(swatch(name, rgb, slot, w)));
        if (hex) {
            EXTRA.forEach((name, rgb) -> buttons.add(swatch(name, rgb, slot, w)));
            List<Integer> favs = favourites(p);
            for (int i = 0; i < favs.size(); i++) {
                buttons.add(swatch("★ Favourite " + (i + 1), favs.get(i), slot, w));
            }
            buttons.add(Kit.button("&fUse hex", "Use the hex code you typed", w, (pl, r) -> {
                TextColor c = Styler.parseColor(Kit.read(r, "hex"));
                if (c == null) {
                    s.draft.notice = "&c\"" + Kit.read(r, "hex") + "\" isn't a hex code. Try #FF8800.";
                    showPalette(pl, slot);
                    return;
                }
                pick(pl, slot, c.value());
            }));
            buttons.add(Kit.button("&fUse sliders", "Use the colour from the three sliders", w, (pl, r) -> {
                float h = orZero(Kit.readFloat(r, "h")) / 360f;
                float sa = orZero(Kit.readFloat(r, "s")) / 100f;
                float v = orZero(Kit.readFloat(r, "v")) / 100f;
                pick(pl, slot, Color.HSBtoRGB(h, sa, v) & 0xFFFFFF);
            }));
            TextColor fav = current;
            buttons.add(Kit.button("&e★ Save", "Save colour " + (slot + 1) + " as a favourite", w, (pl, r) -> {
                addFavourite(pl, fav.value());
                s.draft.notice = "&aSaved " + Styler.hex(fav) + " to your favourites.";
                showPalette(pl, slot);
            }));
            if (!favs.isEmpty()) {
                buttons.add(Kit.button("&c★ Clear", "Forget your favourite colours", w, (pl, r) -> {
                    pl.getPersistentDataContainer().remove(favouritesKey);
                    showPalette(pl, slot);
                }));
            }
        }
        ActionButton back = Kit.button("Back", null, 200, (pl, r) -> showEditor(pl));
        Kit.show(p, Kit.menu(Kit.t("Pick colour " + (slot + 1)), body, inputs, buttons, 4, back));
    }

    private ActionButton swatch(String name, int rgb, int slot, int width) {
        TextColor c = TextColor.color(rgb);
        return Kit.button(Component.text("■■■■■", c), name + " " + Styler.hex(c), width, (pl, r) -> pick(pl, slot, rgb));
    }

    private void pick(Player p, int slot, int rgb) {
        Session s = session(p);
        if (s == null) return;
        s.draft.colors[slot] = Styler.hex(TextColor.color(rgb));
        showEditor(p);
    }

    private static float orZero(@Nullable Float f) {
        return f == null ? 0 : f;
    }

    private List<Integer> favourites(Player p) {
        String raw = p.getPersistentDataContainer().get(favouritesKey, PersistentDataType.STRING);
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            TextColor c = Styler.parseColor(s);
            if (c != null) out.add(c.value());
        }
        return out;
    }

    private void addFavourite(Player p, int rgb) {
        List<Integer> favs = favourites(p);
        favs.remove((Integer) rgb);
        favs.addFirst(rgb);
        while (favs.size() > FAVOURITES_LIMIT) favs.removeLast();
        List<String> hexes = new ArrayList<>();
        for (int c : favs) hexes.add(Styler.hex(TextColor.color(c)));
        p.getPersistentDataContainer().set(favouritesKey, PersistentDataType.STRING, String.join(",", hexes));
    }

    private static String nice(String id) {
        StringBuilder b = new StringBuilder();
        for (String part : id.split("_")) {
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return b.toString().trim();
    }
}
