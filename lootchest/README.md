# LootChest 2.5.9-voqii

Fork of [Guarmanda/LootChest](https://github.com/Guarmanda/LootChest) 2.5.9 (GPL-3). Full changelog: `README-2.5.9-voqii.txt`.

- `patches/lootchest-2.5.9-voqii.patch`: our changes to upstream, applied on top of commit `656988c`.
  (26.x version parsing, world-name fix, per-chest respawn variance, DeleteListener despawn fix.)
- `patches/decentholograms-version-26.x.patch`: the shaded DecentHolograms `Version` enum entries.
- `26.3/`: how the current jar was actually built. It's a *surgical* rebuild: recompile only the changed classes
  against the previous voqii jar and inject them with `jar uf`.
  - `build.sh`: 26.3 support (`Version` enum `v26_3`/`paper_v26_3`, null-safe `FastReflection`, `DeleteListener`).
    Every new MC version needs a new `v26_N` entry in `src/fr/black_eyes/holograms/api/utils/reflect/Version.java`.
  - `build-mute.sh` + `mute/src`: `/lc mute` and `/lc unmute` silence chest-respawn broadcasts (state in
    `plugins/LootChest/mute.yml`, perms `lootchest.mute`/`lootchest.unmute`). Recompiling `Lootchest` needs Lombok ≥ 1.18.48
    and Lootin 11.3 on the classpath (in `tools/`, not committed).
  - BASE jar = the previous voqii jar, which is attached to the release.

The cleaner long-term fix is to apply the patches to upstream source and do a normal Maven build.
