LootChest 2.5.9 - 26.1.2 / 26.2 / 26.3 compatibility + per-chest respawn variance
==========================================================================
Fork of Guarmanda/LootChest 2.5.9. Drop-in jar that:
  1) makes LootChest run on Minecraft 26.1.2, 26.2 and 26.3 (Purpur tested), and
  2) adds an opt-in +/- random spread to each chest's respawn timer.

DROP-IN: works on MC 1.21.x, 26.1.2, 26.2 AND 26.3 with NO config changes.
If the server version has no DecentHolograms NMS adapter (e.g. 26.x),
the plugin logs a warning and runs with holograms disabled instead of
crashing. Particles (incl. soul fire) are unaffected - they use the
Bukkit API. Swapping the jar does NOT touch plugins/LootChest configs
or data.yml; existing chests load unchanged (spread defaults to 0).

26.x fixes (all were upstream bugs, confirmed against the official jar):
  - Main.getCompleteVersion: Purpur jams the build number into the version
    string ("26.1.2.build.2592", "26.2.build.2610"); sanitized before parsing.
    Without this the plugin dies on enable with
    "NumberFormatException: For input string: 262build2610".
  - DecentHolograms Version: added paper_v26_1 + 26.1.1/26.1.2, added
    v26_2/paper_v26_2, and fixed the version extraction (was leaving
    Purpur's dotted build suffix).
  - LootChestUtils.getWorldName: was parsing CraftWorld.toString(), whose
    format changed in 26.x -> corrupted world names so in-session chests
    never respawned. Now uses world.getName().
  - 26.3: added v26_3/paper_v26_3 to the DecentHolograms Version enum, and
    FastReflection no longer NPEs when Version is unknown (it crashed plugin
    enable in particle init on any version the enum doesn't list).
  - DeleteListener (2026-10-03): chests are removed again after looting /
    first opening (RemoveEmptyChests / RemoveChestAfterFirstOpening). An
    earlier fork patch compared the closed inventory with the chest's saved
    loot template, which never matched; it now matches on block location.
  - Main.onEnable: only loads the hologram lib if an NMS adapter exists for
    the running version; degrades gracefully otherwise (no crash).
NOTE: floating-text holograms themselves need a DecentHolograms NMS adapter
for 26.x (none exists yet upstream - the bundled adapters stop at
paper_v1_21_R7). Particles + everything else work. The added enum entries
make 26.x a *recognised* version (Version.CURRENT is non-null) rather than
an unknown one, so the degradation is clean.

Timer variance feature (lootchest module):
  - per-chest randomRange (data.yml 'randomRange'), set in the /lc edit clock
    GUI; respawns at base +/- range each cycle. Backward compatible (0 = exact).

Verified on: Purpur 26.2 build 2610 (API 26.2.build.2610-stable) and
Purpur 26.3 build 2642 (enable + commands; in-game check pending), Java 25.
Plugin enables, logs the expected "Holograms are unavailable" warning,
reports "Server version: 1.26", and chest create/loot works in-game.

Files:
  - LootChest-2.5.9-voqii.jar          : drop-in plugin
  - lootchest-26.x-and-variance.patch: LootChest source diff vs 2.5.9
  - decentholograms-version-26.x.patch: DecentHolograms Version fix
    (clean upstream PR for Guarmanda - root fix that helps every plugin
     bundling his holograms lib). Apply on his DecentHolograms checkout;
     it applies cleanly to master as of 2026-07-30.

Build note: the jar was produced by recompiling the changed classes against
the official 2.5.9 jar (the full upstream build needs Guarmanda's private
GitHub-Packages deps). The patches are the canonical, reviewable source.
The DecentHolograms Version class is compiled into the shaded/relocated
package (fr.black_eyes.holograms.api.utils.reflect) at Java 21 bytecode,
matching the rest of the jar, and injected with "jar uf".

DEPLOY GOTCHA (Paper/Purpur): plugins are remapped into
plugins/.paper-remapped/ and the cache is keyed by jar hash in index.json.
A stale entry there can make the server keep running the OLD jar after you
swap it in. If a swap seems to have no effect, delete
plugins/.paper-remapped/<plugin>.jar and its index.json hash entry.
