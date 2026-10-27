#!/bin/sh
# Compiles against a Paper/Purpur 26.3 server's libraries/ folder (LIBS=..., default ~/minecraft/data/libraries) in a JDK 25 container; output: build/VanishQuery-<version>.jar
set -e
cd "$(dirname "$0")"
V=$(sed -n "s/^version: '\(.*\)'/\1/p" resources/plugin.yml)
rm -rf build && mkdir -p build/classes
docker run --rm -u 1000:1000 -v "$PWD":/w -v "${LIBS:-$HOME/minecraft/data/libraries}":/libs:ro -w /w eclipse-temurin:25-jdk sh -c \
  'javac --release 21 -d build/classes -cp "$(find /libs -name "*.jar" | tr "\n" ":")" $(find src -name "*.java") && cp resources/plugin.yml build/classes/ && cd build/classes && jar cf ../VanishQuery-'"$V"'.jar .'
ls -l build/*.jar
