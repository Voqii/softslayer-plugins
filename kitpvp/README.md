# KitPvP 2.3.13-voqii

Our patches for [jlftt/KitPvP](https://github.com/jlftt/KitPvP), a maintained fork of Cervinakuy's KitPvP.
Neither repo has a license, so the source isn't copied here: only our two patches, which apply on top of
jlftt's commit `1833630` (2.3.13). No jar is attached for the same reason. Build it yourself, see below.

## What the patches do

**`0001` Potions in kits load on MC 1.20.5+.** Stock KitPvP looks up a kit's potion by its effect. On modern
servers that lookup returns the long or strong variant, and KitPvP then fails with `Potion Type is not
upgradable` or `Extended potion type cannot be used directly`. The whole kit fails, so `/kp kit` and
`/kp preview` both break. Stock 2.3.12 failed on 60 of the 92 potion types on 26.3, including plain Swiftness,
Strength, Regeneration and Poison. The patch picks the potion type by name, and new kits save the potion type
itself, so Turtle Master and plain water/awkward bottles survive too. All 92 save and reload on 26.3. Old kit
files load without changes. The one exception is Turtle Master saved by an old version: it was stored as
Slowness, so re-save those kits.

**`0002` Kit drop points.** Picking a kit (sign, menu or `/kp kit`) teleports the player to a random drop point
in their world:

- `/kp adddrop` adds a drop point where you stand (permission `kp.command.addspawn`).
- `/kp deldrops` removes all of the world's drop points (permission `kp.command.delarena`).
- `Arena.TeleportOnKit: true` in `config.yml` turns it on. It's added automatically. Worlds with no drop
  points behave as before.

That gives the layout: `/kp addspawn` points in the lobby (where you land on entry, after a death, and with
`/kp spawn`), and `/kp adddrop` points out in the arena.

Upstream 2.3.13 also adds `kp.arena.bypass` (default: op). Players with it keep their inventory, effects and
gamemode when they enter the arena world.

## Build

    git clone https://github.com/jlftt/KitPvP.git && cd KitPvP
    git checkout 1833630
    git am /path/to/softslayer-plugins/kitpvp/patches/*.patch
    docker run --rm -u $(id -u):$(id -g) -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 \
      mvn -q -B -Dmaven.repo.local=/w/.m2 -DskipTests package

Install `target/KitPvP-2.3.13-voqii.jar`, not the `original-` one.

## Setting it up on SoftSlayer

- Lobby: stand on each lobby spot and run `/kp addspawn`. If old spawns are out on the arena floor, clear them
  first with `/kp delarena` (it only removes the spawn points, nothing else).
- Arena: stand where fighters should land and run `/kp adddrop`. Add several so players don't all land on the
  same block.
- Hotbar clock: it only runs what `Items.Leave.Commands` says, and the default is empty, so it does nothing.
  Set e.g. `- 'player: spawn'` and run `/kp reload`.
- `/kp spawn` takes your kit away on purpose (`Arena.ClearKitOnCommandSpawn`). It's the "back to the lobby"
  command, not the way into the arena.
- Signs: line 1 `[KitPvP]`, line 2 the type in lowercase (`kit`, `menu`, `refill`...), line 3 the kit name.

## Status (2026-10-20)

On the dev server. The potion fix was tested by saving and reloading every potion type on a throwaway 26.3
server. Drop points were tested end to end with a scripted bot: it picked a kit and landed on a drop point.
Not yet on production and not yet played by real players.

Testers need `kp.kit.*` and `kp.ability.*`. Without `kp.ability.*`, 10 of the 13 stock kits can't use their
ability items.
