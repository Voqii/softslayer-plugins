#!/bin/sh
# Runs the (non-shipped) layout fuzz test for the dino board in a JDK 25 container.
set -e
cd "$(dirname "$0")"
CF_JAR=${CF_JAR:-$HOME/cf-ddr/cf-src/target/CustomFishing-2.3.28.jar}
LIBS=${LIBS:-$HOME/minecraft/data/libraries}
rm -rf build/test && mkdir -p build/test
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/src -v "$CF_JAR":/deps/cf.jar:ro -v "$LIBS":/deps/libs:ro -w /src \
  eclipse-temurin:25-jdk sh -c '
    CP=/deps/cf.jar:$(find /deps/libs -name "*.jar" | tr "\n" ":")
    javac --release 21 -Xlint:-options -encoding UTF-8 -cp "$CP" -d build/test $(find src-dino test-dino -name "*.java") &&
    java -cp "build/test:$CP" dev.voqii.cfdino.LayoutRowTest'
