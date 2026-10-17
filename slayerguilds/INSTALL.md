# SlayerGuilds — install (replacing SimpleClans)

Build `2.19.3-SNAPSHOT-3819171` (branch `dialog-ui`, commit `3819171`, of
github.com/Voqii/SlayerGuilds). It's a fork of SimpleClans (GPL-3.0).

**It still loads as `SimpleClans`.** The jar's file name doesn't matter. The server reads the name inside it,
so it reuses `plugins/SimpleClans/` (database, config), the `simpleclans.*` permissions and the
`%simpleclans_…%` placeholders. Existing guilds, members, banks and kills carry over with no migration.

Needs **Paper or Purpur 26.1.2 or newer** (no Spigot). Verified on Purpur 26.3 (build 2642) with BlockLocker
1.14.1 and the BlockLocker add-on.

## Where the pieces are

| | |
|---|---|
| `SimpleClans-2.19.3-guilds-3819171.jar` | The plugin. Attached to the [`built-2026-10-17`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-17) release; source in Voqii/SlayerGuilds (private), branch `dialog-ui` |
| `BlockLockerClanTags-1.0.0.jar` | Only if the server runs **BlockLocker**: lets a guild tag on a lock sign give the whole guild access. Source in [`../blocklocker-clantags`](../blocklocker-clantags), jar in the `deployed-2026-10-14` release |
| Discord bot | Guild chat ↔ Discord (optional, runs outside Minecraft). [`../ss-bot`](../ss-bot) |
| [`TESTING.md`](TESTING.md) | What to check after installing |

## Needs

- **Required:** Vault + an economy plugin (EssentialsX). Guild banks, warps, tax and bounties use money.
- **Optional:** mcMMO (feeds the Slayer Score), PlaceholderAPI, WorldGuard, BlockLocker, DiscordSRV
  (the Discord bot shows linked players' nicknames).

## Install

1. Stop the server.
2. **Back up `plugins/SimpleClans/`** (the whole folder: database + config).
3. **Check purge before the first start** (see below). This is the one step that can delete data.
4. Remove the old SimpleClans jar from `plugins/` (any `SimpleClans*.jar`). Two jars that both call themselves
   SimpleClans make the server load only one of them.
5. Copy `SimpleClans-2.19.3-guilds-3819171.jar` into `plugins/`, plus `BlockLockerClanTags-1.0.0.jar`
   if the server has BlockLocker.
6. Edit `plugins/SimpleClans/config.yml` as below, then start the server.
7. The log should show `Enabling SimpleClans v2.19.3-SNAPSHOT-3819171` with no SimpleClans errors, and a few
   seconds later `Slayer Score: loaded mcMMO levels for N players in X ms (off the main thread)`.
   A `WorldGuard6Provider` warning is normal.

**Upgrading from `a84e494`:** stop, swap the jar (remove the old one), start. No config or data changes.
`3819171` fixes a once-a-minute server freeze: the Slayer Score read every guild member's mcMMO level
from mcMMO's database on the main thread (a prod spark profile showed ticks up to 2 s). Offline levels are
now loaded once in the background and cached; the guild tax waits for a guild's score to finish loading
after a restart instead of charging it short.

### Purge (check first)

SimpleClans deletes inactive guilds and players **when the server starts**. The plugin's defaults are
aggressive (unverified guilds after 2 days, inactive guilds after 7, clanless players after 30). Keep the
values production already uses, or set them to 0 (= never) before the first start:

```yaml
purge:
  inactive-player-data-days: 0
  inactive-clan-days: 0
  unverified-clan-days: 0
```

### config.yml lines to change

A fresh install already has these. An **existing** SimpleClans config keeps its old values, so set them:

```yaml
settings:
  user-language-selector: false   # only the English text says "guild"; other languages still say "clan"
  server-name: '&6Softslayer'     # shown in list headers and the menu title (vanilla: '&bSimpleClans')
  disallowed-tags:
  - vip
  - clan
  - guild                         # add this line
commands:
  clan: guild                     # makes /guild the main command; /clan keeps working
economy:
  upkeep-enabled: false           # vanilla's own upkeep; SlayerGuilds has its own monthly tax (below)
```

New keys (menus, colours) are added automatically, and old rival/war settings are removed on first start.

## Other config files

Created in `plugins/SimpleClans/` on first start.

| File | What it controls | Check |
|---|---|---|
| `upkeep-config.yml` | Monthly guild tax (Slayer Score × multiplier) | **On by default.** First charge 30 days after the first start. A guild that can't pay is warned; if it misses the next one too it's **disbanded**. `enabled: false` turns it off |
| `deathvalley-config.yml` | Which world counts for PvP KDR and bounties | `world:` must be the PvP world's name (default `world_deathvalley`) |
| `warp-config.yml` | Guild warp prices, max warps, rent, ally access % | Default prices: 100k / 500k / 3M / 10M / 30M |
| `bounty-config.yml` | Minimum bounty, payout burn %, 30-day re-bounty cooldown | — |
| `power.yml` | Slayer Score weights | Expect to tune |
| `bridge-config.yml` | Chat link for the Discord bot | Off by default. See [`../ss-bot/README.md`](../ss-bot/README.md) |
| `clan-applications.yml` | Open guilds + pending applications (kept 14 days: `expire-days`) | Written automatically |

## Permissions

Unchanged node names, so existing LuckPerms grants keep working before and after the swap.

- Players: `simpleclans.anyone.*`, `simpleclans.member.*`, `simpleclans.leader.*` (leader nodes only do
  anything for an actual guild leader). Defaults already let any player create and lead a guild.
- Staff: `simpleclans.mod.*` (moderation, Staff tools menu) and `simpleclans.admin.*` (bank, purge, reload…).
  `simpleclans.*` grants all of it, so give it to staff only.
- **Test with a non-op account.** Ops have everything and hide permission problems.

## Known gaps (not bugs to report)

- Changing a guild's tag doesn't rewrite old kill history or "past guilds" entries (cosmetic).
- Other languages aren't rebranded (the language selector should be off).
- PlaceholderAPI placeholders keep their `simpleclans_` names, so TAB/chat setups keep working.
