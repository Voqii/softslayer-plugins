# SoftSlayer plugins

Everything custom that runs on (or was built for) the SoftSlayer Minecraft server: our own plugins, the Discord bot,
forks of other people's plugins, and the small patches that keep third-party jars running on new Minecraft versions.

This repo is the succession plan. If you're picking the server up from Voqii, start here.

## What's what

| Folder / repo | What it is | Ours or a fork | Status (2026-10-27) |
|---|---|---|---|
| [Voqii/SlayerGuilds](https://github.com/Voqii/SlayerGuilds) (separate repo, private) | SimpleClans fork rebranded to Guilds: clan power, warps, Discord bridge, dialog menus | fork of SimpleClans | **`dialog-ui` at `91d4be6` is the version headed to production** (rivals/wars removed, dialog menus, reworked guild applications: leaders are notified, applications survive restarts; `3819171` fixes a once-a-minute freeze from the Slayer Score reading mcMMO's database on the main thread; `720fdfe` adds per-member guild tax with a $1k minimum and $10k cap, makes unpaid tax → grace → disband the only way a guild ends, and makes "reset everyone's KDR" console-only; `53a1aa0` sets the default per-member price to $1,000; `aa4de8f` makes guild-board posting leader-only; `bec6755` fixes `/guild bounty set` and `check`, which errored with "wrong number of arguments"; `91d4be6` stops the 5-second "Waiting for server response" screen after closing a menu with a button). Install and test steps for swapping it in for vanilla SimpleClans: [`slayerguilds/`](slayerguilds). `guilds-rebrand` (older) is on the official test server. Neither is merged to master. Its `CLAUDE.md` has the full status. |
| [Voqii/minecraft](https://github.com/Voqii/minecraft) (separate repo), branch `26.3-brigadier` | CustomJoinMessages 1.5.6-voqii: join/leave/AFK messages | fork of milan25's plugin | 1.5.6 is the current build: 1.5.5 (white `*` on custom/paid AFK messages, grey on defaults, coloured nicknames) plus no "is no longer AFK" broadcast when EssentialsX clears a stale AFK flag on join or quit. `main` is still the old 1.5.1, so build from `26.3-brigadier`. Notes in [`customjoinmessages/`](customjoinmessages). |
| [`slayerguilds/`](slayerguilds) | install guide + test checklist for SlayerGuilds (the code itself is in Voqii/SlayerGuilds) | docs | written for `aa4de8f`; still accurate for `91d4be6` (bug fixes only) |
| [`ss-bot/`](ss-bot) | Discord bot that relays guild chat to each guild's own Discord channel | ours | working with SlayerGuilds' bridge |
| [`doorknob/`](doorknob) | chat slur filter: other players see "I'm a doorknob!" | ours | 1.0.5: staff with `doorknob.see` see the real text tagged [Filtered]. 1.0.4 filtering confirmed in-game. |
| [`voicechat-discord/`](voicechat-discord) | Discord voice ↔ Simple Voice Chat bridge. `/dvc call` puts a player who has Simple Voice Chat into a Discord voice call (they hear the channel, it hears their mic), and several people can talk on Discord at once | fork of amsam0/voicechat-discord 3.2.1 (MIT) | 3.2.1-call.2: someone in the Discord voice channel has to let each player in (Let in / Deny buttons in the channel's chat), and the channel can see who's listening. Unit, end-to-end and real-Discord tests pass. Tried on production 2026-10-20: the Let in button worked, but the call couldn't connect because production's host (BloomHost) blocks the UDP that Discord voice needs; that's a host network setting, not the plugin. Needs its own Discord bot. Everything is in its `SS-FORK.md`. |
| [`slayerbot/`](slayerbot) | chat FAQ bot: answers common questions (discord, money, claims, the end...) plus a few in-jokes. Replaces ancHelperBot | ours (rewrite of Ancienty's ancHelperBot, no code reused) | 2.1.0 confirmed working in-game on the dev server. Its config is converted from production's ancHelperBot config. Remove the ancHelperBot jar when installing, or both will answer. |
| [`discord-mentions/`](discord-mentions) | `@MinecraftName` in game chat pings the linked Discord user, never a same-named stranger (DiscordSRV add-on) | ours | 1.1.0, loads fine but never tested end to end with a real bot token |
| [`chat-translate/`](chat-translate) | adds an English translation under non-English chat. Plugin + local LLM service (Gemma 3 4B on llama.cpp) | ours | testing on the dev server |
| [`customfishing-minigames/`](customfishing-minigames) | CustomFishing add-ons: DDR arrow game + Chrome-dino runner | ours (CustomFishing expansions, no fork) | DDR 1.0.2 and Dino 1.0.5 shipped |
| [`headmark/`](headmark) | lore watermark on MoreMobHeads player heads dropped in Death Valley / ClassPvP | ours | 1.1.0 for MC 26.3, tested with production's MoreMobHeads 1.0.45 on a throwaway 26.3 server; not installed anywhere yet |
| [`blocklocker-clantags/`](blocklocker-clantags) | lets BlockLocker signs use a clan **tag** instead of the full clan name | ours | 1.0.0 |
| [`simplestaffchat/`](simplestaffchat) | SimpleStaffChat 4.4.1-voqii: staff, dev and adult chat channels (the "admin chat" channel is the 18+ adult chat) | fork of RefracDevelopment/SimpleStaffChat 4.3.5 (MIT, upstream archived) | runs on 26.3, plus bug and security fixes. 4.4.1 takes `/s` back from TimingSystem, which grabs it for `/settings`. `/a` needs a `commands.yml` alias because mcMMO owns it; see its `VOQII-FORK.md` |
| [`kudos/`](kudos) | Kudos 7.1.1-voqii: players give each other kudos (`/kudo`) | fork of Urbance/Kudos 7.1.0 (GPL-3, now on Codeberg) | fixes the chat lag you get with InteractiveChat + Kudos: kudos placeholders now read from memory instead of running ~5 queries on a fresh connection every time. 7.1.1 also stops suggesting vanished players in tab completion. 7.1.0 on the dev server since 2026-10-19, neither on production yet. See its `VOQII-FORK.md` |
| [`vanish-query/`](vanish-query) | VanishQuery 1.0.0: hides vanished players from the server's query player list, which the Discord #online-players status message reads (EssentialsX only hides them from the server-list ping) | ours | tested on a throwaway 26.3 server, not installed anywhere yet. No config |
| [`kitpvp/`](kitpvp) | KitPvP 2.3.15-voqii (the jar is renamed SlayerPVP on the server): kit-based PvP arena with signs, kit menu and abilities | patches for jlftt/KitPvP (no license upstream, so patches only, no source or jar) | fixes kits with potions on 26.3, adds kit drop points (picking a kit in the lobby teleports you into the arena), built-in combat logging (logging out mid-fight counts as a death, `/spawn` etc. blocked in combat) and `kp.inventory.keep` so staff watching fights aren't wiped. 2.3.13 on the dev server since 2026-10-20; 2.3.14/2.3.15 bot-tested, not on production yet. Build steps and setup in its README |
| [`lootchest/`](lootchest) | LootChest 2.5.9-voqii: runs on MC 26.x, per-chest respawn variance, despawn fix, `/lc mute` | fork of Guarmanda/LootChest (GPL-3) | live (`-26.3-mute` jar) |
| [`jar-patches/`](jar-patches) | one-class fixes injected into BetterSleeping, BlueSlimeCore, DragonDropElytra and LibsDisguises | patches to third-party jars | all were needed for MC 26.2. Check whether upstream has caught up before you re-apply them. |
| [`loresmith/`](loresmith) | LoreSmith 1.0.1: `/lore` opens menus to rename items and write coloured lore (gradients, rainbow, colour picker), no client mod needed | ours | on the dev server, tested with the bot client; not on production |
| [`timing-ghost/`](timing-ghost) | TimingGhost 1.0.0: race a see-through ghost of your best TimingSystem time-trial run (`/ttghost`) | ours (TimingSystem add-on) | on the dev server, ghost visuals not yet checked by a person; not on production |
| [`timing-records/`](timing-records) | TimingRecords 1.0.0: announces new #1 TimingSystem time-trial records in chat | ours (TimingSystem add-on) | on the dev server; not on production |
| [`tools/mc-dialog-probe/`](tools/mc-dialog-probe) | scripted bot client for testing SlayerGuilds' dialog/chest menus | ours (test tool) | — |

Everything else on the server (EssentialsX, LuckPerms, WorldGuard, mcMMO, etc.) is stock upstream, so it isn't in here.

Built jars of our own plugins, as deployed on 2026-10-14, are attached to the
[`deployed-2026-10-14` release](https://github.com/Voqii/softslayer-plugins/releases/tag/deployed-2026-10-14).
Jars built since then are in [`built-2026-10-15`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-15)
(Doorknob 1.0.5, DiscordMentions 1.1.0, CustomJoinMessages 1.5.5-voqii), and SlayerBot 2.1.0 plus SlayerGuilds
`a84e494` are in [`built-2026-10-16`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-16).
voicechat-discord 3.2.1-call.1 and SlayerGuilds `3819171` (the lag fix) and `53a1aa0` (tax/purge from `720fdfe` + the $1,000 per-member default) and `aa4de8f` (leader-only board posts), plus HeadMark 1.1.0, are in
[`built-2026-10-17`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-17).
Kudos 7.1.0-voqii is in [`built-2026-10-19`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-19).
Kudos 7.1.1-voqii, VanishQuery 1.0.0, SlayerGuilds `91d4be6`, CustomJoinMessages 1.5.6-voqii, voicechat-discord 3.2.1-call.2,
SimpleStaffChat 4.4.1-voqii, LoreSmith 1.0.1, TimingGhost 1.0.0 and TimingRecords 1.0.0 are in
[`built-2026-10-27`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-27). KitPvP has no jar (no license upstream).
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
- Brightness Aura 1.6.6 had no 26.3 build either: [`client-mods/brightness-aura/`](client-mods/brightness-aura) has a
  two-instruction bytecode patch for the 26.2 jar (`python3 patch.py in.class out.class`). Use the official build once it exists.

## Building

The build machine has no JDK or Maven installed. Everything compiles inside Docker:

- Maven projects (`doorknob`, `discord-mentions`, `slayerbot`, `kudos`, `timing-ghost`, `timing-records`, SlayerGuilds, CustomJoinMessages; `vanish-query` and `loresmith` have their own `build.sh`; the two timing add-ons need TimingSystem's jar in `lib/` first):
  `docker run --rm -u $(id -u):$(id -g) -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 mvn -Dmaven.repo.local=/w/.m2 package`
  (run it from the project folder; jar lands in `target/`).
- `build.sh` projects (`chat-translate/plugin`, `customfishing-minigames`, `blocklocker-clantags`, `lootchest/26.3`)
  compile with `eclipse-temurin:25-jdk` against the dev server's `/data/libraries`.
  A Paper/Purpur server jar (paperclip) doesn't contain the API classes. The scripts `docker cp` the libraries out of
  the running server container (`CONTAINER=mc-dev` by default) or take a `LIBS=` path.
- `voicechat-discord` is Gradle plus a Rust library (Discord voice). Both build in Docker; the steps and its
  test suites are in `voicechat-discord/SS-FORK.md`.
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
