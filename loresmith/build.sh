#!/bin/bash
# Builds target/LoreSmith-<version>.jar in a JDK 25 Maven container (no host JDK on orthrus).
set -e
cd "$(dirname "$0")"
docker run --rm -u 1000:1000 -e MAVEN_CONFIG=/m2 -v "$PWD":/src -v "${M2:-$HOME/.m2-loresmith}":/m2 -w /src \
  maven:3.9-eclipse-temurin-25 mvn -q -B -Dmaven.repo.local=/m2 package
ls -la target/*.jar
