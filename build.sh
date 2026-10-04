#!/bin/sh
# Build the CustomFishing DDR expansion. No host JDK: compiles in a JDK 25 container
# against CustomFishing (built from ~/cf-ddr/cf-src) and the mc-dev server's libraries.
set -e
VERSION=1.0.2
cd "$(dirname "$0")"
CF_JAR=${CF_JAR:-$HOME/cf-ddr/cf-src/target/CustomFishing-2.3.28.jar}
LIBS=${LIBS:-$HOME/minecraft/data/libraries}
rm -rf build && mkdir -p build/classes
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/src -v "$CF_JAR":/deps/cf.jar:ro -v "$LIBS":/deps/libs:ro -w /src \
  eclipse-temurin:25-jdk sh -c '
    CP=/deps/cf.jar:$(find /deps/libs -name "*.jar" | tr "\n" ":")
    javac --release 21 -Xlint:-options -encoding UTF-8 -cp "$CP" -d build/classes $(find src -name "*.java") &&
    jar cf build/CustomFishing-DDR-'"$VERSION"'.jar -C build/classes .'
ls -la build/*.jar
