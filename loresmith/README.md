# LoreSmith

Name, colour and lore your items from a menu, with no client mod. `/lore` (aliases `/loresmith`, `/itemeditor`)
opens Minecraft's built-in dialog screens (1.21.6+ clients) for the item in your hand. Built as a friendlier
replacement for EssentialsX `/itemlore`, inspired by the FZMM client mod's text formatter.

- **Overview:** a tooltip preview of the item, its name, each lore line, add a line, enchantment shine on/off,
  undo, and remove all (with a confirm).
- **Line editor:** modes Codes (`&` codes by hand), Solid, Gradient (blended in OKLab), Rainbow and Alternating;
  bold/italic/underline/strikethrough/obfuscated toggles; preview, save, move and delete.
- **Colour picker:** 32 swatches, favourites saved per player, a hex box and HSB sliders.
- Bedrock players (Floodgate) get a message instead, since Bedrock has no dialog screens.

EssentialsX also has a `/lore` alias; it defers to LoreSmith's.

## Lines it didn't write

LoreSmith marks the lines it writes in the item's persistent data (`loresmith:lines`). Lines from anything else
(AcuteLoot rarity, HeadMark watermarks, CustomFishing stats, old `/itemlore` lines) are locked by default:
shown but not editable. `unmarked-lines: editable` in `config.yml` unlocks them. `blocked-materials` and
`blocked-namespaces` (e.g. `[customfishing, mythicmobs]`) stop whole items from being edited.

## Permissions

Everything a normal player needs defaults to true: `loresmith.use`, `.name`, `.lore`, `.glint`,
`.color.codes|solid|hex|gradient|rainbow|alternating`, `.format`. Without `loresmith.color.hex` only the 16
standard colours work. `loresmith.format.obfuscated` and `loresmith.admin` (edit locked lines and blocked items,
ignore limits, `/lore reload`) default to op. `loresmith.lines.<n>` raises the per-item line limit
(`max-lines`, default 5) for a rank.

`log-edits: true` writes every change to `plugins/LoreSmith/edits.log`.

## Build

    ./build.sh

Runs Maven in a JDK 25 container (`M2=` sets the cache folder) and writes `target/LoreSmith-<version>.jar`.
Paper/Purpur 26.3, no other plugin needed.

## Status

1.0.1 fixes the 5-second "Waiting for server response" screen after leaving the menu with a button. Tested with
a scripted bot client (`tools/mc-dialog-probe` in softslayer-plugins) and installed on the dev server; not yet on
production.
