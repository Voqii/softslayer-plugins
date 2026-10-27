package com.voqii.loresmith;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small toolkit over Paper's dialog API (same pattern as the SlayerGuilds menus).
 * <p>
 * Every button waits for the server: after its action runs, the dialog is closed unless the action
 * showed another one, so the player never sits on a dead "waiting for response" screen.
 */
final class Kit {

    static final int BODY_WIDTH = 300;
    static final int INPUT_WIDTH = 250;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final ClickCallback.Options CLICK_OPTIONS = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(30)).build();

    /** Players who were shown a new dialog while a click was being handled. */
    private static final Set<UUID> SHOWN = new HashSet<>();

    private Kit() {
    }

    interface Action {
        void run(@NotNull Player player, @NotNull DialogResponseView response);
    }

    /** UI text written with {@code &} codes (never player input), italics off. */
    @NotNull
    static Component t(@NotNull String amp) {
        return AMP.deserialize(amp).decoration(TextDecoration.ITALIC, false);
    }

    @NotNull
    static DialogBody body(@NotNull Component text) {
        return DialogBody.plainMessage(text, BODY_WIDTH);
    }

    @NotNull
    static DialogBody body(@NotNull String amp) {
        return body(t(amp));
    }

    @NotNull
    static DialogBody item(@NotNull ItemStack item, @Nullable Component description) {
        var b = DialogBody.item(item).showTooltip(true).showDecorations(false);
        if (description != null) {
            b.description(DialogBody.plainMessage(description, BODY_WIDTH - 40));
        }
        return b.build();
    }

    @NotNull
    static ActionButton button(@NotNull Component label, @Nullable String tooltip, int width, @NotNull Action action) {
        ActionButton.Builder b = ActionButton.builder(label).width(width)
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) {
                        sync(() -> handle(player, response, action));
                    }
                }, CLICK_OPTIONS));
        if (tooltip != null && !tooltip.isEmpty()) {
            b.tooltip(t(tooltip));
        }
        return b.build();
    }

    @NotNull
    static ActionButton button(@NotNull String amp, @Nullable String tooltip, int width, @NotNull Action action) {
        return button(t(amp), tooltip, width, action);
    }

    private static void handle(Player player, DialogResponseView response, Action action) {
        if (!player.isOnline()) {
            return;
        }
        SHOWN.remove(player.getUniqueId());
        try {
            action.run(player, response);
        } catch (RuntimeException ex) {
            LoreSmith.get().getLogger().warning("Menu action failed for " + player.getName() + ": " + ex);
            close(player);
            throw ex;
        }
        if (!SHOWN.remove(player.getUniqueId())) {
            close(player);
        }
    }

    /** Buttons in a grid with an exit button (also what Esc runs) at the bottom. */
    @NotNull
    static Dialog menu(@NotNull Component title, @NotNull List<DialogBody> body, @NotNull List<DialogInput> inputs,
                       @NotNull List<ActionButton> buttons, int columns, @NotNull ActionButton exit) {
        DialogBase base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
        DialogType type = buttons.isEmpty()
                ? DialogType.notice(exit)
                : DialogType.multiAction(buttons).columns(columns).exitAction(exit).build();
        return Dialog.create(f -> f.empty().base(base).type(type));
    }

    /** Yes / no. Esc runs {@code no}, so {@code no} must always be the harmless one. */
    @NotNull
    static Dialog confirm(@NotNull Component title, @NotNull List<DialogBody> body,
                          @NotNull ActionButton yes, @NotNull ActionButton no) {
        DialogBase base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .build();
        return Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(yes, no)));
    }

    static void show(@NotNull Player player, @NotNull Dialog dialog) {
        SHOWN.add(player.getUniqueId());
        Backdrop.show(player);
        player.showDialog(dialog);
    }

    /**
     * Closes the menu. After a button click the client has swapped the dialog for its "Waiting for server
     * response" screen, and closeDialog() only closes dialog screens, so on its own it leaves the player on
     * that screen until its Back button unlocks after 5 seconds. A container-close packet makes the client
     * close whatever screen is open. Skipped when a real container is open (a button that opened a chest).
     */
    static void close(@NotNull Player player) {
        player.closeDialog();
        if (player.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            player.closeInventory();
        }
        Backdrop.hide(player);
    }

    // ---------------------------------------------------------------- inputs

    @NotNull
    static DialogInput text(@NotNull String key, @NotNull String label, @Nullable String initial, int maxLength, int width) {
        int max = Math.max(1, Math.max(maxLength, initial == null ? 0 : initial.length()));
        var b = DialogInput.text(key, t(label)).width(width).maxLength(max);
        if (initial != null && !initial.isEmpty()) {
            b.initial(initial);
        }
        return b.build();
    }

    @NotNull
    static DialogInput toggle(@NotNull String key, @NotNull String label, boolean initial) {
        return DialogInput.bool(key, t(label)).initial(initial).build();
    }

    @NotNull
    static DialogInput slider(@NotNull String key, @NotNull String label, float min, float max, float step, float initial) {
        float clamped = Math.max(min, Math.min(max, Math.round(initial / step) * step));
        return DialogInput.numberRange(key, t(label), min, max).step(step).initial(clamped).width(INPUT_WIDTH).build();
    }

    /** Text from a text input: control characters removed, never null. Not trimmed (spaces matter in lore). */
    @NotNull
    static String read(@NotNull DialogResponseView response, @NotNull String key) {
        String v = response.getText(key);
        return v == null ? "" : v.replaceAll("\\p{Cntrl}", "");
    }

    @Nullable
    static Boolean readBool(@NotNull DialogResponseView response, @NotNull String key) {
        return response.getBoolean(key);
    }

    @Nullable
    static Float readFloat(@NotNull DialogResponseView response, @NotNull String key) {
        return response.getFloat(key);
    }

    static void click(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    private static void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(LoreSmith.get(), r);
        }
    }
}
