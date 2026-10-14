#!/bin/sh
# Surgical rebuild adding /lc mute + /lc unmute (voqii fork, 2026-10-09).
# Base = the r2 26.3 jar (DeleteListener + 26.3 fixes); recompiles mute/src (Lootchest, Respawn*Command,
# RespawnMute, Mute/UnmuteCommand) with Lombok 1.18.48 + Lootin, then injects with jar uf.
# mute/src uses the RELOCATED import fr.black_eyes.lootchest.simpleJavaPlugin (as inside the jar).
set -e
cd "$(dirname "$0")"
LIBS=${LIBS:-$HOME/mc-test-26/data-263/libraries}
BASE=${BASE:-$HOME/lc-263/tools/base-r2.jar}
OUT=dist/LootChest-2.5.9-voqii-26.3-mute.jar
rm -rf mute/out && mkdir -p mute/out dist && cp "$BASE" "$OUT"
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -v "$LIBS":/libs:ro eclipse-temurin:25-jdk sh -c '
  cd /w/mute && CP=/w/tools/base-r2.jar:/w/tools/lootin-11.3.jar:$(find /libs -name "*.jar" | tr "\n" :)
  javac --release 21 -nowarn -proc:full -cp "$CP:/w/tools/lombok.jar" -processorpath /w/tools/lombok.jar -d out $(find src -name "*.java") 2>&1 | grep -v -E "^Note:|^WARNING" || true
  test -f out/fr/black_eyes/lootchest/RespawnMute.class
  cd out && jar uf /w/'"$OUT"' $(find . -name "*.class")'
find mute/out -name '*.class' | sed 's|mute/out/||'
ls -l "$OUT"
