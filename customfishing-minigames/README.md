# CustomFishing-DDR

A Dance-Dance-Revolution style minigame (`game-type: ddr`) for XiaoMoMi's CustomFishing,
built as a minigame expansion — no CustomFishing fork.

Controls (default `controls: wasd`): W ↑ · A ← · S ↓ · D →, walking frozen during the game. `controls: mouse` = left click ← · sneak ↓ · F ↑ · right click →; `both` = either.

- `./build.sh` → `build/CustomFishing-DDR-1.0.0.jar` (JDK 25 container; compiles against
  `~/cf-ddr/cf-src/target/CustomFishing-2.3.28.jar` and `~/minecraft/data/libraries`).
- Install: jar → `plugins/CustomFishing/expansions/minigame/`, `config/ddr.yml` →
  `plugins/CustomFishing/contents/minigame/`, append `config/game-conditions-ddr.yml` to
  `game-conditions.yml`, then `/cfishing reload` (or restart).
- Visuals use vanilla font glyphs only (12px cells so the track doesn't jitter); override
  `cells.*` in config to switch to resource-pack glyphs later.

Requires CustomFishing 2.3.28+ on MC 26.3 (2.3.26 lacks the 26.3 NMS adapter).

## CustomFishing-Dino (`game-type: dino`)

Chrome offline-T-Rex style runner, built from `src-dino/` into its own jar (CustomFishing loads one
GameExpansion per jar). A TextDisplay board floats over the water in front of the player (only they
see it): 🌵 jump, 🐦 jump or duck, 🦅 don't jump. W/space/right-click jump, S/sneak duck (fast-fall in
the air). Install like DDR with `config/dino.yml` + `config/game-conditions-dino.yml`
(`game-conditions-dino-test.yml` is an ops-only test boost for mc-dev — don't ship it).
