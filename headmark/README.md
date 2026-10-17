# HeadMark

Adds a lore "watermark" as the last lore line on MoreMobHeads player heads dropped by kills in specific worlds
(e.g. `world_deathvalley` → "Death Valley 🔥"), matching the LootChest watermark style. Config: `config.yml`,
`/headmark reload` (perm `headmark.reload`, op).

Depends on MoreMobHeads. Put `MoreMobHeads.jar` in `lib/` before building (system-scope dependency).
MoreMobHeads can't be built from source (its `core-utils` dependency isn't published anywhere), so use the official jar.

## 1.1.0 (MC 26.3)

Built against paper-api `26.3.build.181-stable` (Java 25), `api-version: '26.3'`. Lore and messages now use
Adventure components instead of the deprecated `setLore`/`ChatColor`. A watermark line is only italic when it has
`&o` (the shipped Death Valley / ClassPvP lines do), so it looks the same as 1.0.0 did.

Build (from this folder, with `lib/MoreMobHeads.jar` in place):

    docker run --rm -u $(id -u):$(id -g) -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 mvn -Dmaven.repo.local=/w/.m2 package

Tested 2026-10-17 on Purpur 26.3 with production's MoreMobHeads `1.20_1.0.45`: a player killed by a player in a
listed world → the head's last lore line is the watermark (italic only with `&o`); unlisted world → no watermark.

Testing on an offline-mode server: MoreMobHeads 1.0.45 throws a NullPointerException on player kills when the victim
has no skin (`PlayerTextures.getSkin()` is null), so nothing drops. That's MoreMobHeads, not HeadMark, and can't
happen on an online-mode server. Give test accounts a skin texture on join to test there.
