# HeadMark

Adds a lore "watermark" as the last lore line on MoreMobHeads player heads dropped by kills in specific worlds
(e.g. `world_deathvalley` → "Death Valley 🔥"), matching the LootChest watermark style. Config: `config.yml`,
`/headmark reload` (perm `headmark.reload`, op).

Depends on MoreMobHeads. Put `MoreMobHeads.jar` in `lib/` before building (system-scope dependency).
MoreMobHeads can't be built from source (its `core-utils` dependency isn't published anywhere), so use the official jar.
