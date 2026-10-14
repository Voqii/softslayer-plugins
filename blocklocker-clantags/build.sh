#!/usr/bin/env bash
# Offline build for BlockLockerClanTags.
#
# The host has no JDK/Maven, so this compiles inside a Temurin container against
# the jars the running server already has. No network needed (except the one-time
# docker image pull). Produces dist/BlockLockerClanTags-1.0.0.jar.
#
# Requires: docker, and the mc-dev container running (source of the server API jars).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$HERE/build"
CONTAINER="${CONTAINER:-mc-dev}"
BLOCKLOCKER_URL="https://hangarcdn.papermc.io/plugins/Rutger/BlockLocker/versions/1.14.1/PAPER/blocklocker-1.14.1.jar"

mkdir -p "$BUILD/libs" "$BUILD/out"

# --- gather compile-time dependencies -------------------------------------
if [ ! -f "$BUILD/libs/SimpleClans.jar" ]; then
  docker cp "$CONTAINER:/data/plugins/SimpleClans.jar" "$BUILD/libs/SimpleClans.jar"
fi
if [ ! -f "$BUILD/libs/BlockLocker.jar" ]; then
  if ! docker cp "$CONTAINER:/data/plugins/BlockLocker-1.14.1.jar" "$BUILD/libs/BlockLocker.jar" 2>/dev/null; then
    curl -fsSL -o "$BUILD/libs/BlockLocker.jar" "$BLOCKLOCKER_URL"
  fi
fi
# The server's library cache carries the Bukkit API plus its transitive deps
# (Adventure, annotations). The top-level purpur jar is only a Paperclip
# launcher and does NOT contain the API classes.
if [ ! -d "$BUILD/serverlibs" ]; then
  docker cp "$CONTAINER:/data/libraries" "$BUILD/serverlibs"
fi

# --- compile & package ----------------------------------------------------
# JDK 25: the purpur-api classes are Java 25 bytecode (class file v69), so an
# older javac cannot read them. We still emit Java 21 bytecode for portability.
docker run --rm -v "$HERE":/w -w /w eclipse-temurin:25-jdk sh -c '
  set -e
  SRVCP=$(find build/serverlibs -name "*.jar" | tr "\n" ":")
  CP="build/libs/BlockLocker.jar:build/libs/SimpleClans.jar:${SRVCP}"
  rm -rf build/out; mkdir -p build/out
  javac --release 21 -cp "$CP" -d build/out $(find src/main/java -name "*.java") \
    2>&1 | grep -vE "warning:|Cannot find annotation" || true
  test -f build/out/net/voqii/blsctags/SimpleClansTagGroupSystem.class
  cp src/main/resources/plugin.yml build/out/plugin.yml
  mkdir -p dist
  (cd build/out && jar cf ../../dist/BlockLockerClanTags-1.0.0.jar .)
'

echo "Built: $HERE/dist/BlockLockerClanTags-1.0.0.jar"
