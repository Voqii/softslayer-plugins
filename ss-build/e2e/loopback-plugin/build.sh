#!/bin/sh
# Builds the test-only loopback plugin against the fork's jar, the SVC API and the test server's libraries.
# Usage: build.sh <test server data dir> <gradle home with voicechat-api cached>
set -eu
cd "$(dirname "$0")"
DATA="$1"; GRADLE_HOME="$2"
SVC_API=$(find "$GRADLE_HOME" -name 'voicechat-api-2.4.11.jar' | head -1)
FORK=$(ls ../../../paper/build/libs/voicechat-discord-paper-*-call.*[0-9].jar)
rm -rf out && mkdir -p out
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -v "$DATA/libraries":/libs:ro -v "$SVC_API":/svc-api.jar:ro \
  -v "$(cd "$(dirname "$FORK")" && pwd)/$(basename "$FORK")":/fork.jar:ro -w /w eclipse-temurin:25-jdk sh -c '
  CP=$(find /libs -name "*.jar" | tr "\n" ":")/svc-api.jar:/fork.jar
  javac --release 21 -d out/classes -cp "$CP" $(find src -name "*.java") && cp src/plugin.yml out/classes/ &&
  cd out/classes && jar cf ../vcd-loopback-test.jar .'
ls -la out/vcd-loopback-test.jar
