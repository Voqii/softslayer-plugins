#!/bin/sh
# Build, then run MatchCheck against src/main/resources/config.yml and ${2:-tools/cases.txt}.
# Usage: tools/check.sh <writable maven repo dir> [cases file]
cd "$(dirname "$0")/.." && docker run --rm -u 1000:1000 -v "$PWD":/src -v "$1":/m2 -w /src -e HOME=/tmp -e CASES="${2:-tools/cases.txt} $3" -e SWEEP maven:3.9-eclipse-temurin-25 sh -c '
  mvn -q -B -Dmaven.repo.local=/m2 package dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt >/tmp/mvn.log 2>&1 || { cat /tmp/mvn.log; exit 1; }
  CP=$(cat /tmp/cp.txt):target/classes; mkdir -p /tmp/mc && javac -d /tmp/mc -cp $CP tools/MatchCheck.java && java -cp /tmp/mc:$CP MatchCheck src/main/resources/config.yml $CASES' 2>&1 | grep -v -E "^(mkdir|Can not write)"
