# TimingRecords

Companion plugin for [TimingSystem](https://github.com/FrostHexABG/TimingSystem) (built against 3.5). When someone sets a new #1 time-trial time on a track, everyone online gets a chat announcement (and a sound).

- Listens to TimingSystem's public `TimeTrialFinishEvent`; no NMS, no database access.
- Three cases, each with its own message: beating someone else's record, the holder beating their own record, and the first-ever finish on a track (off by default).
- MiniMessage formatting; placeholders `<player>` `<track>` `<time>` `<previous_holder>` `<previous_time>` `<delta>`, inserted unparsed so names can't inject tags.
- `ignored-tracks` list, configurable sound.

Time trials only; event heats aren't covered.

## Commands

| Command | Permission | |
|---|---|---|
| `/timingrecords reload` | `timingrecords.admin` (op) | reload config.yml |
| `/timingrecords test` | `timingrecords.admin` | preview the record messages (only to you) |

Alias `/trecords`.

## Build

    docker run --rm -v $PWD:/src -v m2cache:/root/.m2 -w /src maven:3.9-eclipse-temurin-25 mvn -q -B package

Put TimingSystem 3.5's release jar (from its GitHub releases page) at `lib/TimingSystem-3.5.jar` first; it isn't committed here. The one we built against has sha256 b4362608ca596162882c036d1d6c04a2d82eee1df1b56053ca66b50a25d41a37.
