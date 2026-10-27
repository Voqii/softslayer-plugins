# VanishQuery

Removes vanished players from the server's query-protocol player list (`enable-query=true`), which status bots
such as the SoftSlayer Discord #online-players embed read. EssentialsX already hides vanished players from the
server-list ping but not from query.

Vanished = the `vanished` player metadata (EssentialsX, SuperVanish/PremiumVanish). The player count drops too.
No config, no commands. Build: `./build.sh` (Docker, JDK 25, against mc-dev's Purpur 26.3 libraries).
