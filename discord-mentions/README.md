# DiscordMentions

Typing `@MinecraftName` in game chat pings that player's linked Discord account (DiscordSRV add-on).

Why it exists: DiscordSRV only turns `@name` into a ping when `name` is the member's Discord display name/nickname.
It never looks at Minecraft names or `/discord link` links.

Resolution order: Minecraft name → linked Discord id (cache for online players, DB lookup off the main thread for
offline ones) → Discord username → nickname with spaces/symbols squeezed out (ambiguous = left alone).
Emits `<@id>`, which DiscordSRV passes through untouched.

- Compiled against the real DiscordSRV jar (`lib/`, see lib/README.md) because JDA/Adventure are relocated in it.
- Status: unit tested and loads on the dev server, but **not tested end to end** (dev server's DiscordSRV has no token).
- If `Experiment_MCDiscordReserializer_ToDiscord` is turned on in DiscordSRV, check that `<@id>` still survives.
