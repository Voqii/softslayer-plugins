# SoftSlayer plugins

Everything custom that runs on (or was built for) the SoftSlayer Minecraft server: our own plugins, the Discord bot,
forks of other people's plugins, and the small patches that keep third-party jars running on new Minecraft versions.

This repo is the succession plan. If you're picking the server up from Voqii, start here.

## What's what

| Folder / repo | What it is | Ours or a fork | Status (2026-10-15) |
|---|---|---|---|
| [Voqii/SlayerGuilds](https://github.com/Voqii/SlayerGuilds) (separate repo, private) | SimpleClans fork rebranded to Guilds: clan power, warps, Discord bridge, dialog menus | fork of SimpleClans | `guilds-rebrand` is on the official test server. `dialog-ui` (rivals/wars removed, dialog menus) is in testing. Neither is merged to master. Its `CLAUDE.md` has the full status. |
| [Voqii/minecraft](https://github.com/Voqii/minecraft) (separate repo), branch `26.3-brigadier` | CustomJoinMessages 1.5.5-voqii: join/leave/AFK messages | fork of milan25's plugin | 1.5.5 (white `*` on custom/paid AFK messages, grey on defaults, coloured nicknames) on the dev server; production still has 1.5.4. `main` is still the old 1.5.1, so build from `26.3-brigadier`. Notes in [`customjoinmessages/`](customjoinmessages). |
| [`ss-bot/`](ss-bot) | Discord bot that relays guild chat to each guild's own Discord channel | ours | working with SlayerGuilds' bridge |
| [`doorknob/`](doorknob) | chat slur filter: other players see "I'm a doorknob!" | ours | 1.0.5: staff with `doorknob.see` see the real text tagged [Filtered]. 1.0.4 filtering confirmed in-game. |
| [`discord-mentions/`](discord-mentions) | `@MinecraftName` in game chat pings the linked Discord user, never a same-named stranger (DiscordSRV add-on) | ours | 1.1.0, loads fine but never tested end to end with a real bot token |
| [`chat-translate/`](chat-translate) | adds an English translation under non-English chat. Plugin + local LLM service (Gemma 3 4B on llama.cpp) | ours | testing on the dev server |
| [`customfishing-minigames/`](customfishing-minigames) | CustomFishing add-ons: DDR arrow game + Chrome-dino runner | ours (CustomFishing expansions, no fork) | DDR 1.0.2 and Dino 1.0.5 shipped |
| [`headmark/`](headmark) | lore watermark on MoreMobHeads player heads dropped in Death Valley / ClassPvP | ours | 1.0.0 (source from the laptop) |
| [`blocklocker-clantags/`](blocklocker-clantags) | lets BlockLocker signs use a clan **tag** instead of the full clan name | ours | 1.0.0 |
| [`lootchest/`](lootchest) | LootChest 2.5.9-voqii: runs on MC 26.x, per-chest respawn variance, despawn fix, `/lc mute` | fork of Guarmanda/LootChest (GPL-3) | live (`-26.3-mute` jar) |
| [`jar-patches/`](jar-patches) | one-class fixes injected into BetterSleeping, BlueSlimeCore, DragonDropElytra and LibsDisguises | patches to third-party jars | all were needed for MC 26.2. Check whether upstream has caught up before you re-apply them. |
| [`tools/mc-dialog-probe/`](tools/mc-dialog-probe) | scripted bot client for testing SlayerGuilds' dialog/chest menus | ours (test tool) | — |

Everything else on the server (EssentialsX, LuckPerms, WorldGuard, mcMMO, etc.) is stock upstream, so it isn't in here.

Built jars of our own plugins, as deployed on 2026-10-14, are attached to the
[`deployed-2026-10-14` release](https://github.com/Voqii/softslayer-plugins/releases/tag/deployed-2026-10-14).
Jars built since then are in [`built-2026-10-15`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-15)
(Doorknob 1.0.5, DiscordMentions 1.1.0, CustomJoinMessages 1.5.5-voqii).
No third-party jars are committed or attached. Download those from their own pages.

## Client mods staff use (separate forks)

FZMM (head/item tools) and its library owo-lib had no builds for MC 26.2/26.3, so we ported them. Each fork has
`voqii/26.2` and `voqii/26.3` branches. Compare them to upstream to see exactly what was patched.

- [Voqii/FZMM-Mod](https://github.com/Voqii/FZMM-Mod): `a281a1a` 26.2 port, `0c8f003` duplicate-stack crash fix in the
  "Useful block states" tab, `d6a8507` 26.3 port.
- [Voqii/owo-lib](https://github.com/Voqii/owo-lib): `voqii/26.2` = kouhe3's community 26.2 port + our zero-size scissor
  guard (`a0d374c6`). `voqii/26.3` = Raghav314's 26.3 port + one commit so it publishes locally (`892f7f06`).
- Build order: owo-lib first (`./gradlew publishToMavenLocal`), then FZMM (`./gradlew build`). FZMM's build looks in
  `mavenLocal()` for `owo-lib 0.13.1+26.3`. Once upstream ships official 26.x builds, use those instead.

## Building

The build machine has no JDK or Maven installed. Everything compiles inside Docker:

- Maven projects (`doorknob`, `discord-mentions`, SlayerGuilds, CustomJoinMessages):
  `docker run --rm -u $(id -u):$(id -g) -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 mvn -Dmaven.repo.local=/w/.m2 package`
  (run it from the project folder; jar lands in `target/`).
- `build.sh` projects (`chat-translate/plugin`, `customfishing-minigames`, `blocklocker-clantags`, `lootchest/26.3`)
  compile with `eclipse-temurin:25-jdk` against the dev server's `/data/libraries`.
  A Paper/Purpur server jar (paperclip) doesn't contain the API classes. The scripts `docker cp` the libraries out of
  the running server container (`CONTAINER=mc-dev` by default) or take a `LIBS=` path.
- Target Java 21 bytecode (`--release 21`). MC 26.x APIs themselves need a JDK 25 compiler.

## Things that will bite you

- **Netty-hooking plugins can kill every login.** If a plugin injects into the network pipeline and then fails to enable
  (seen with floodgate on 26.3, and AxiomPaper when CoreProtect wouldn't load), every connection dies, status pings
  included. It looks like a DNS or tunnel problem but it isn't. After any Minecraft version bump, check that a status ping works.
- **Never install FastAsyncWorldEdit next to WorldEdit.** FAWE claims the same main class. It replaces WorldEdit, it doesn't run beside it.
- **ViaVersion and ViaBackwards must be updated together** or ViaBackwards' protocols fail to load.
- **EssentialsX chat replaces the chat renderer at HIGHEST and ignores the message argument.** Any plugin that rewrites chat
  has to listen at MONITOR and edit the rendered component (see Doorknob's source).
- **SlayerGuilds/SimpleClans purge settings:** with upstream defaults (7/2/30 days), the first boot on production's database
  would delete inactive clans and roughly 850 clanless players. Check the `purge:` values before swapping jars on production.
- **Redeploy jars under a new filename.** Overwriting a jar in place can make the JVM serve stale classes from its cached JarFile.
- Purpur puts the build number into the version string (`26.2.build.2610`). Several plugins' version parsers choke on
  it, or on the new `26.x` numbering in general. That's what most of `jar-patches/` and the LootChest fork fix.

## Handover checklist

- Add the new maintainer as a collaborator here and on **Voqii/SlayerGuilds** (it's private).
- Bot tokens and bridge secrets are **not** in git. Make new ones: Discord Developer Portal → reset token, and set
  `secret` in `plugins/SimpleClans/bridge-config.yml` to match the bot's `.env`.
- The production server is on a Pterodactyl panel host that has no shell and can't accept inbound connections, so
  SlayerGuilds runs its bridge in `mode: remote` and pushes to the bot (`BRIDGE_MODE=listen`), which has to be reachable over HTTPS.
