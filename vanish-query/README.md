# VanishQuery

Removes vanished players from the server's query-protocol player list (`enable-query=true`), which status bots
such as the SoftSlayer Discord #online-players embed read. EssentialsX already hides vanished players from the
server-list ping but not from query.

Vanished = the `vanished` player metadata (EssentialsX, SuperVanish/PremiumVanish). The player count drops too.

1.1.0: with EssentialsX installed, entries carry extra fields after `|` (never valid in a Java or Bedrock username):
`YosemiteSamson|n:Sam|afk` = nickname "Sam" (colour codes stripped), currently AFK. Plain `Name` = no nickname, not AFK.
The ss-bot status bot reads these for the Discord #online list. Note vanilla caches query replies for ~5 s.
No config, no commands. Build: `./build.sh` (Docker, JDK 25, against Purpur 26.3 libraries in `LIBS` and an EssentialsX jar in `ESS`).
