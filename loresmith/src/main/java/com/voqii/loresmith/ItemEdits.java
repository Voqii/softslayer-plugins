package com.voqii.loresmith;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes an item's name and lore.
 * <p>
 * LoreSmith remembers which lore lines a player wrote by storing each line (as JSON, read back from
 * the item so it matches exactly) with the settings that made it, in the item's persistent data.
 * Every other line belongs to someone else (another plugin, or Essentials /itemlore from before)
 * and is locked unless the config says otherwise. Matching by content rather than position means
 * lines added or moved by other plugins can't make a player's line point at the wrong one.
 */
final class ItemEdits {

    /** One lore line, and whether (and how) LoreSmith wrote it. */
    static final class Line {
        Component text;
        boolean ours;
        @Nullable String recipe;

        Line(Component text, boolean ours, @Nullable String recipe) {
            this.text = text;
            this.ours = ours;
            this.recipe = recipe;
        }
    }

    /** Name, lore and shine as they were before a change, for Undo. */
    record Snapshot(@Nullable Component name, @Nullable List<Component> lore, @Nullable Boolean glint,
                    @Nullable List<String> lines, @Nullable String nameRecipe) {
    }

    private static final char SEP = '\u0001';
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final NamespacedKey linesKey;
    private final NamespacedKey nameKey;

    ItemEdits(@NotNull LoreSmith plugin) {
        this.linesKey = new NamespacedKey(plugin, "lines");
        this.nameKey = new NamespacedKey(plugin, "name");
    }

    @NotNull
    static String json(@NotNull Component c) {
        return GSON.serialize(c);
    }

    // ---------------------------------------------------------------- reading

    @NotNull
    List<Line> lines(@NotNull ItemMeta meta) {
        List<Component> lore = meta.lore();
        List<Line> out = new ArrayList<>();
        if (lore == null) {
            return out;
        }
        // json -> recipes (a list, in case the same line appears twice)
        Map<String, List<String>> ours = new HashMap<>();
        List<String> stored = meta.getPersistentDataContainer().get(linesKey, PersistentDataType.LIST.strings());
        if (stored != null) {
            for (String entry : stored) {
                int i = entry.indexOf(SEP);
                if (i > 0) {
                    ours.computeIfAbsent(entry.substring(i + 1), k -> new ArrayList<>()).add(entry.substring(0, i));
                }
            }
        }
        for (Component c : lore) {
            List<String> recipes = ours.get(json(c));
            if (recipes != null && !recipes.isEmpty()) {
                out.add(new Line(c, true, recipes.removeFirst()));
            } else {
                out.add(new Line(c, false, null));
            }
        }
        return out;
    }

    /** Saved settings for the item's name, if LoreSmith made the current name. */
    @Nullable
    String nameRecipe(@NotNull ItemMeta meta) {
        String entry = meta.getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
        Component name = meta.customName();
        if (entry == null || name == null) {
            return null;
        }
        int i = entry.indexOf(SEP);
        return i > 0 && entry.substring(i + 1).equals(json(name)) ? entry.substring(0, i) : null;
    }

    // ---------------------------------------------------------------- writing

    /** Writes {@code lines} to the item and records which ones are ours. */
    void writeLines(@NotNull ItemStack item, @NotNull List<Line> lines) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (Line l : lines) {
            lore.add(l.text);
        }
        meta.lore(lore.isEmpty() ? null : lore);
        item.setItemMeta(meta);

        // Read back: the stored JSON must match what the item really holds.
        meta = item.getItemMeta();
        List<Component> back = meta.lore();
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < lines.size() && back != null && i < back.size(); i++) {
            Line l = lines.get(i);
            if (l.ours && l.recipe != null) {
                entries.add(l.recipe + SEP + json(back.get(i)));
            }
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (entries.isEmpty()) {
            pdc.remove(linesKey);
        } else {
            pdc.set(linesKey, PersistentDataType.LIST.strings(), entries);
        }
        item.setItemMeta(meta);
    }

    void writeName(@NotNull ItemStack item, @Nullable Component name, @Nullable String recipe) {
        ItemMeta meta = item.getItemMeta();
        meta.customName(name);
        item.setItemMeta(meta);
        meta = item.getItemMeta();
        Component back = meta.customName();
        if (back != null && recipe != null) {
            meta.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, recipe + SEP + json(back));
        } else {
            meta.getPersistentDataContainer().remove(nameKey);
        }
        item.setItemMeta(meta);
    }

    /** Glint: null = the game decides (enchanted items shine), true = always, false = never. */
    void writeGlint(@NotNull ItemStack item, @Nullable Boolean glint) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(glint);
        item.setItemMeta(meta);
    }

    // ---------------------------------------------------------------- undo

    @NotNull
    Snapshot snapshot(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return new Snapshot(meta.customName(), meta.lore(),
                meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null,
                pdc.get(linesKey, PersistentDataType.LIST.strings()), pdc.get(nameKey, PersistentDataType.STRING));
    }

    /** Puts back only what LoreSmith changes (never durability, amount or enchants). */
    void restore(@NotNull ItemStack item, @NotNull Snapshot s) {
        ItemMeta meta = item.getItemMeta();
        meta.customName(s.name());
        meta.lore(s.lore());
        meta.setEnchantmentGlintOverride(s.glint());
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (s.lines() == null) pdc.remove(linesKey);
        else pdc.set(linesKey, PersistentDataType.LIST.strings(), s.lines());
        if (s.nameRecipe() == null) pdc.remove(nameKey);
        else pdc.set(nameKey, PersistentDataType.STRING, s.nameRecipe());
        item.setItemMeta(meta);
    }
}
