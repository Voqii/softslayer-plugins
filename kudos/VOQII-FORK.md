# Kudos 7.1.1-voqii (SoftSlayer fork)

Fork of [Urbance/Kudos](https://codeberg.org/Urbance/Kudos) 7.1.0 (GPL-3). Upstream moved from GitHub to Codeberg in
February 2026. The code here is upstream tag `7.1.0` plus two commits, squashed in. `README.md` is still upstream's.

## Why it exists

With InteractiveChat and Kudos both enabled, production chat lagged badly. Kudos has no chat listener of its own.
The lag came in through PlaceholderAPI:

- InteractiveChat puts a hover on every player name in chat. It fills in that hover's placeholders once per name,
  per player receiving the line, per chat line, and doesn't cache them.
- Every `%kudos_*%` request ran about 5 database queries. It always ran the top-3 leaderboard query and its name
  lookups, even when only `player_kudos` was asked for.
- `SQL.getConnection()` had an inverted null check (`connection != null || connection.isClosed()`), so it opened
  a brand new SQLite connection for every query. It also handed one static `Connection` to whichever thread asked
  last. The kudos table had no indexes.

Measured old cost per placeholder: about 2 ms with 2,000 kudos rows and about 11 ms with 20,000. With 40 people
online that's roughly 80-450 ms of blocking database work per chat line.

## Changes

- SQLite goes through a HikariCP pool (4 connections, `busy_timeout` 5 s), the same as the MySQL path.
- The PlaceholderAPI expansion never touches the database. It reads `KudosPlaceholderCache`, which refreshes on an
  async timer every 60 s and right after any award, remove or clear. Online players are loaded when they join.
  A player who isn't cached yet reads 0 for about a second while they load.
- Indexes on `kudos.AwardedToPlayer` and `kudos.ReceivedFromPlayer`, created on startup.
- The player-row upsert on join runs off the main thread.
- The expansion sets `persist()`, so it survives `/papi reload`.
- `/kudmin add` and `/kudo` from RCON or a command block no longer crash on a `Player` cast.
- The pom writes the jar to `target/` instead of the upstream author's local server folder.

Kudos's own award and `/kudos` messages still query the database directly, so they're always current.

## 7.1.1-voqii: vanished players

`/kudo`, `/kudos` and `/kudmin` tab completion skips players the person typing can't see, so vanished staff aren't
suggested to normal players. Staff who can see vanished players (EssentialsX `essentials.vanish.see`) still get
everyone. Tested on a throwaway 26.3 server with bot players; 7.1.0 suggested the vanished player there.

## Build

    docker run --rm -u $(id -u):$(id -g) -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 \
      mvn -q -B -Dmaven.repo.local=/w/.m2 -DskipTests package

Install `target/Kudos-7.1.0-voqii-shaded.jar`. The jar without `-shaded` is missing its bundled libraries.

## Install

Stop the server, delete the old `Kudos-*.jar`, put the new jar in under its new filename, start. The existing
database and config work unchanged.

## Status (2026-10-19)

Running on the dev server. Console tests pass: awards, clears, the top-1/2/3 placeholders, and 30 parallel
placeholder requests with no database errors. Not yet on production and not yet tested with real players in chat.

## Upgrading to a new Minecraft version

Kudos bundles InventoryFramework, which picks its version code from a hard-coded list. Any 26.x version maps to
its `V26_1` module, and Kudos's chest menus don't use the version-specific code, so 26.x should keep working.
Expect to bump InventoryFramework at Minecraft 27.
