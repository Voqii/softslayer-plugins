# TimingGhost

Companion plugin for [TimingSystem](https://github.com/FrostHexABG/TimingSystem) (built against 3.5). While you do a time trial, you race a see-through ghost of your best run on that track. Only you can see your ghost.

- Records the boat (or the player, on parkour and elytra tracks) every tick from TimingSystem's start event, timed against TimingSystem's own clock.
- Saves the run when it beats your previous ghost (`plugins/TimingGhost/ghosts/<trackId>/<uuid>.ghost`).
- Draws the ghost with display entities: a glass boat (or figure) with your head and a name tag. It has no hitbox and never collides with anyone.
- Works on lap tracks where the start line is the finish (TimingSystem fires no new start event there, so the plugin spots the timer resetting).
- Follows the track when it's moved with `/te move`, including to another world (1.2.0). Each ghost stores the track's spawn block from when it was recorded, and plays back shifted by however far the spawn has moved since.
- Ghosts saved before 1.2.0 have no stored spawn. The first time one is raced, the plugin works out how far the track moved by finding the shift that puts the recorded path back through the track's current start, every checkpoint and the finish (to within a block), then saves it so this only happens once. If no shift fits, for example because the track was rebuilt rather than moved, that ghost is hidden instead of driving the old location, the player is told, and their next finish records a new one even if it's slower.

Ghosts only exist for runs set after the plugin is installed. TimingSystem only stores times, not paths.

## Commands

| Command | Who | |
|---|---|---|
| `/ttghost pb` | everyone | race your own best run (default) |
| `/ttghost best` | everyone | race the fastest ghost on the track |
| `/ttghost next` | everyone | race the player ranked right above you on the track (8th races 7th). Players above without a ghost are skipped; #1 races their own best |
| `/ttghost off` | everyone | no ghost |
| `/ttghost clear <track> [player]` | `timingghost.admin` | delete one player's ghost, or every ghost on the track |
| `/ttghost reload` | `timingghost.admin` | reload config.yml and the ghost files |

Alias `/tghost`. `/ghost` itself belongs to TimingSystem (race ghosting in heats).

## Build

    docker run --rm -v $PWD:/src -v m2cache:/root/.m2 -w /src maven:3.9-eclipse-temurin-25 mvn -q -B package

`lib/TimingSystem-3.5.jar` is the upstream release jar (sha256 b4362608ca596162882c036d1d6c04a2d82eee1df1b56053ca66b50a25d41a37).
