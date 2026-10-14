#!/bin/sh
# Surgical 26.3 rebuild of LootChest-voqii: recompile Version (shaded DecentHolograms enum)
# and FastReflection against the current voqii jar, then inject with jar uf.
set -e
cd "$(dirname "$0")"
LIBS=${LIBS:-$HOME/mc-test-26/data-263/libraries}
BASE=${BASE:-$HOME/lc-263/base-r1.jar}
rm -rf out && mkdir -p out dist && cp "$BASE" dist/LootChest-2.5.9-voqii-26.3.jar
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -v "$LIBS":/libs:ro -v "$BASE":/base.jar:ro eclipse-temurin:25-jdk sh -c '
  cd /w && CP=/base.jar:$(find /libs -name "*.jar" | tr "\n" :)
  javac --release 21 -nowarn -cp "$CP" -d out $(find src -name "*.java")
  cd out && jar uf ../dist/LootChest-2.5.9-voqii-26.3.jar $(find . -name "*.class")'
find out -name '*.class'
ls -l dist/
