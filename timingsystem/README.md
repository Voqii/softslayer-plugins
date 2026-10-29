# TimingSystem 3.5-voqii.2

Our patches for [FrostHexABG/TimingSystem](https://github.com/FrostHexABG/TimingSystem) (MPL-2.0), the `/tt`
boat-racing plugin. They apply on top of upstream's `3.5` release (commit `132ccf8`). Under the MPL, the
changed files stay MPL-2.0.

## What the patches do

**`0001` Loneliness only while racing (3.5-voqii).** With `loneliness.enabled` (the default), sitting in any boat
anywhere hid every other player and dropped them from the tab list. Now it only applies during a time trial or
a heat; heats keep their own collision modes. Players become visible again whenever a run ends, however it ends.

**`0002` Track stats on `/t times` (3.5-voqii.2).** Hovering a time in `/t times` shows that player's position,
best time and its date, gap to #1, finishes, attempts and time spent on the track, the same numbers the `/tt`
menu shows for your own tracks. Only the time carries the hover, so the name stays free for chat plugins' own
hovers (InteractiveChat profiles).

## Build

    git clone https://github.com/FrostHexABG/TimingSystem.git && cd TimingSystem
    git checkout 132ccf8
    git am /path/to/softslayer-plugins/timingsystem/patches/*.patch
    docker run --rm -u $(id -u):$(id -g) -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-21 \
      mvn -q -B -Dmaven.repo.local=/w/.m2 -DskipTests package

Use JDK 21: Lombok fails on 25. If JitPack can't build the `java-mojang-api` dependency, install a stand-in jar
into the local Maven repo made from the `org/shanerx/mojang` (and its unirest, Apache HTTP/commons, org.json,
json-simple) classes bundled in the official 3.5 jar. The resulting jar has the same class list as the official
one plus `TimeTrialController$1`.

## Status (2026-10-29)

Both tested with scripted bots on a throwaway 26.3 server: outside a time trial other players stay visible in a
boat, during one they're hidden, and after it they're back; the `/t times` hovers show the right numbers.
Not on the dev server or production yet.
