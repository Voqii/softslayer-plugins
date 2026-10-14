#!/usr/bin/env bash
# Offline build: compiles inside a Temurin 25 container against the running mc-dev
# server's library cache (Paper/Purpur API + Adventure + Gson). Produces dist/ChatTranslate-1.0.0.jar.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${CONTAINER:-mc-dev}"
mkdir -p "$HERE/build"
[ -d "$HERE/build/serverlibs" ] || docker cp "$CONTAINER:/data/libraries" "$HERE/build/serverlibs"
docker run --rm -u "$(id -u):$(id -g)" -v "$HERE":/w -w /w eclipse-temurin:25-jdk sh -c '
  set -e
  CP=$(find build/serverlibs -name "*.jar" | tr "\n" ":")
  rm -rf build/out; mkdir -p build/out dist
  javac -Xlint:-options --release 21 -cp "$CP" -d build/out $(find src/main/java -name "*.java")
  cp src/main/resources/*.yml build/out/
  (cd build/out && jar cf ../../dist/ChatTranslate-1.0.0.jar .)
'
echo "Built: $HERE/dist/ChatTranslate-1.0.0.jar"
