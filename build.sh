#!/bin/sh
# Build the CustomFishing minigame expansions. No host JDK: compiles in a JDK 25 container
# against CustomFishing (built from ~/cf-ddr/cf-src) and the mc-dev server's libraries.
# One jar per game — CustomFishing loads only the first GameExpansion class found in a jar.
#   src/       -> CustomFishing-DDR-$DDR_VERSION.jar   (game-type: ddr)
#   src-dino/  -> CustomFishing-Dino-$DINO_VERSION.jar (game-type: dino)
set -e
DDR_VERSION=1.0.2
DINO_VERSION=1.0.0
cd "$(dirname "$0")"
CF_JAR=${CF_JAR:-$HOME/cf-ddr/cf-src/target/CustomFishing-2.3.28.jar}
LIBS=${LIBS:-$HOME/minecraft/data/libraries}
rm -rf build && mkdir -p build/ddr build/dino
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/src -v "$CF_JAR":/deps/cf.jar:ro -v "$LIBS":/deps/libs:ro -w /src \
  -e DDR_VERSION="$DDR_VERSION" -e DINO_VERSION="$DINO_VERSION" \
  eclipse-temurin:25-jdk sh -c '
    CP=/deps/cf.jar:$(find /deps/libs -name "*.jar" | tr "\n" ":")
    javac --release 21 -Xlint:-options -encoding UTF-8 -cp "$CP" -d build/ddr $(find src -name "*.java") &&
    javac --release 21 -Xlint:-options -encoding UTF-8 -cp "$CP" -d build/dino $(find src-dino -name "*.java") &&
    jar cf build/CustomFishing-DDR-$DDR_VERSION.jar -C build/ddr . &&
    jar cf build/CustomFishing-Dino-$DINO_VERSION.jar -C build/dino .'
ls -la build/*.jar
