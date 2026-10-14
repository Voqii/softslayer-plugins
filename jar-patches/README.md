# Jar patches

Third-party plugins that broke on MC 26.2 (mostly the new `26.x` version numbering). Each fix is one class,
recompiled and injected into the original jar with `jar uf`. Check upstream first. A newer official release may
already work, and then you don't need any of this.

| Plugin | Class | Fix |
|---|---|---|
| BetterSleeping 4 | `be/betterplugins/bettersleeping/shade/betteryaml/files/TempFileCopier` | copied config with `while (reader.ready())`, which truncated on Paper's resource streams. Now `readLine() != null` |
| BlueSlimeCore (CombatLogX dep) | `com/github/sirblobman/api/utility/VersionUtility` | "26.2" got truncated to "26" and every `getMinorVersion() >= N` gate broke. Now the first number counts as the generation for non-1.x versions |
| DragonDropElytra | `com/github/joelgodofwar/dde/DragonDropElytra` | version gate rejected 26.x; `Enchantment.LOOT_BONUS_MOBS` → `LOOTING` |
| LibsDisguises 11.0.18 | `.../reflection/NmsVersion`, `.../reflection/v26_R1/ReflectionManager` | map 26.2 onto the v26_R1 adapter; `ENTITY_COUNTER` moved from `Entity` to `ServerLevel`; registry/generic fixes |

The DragonDropElytra patch is against upstream source (github.com/JoelGodOfWar/DragonDropElytra, `src/`).
The others are diffs against the class as decompiled by **CFR 0.152** from the official jar, since we only had jars.
To re-apply: decompile that one class with CFR, `patch` it, then
`javac --release 21 -cp <original jar>:<server libraries> -d out File.java` and `jar uf Plugin.jar -C out .`.
