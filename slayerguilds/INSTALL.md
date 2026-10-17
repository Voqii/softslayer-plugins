# SlayerGuilds — install (replacing SimpleClans)

Build `2.19.3-SNAPSHOT-720fdfe` (branch `dialog-ui`, commit `720fdfe`, of
github.com/Voqii/SlayerGuilds). It's a fork of SimpleClans (GPL-3.0).

**It still loads as `SimpleClans`.** The jar's file name doesn't matter. The server reads the name inside it,
so it reuses `plugins/SimpleClans/` (database, config), the `simpleclans.*` permissions and the
`%simpleclans_…%` placeholders. Existing guilds, members, banks and kills carry over with no migration.

Needs **Paper or Purpur 26.1.2 or newer** (no Spigot). Verified on Purpur 26.3 (build 2642) with BlockLocker
1.14.1 and the BlockLocker add-on.

## Where the pieces are

| | |
|---|---|
| `SimpleClans-2.19.3-guilds-720fdfe.jar` | The plugin. Attached to the [`built-2026-10-17`](https://github.com/Voqii/softslayer-plugins/releases/tag/built-2026-10-17) release; source in Voqii/SlayerGuilds (private), branch `dialog-ui` |
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
5. Copy `SimpleClans-2.19.3-guilds-720fdfe.jar` into `plugins/`, plus `BlockLockerClanTags-1.0.0.jar`
   if the server has BlockLocker.
6. Edit `plugins/SimpleClans/config.yml` as below, then start the server.
7. The log should show `Enabling SimpleClans v2.19.3-SNAPSHOT-720fdfe` with no SimpleClans errors, a
   `Guild tax — on: $… per member, min $1,000, max $10,000…` line, and a few
   seconds later `Slayer Score: loaded mcMMO levels for N players in X ms (off the main thread)`.
   A `WorldGuard6Provider` warning is normal.

**Upgrading from `a84e494` or `3819171`:** stop, swap the jar (remove the old one), set `upkeep-config.yml` as in
[Guild tax](#guild-tax) below, start.

`720fdfe`: guild tax can be per member (the new default), clamped to a minimum and a cap; guilds can no
longer be wiped by a restart (see Purge); "Reset everyone's KDR" is console-only.

`3819171` fixes a once-a-minute server freeze: the Slayer Score read every guild member's mcMMO level
from mcMMO's database on the main thread (a prod spark profile showed ticks up to 2 s). Offline levels are
now loaded once in the background and cached; the guild tax waits for a guild's score to finish loading
after a restart instead of charging it short.

### Purge (check first)

**Guilds are never purged.** Upstream SimpleClans deletes inactive guilds outright when the server starts;
this build ignores `inactive-clan-days` and `unverified-clan-days` (it logs that it does). The only automatic
way a guild ends is unpaid tax: a missed payment puts it in a grace cycle (warning on the guild board), and it
is disbanded only if it misses the next one too. An abandoned guild still goes that way once its bank runs dry.
Upstream's own daily upkeep, which disbanded on the first short payment, won't start even if
`economy.upkeep-enabled` is on.

**Player purge still works** and runs at startup: players inactive longer than `inactive-player-data-days`
lose their guild record (they leave their guild, and their kills/deaths are deleted). **Leaders are never
purged**, so it can't empty a guild. 0 = off.

```yaml
purge:
  inactive-player-data-days: 60   # pick a value; 0 = never
  inactive-clan-days: 0           # ignored by this build
  unverified-clan-days: 0         # ignored by this build
```

Back up `plugins/SimpleClans/` first: the first boot with player purge on removes everyone past the limit at
once. On production's 2026-10-08 data (40 guilds, 1,688 players) that would have been:

| Setting | Guild members removed (non-leaders) | Guildless records deleted | Inactive leaders kept |
|---|---|---|---|
| 30 days | 79 | 1,042 | 34 |
| 60 days | 43 | 447 | 20 |
| 90 days | 4 | 175 | 9 |

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
  upkeep-enabled: false           # vanilla's own upkeep; ignored by this build, which has its own monthly tax
```

### Guild tax

`plugins/SimpleClans/upkeep-config.yml`. Missing keys use the defaults shown.

```yaml
enabled: true
interval-days: 30
grace-cycles: 1          # missed payments allowed before disband; anything below 1 is treated as 1
tax:
  basis: members         # members = per-member x member count; score = multiplier x Slayer Score
  per-member: 500.0      # $ per member per month (members basis)
  multiplier: 1.0        # score basis only
  min: 1000.0            # every guild pays at least this
  max: 10000.0           # cap; 0 = no cap
```

So with `per-member: 500`, a 1-2 member guild pays the $1,000 minimum, 10 members pay $5,000, and 20+
members pay the $10,000 cap. The Slayer Score still ranks the leaderboard either way.

New keys (menus, colours) are added automatically, and old rival/war settings are removed on first start.

## Other config files

Created in `plugins/SimpleClans/` on first start.

| File | What it controls | Check |
|---|---|---|
| `upkeep-config.yml` | Monthly guild tax (see [Guild tax](#guild-tax)) | **On by default.** First charge 30 days after the first start. A guild that can't pay is warned; if it misses the next one too it's **disbanded**. `enabled: false` turns it off |
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
