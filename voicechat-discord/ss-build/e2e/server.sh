#!/bin/sh
# Throwaway OFFLINE-mode test server for the end-to-end tests (never mc-dev: it's online-mode and live).
# Purpur 26.3 like mc-dev, with Simple Voice Chat, ViaVersion/ViaBackwards (the test client speaks 1.21.11)
# and the freshly built fork.
#   server.sh start <dir with voicechat, ViaVersion and ViaBackwards jars> <voicechat-discord config.yml> [extra plugin jar]
#   server.sh stop
set -eu
NAME=vcd-e2e-mc
NET=vcd-e2e
DATA="${E2E_DATA:-/tmp/vcd-e2e-data}"
case "$1" in
start)
  JARS="$2"; CONFIG="$3"
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
  rm -rf "$DATA/plugins/voicechat-discord-paper-"*.jar "$DATA/plugins/vcd-loopback-test.jar"
  mkdir -p "$DATA/plugins/voicechat-discord" "$DATA/plugins/voicechat"
  cp "$JARS"/voicechat-bukkit-*.jar "$JARS"/ViaVersion-*.jar "$JARS"/ViaBackwards-*.jar "$DATA/plugins/"
  cp "$ROOT"/paper/build/libs/voicechat-discord-paper-*-call.*[0-9].jar "$DATA/plugins/"
  cp "$CONFIG" "$DATA/plugins/voicechat-discord/config.yml"
  if [ $# -ge 4 ]; then cp "$4" "$DATA/plugins/"; fi
  # SVC on its own UDP port so it can't clash with mc-dev's.
  printf 'port=24499\nvoice_host=\n' > "$DATA/plugins/voicechat/voicechat-server.properties"
  # Several test clients join from one IP within seconds; Bukkit's 4 s connection throttle would reset them.
  if [ -f "$DATA/bukkit.yml" ]; then sed -i 's/connection-throttle: .*/connection-throttle: -1/' "$DATA/bukkit.yml"
  else printf 'settings:\n  connection-throttle: -1\n' > "$DATA/bukkit.yml"; fi
  docker run -d --name "$NAME" --network "$NET" -u 1000:1000 -v "$DATA":/data \
    -e EULA=TRUE -e TYPE=PURPUR -e VERSION=26.3 -e PURPUR_BUILD=2642 -e ONLINE_MODE=FALSE \
    -e MEMORY=2G -e ENABLE_RCON=true -e RCON_PASSWORD=e2e \
    -e LEVEL_TYPE=minecraft:flat -e SPAWN_PROTECTION=0 -e ALLOW_FLIGHT=TRUE \
    itzg/minecraft-server:java25 >/dev/null
  printf 'waiting for server'
  for i in $(seq 1 120); do
    if docker logs "$NAME" 2>&1 | grep -q 'Done ('; then
      echo ' up'
      # Offline-mode names can't be resolved online, so op them over RCON instead of OPS=.
      for p in SvcAlice SvcBob SvcCarol NoModTester; do docker exec "$NAME" rcon-cli op "$p" >/dev/null; done
      exit 0
    fi
    printf .; sleep 3
  done
  echo ' TIMED OUT'; docker logs --tail 40 "$NAME"; exit 1 ;;
stop)
  docker rm -f "$NAME" >/dev/null 2>&1 || true ;;
esac
