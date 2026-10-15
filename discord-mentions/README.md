# DiscordMentions

Decides who `@Name` in game chat pings on Discord (DiscordSRV add-on). Only the copy sent to Discord changes.

Why it exists: on its own, DiscordSRV only knows Discord nicknames. Its matching is
`(?<!<)` + `@<nickname>`, case-insensitive, with no word boundary at the end. So:
- `@MinecraftName` never reached the linked account.
- It pinged whoever's Discord nickname happened to equal the Minecraft name. In practice that was an inactive member
  who shares Voqii's name.
- A nickname also matches as a prefix (nickname "Vo" fires on `@Voqii`) and inside email addresses.

How 1.1.0 resolves `@Name`:
1. **A Minecraft name always wins.** If anyone who has ever joined is called Name, only their `/discord link`ed account
   is pinged. If they aren't linked, or their account has left the guild, **nobody** is pinged.
2. Only when no Minecraft player has that name: Discord username, nickname with spaces/symbols squeezed out
   (`@YosemiteSam`), or a nickname typed in full (`@Yosemite Sam`). The longest match wins; a tie pings nobody.
3. Every other `@` (unknown names, `@everyone`, emails, one-letter mentions) gets a zero-width space after it
   (`@\u200B`), so DiscordSRV's own matching can't guess.

The sender gets a private grey line saying who was pinged ("Pinged Voqii on Discord (Alex / @alex_d).") or why
nobody was. Players need to `/discord link` to be pingable by their Minecraft name.

- Compiled against the real DiscordSRV jar (`lib/`, see lib/README.md) because JDA/Adventure are relocated in it.
- Status (1.1.0, 2026-10-15): 21-case test against mocked JDA members, also run through a copy of DiscordSRV's
  matching, all passing. Loads on the dev server. **Not tested end to end with a real bot token.**
- If `Experiment_MCDiscordReserializer_ToDiscord` is turned on in DiscordSRV, check that `<@id>` and the zero-width
  space still survive.
