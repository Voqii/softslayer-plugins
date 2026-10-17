#!/bin/sh
# Runs every end-to-end scenario that doesn't need Discord, each on a freshly started throwaway server.
# Needs: the fork built (gradlew :paper:build), npm install done here, and jars/ holding voicechat-bukkit,
# ViaVersion and ViaBackwards jars (copy them from the server you're targeting).
# Usage: run-all.sh <writable data dir> <gradle home with the voicechat-api jar cached>
set -u
cd "$(dirname "$0")"
DATA="$1"; GRADLE_HOME="$2"
export E2E_DATA="$DATA"
node() { docker run --rm --network vcd-e2e -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/w -w /w node:22 timeout 300 node "$@"; }
status=0

echo "== no-discord (one call bot with a fake token)"
./server.sh start jars config-no-discord.yml || exit 1
node no-discord.test.js || status=1

echo "== loopback (test plugin stands in for Discord)"
./loopback-plugin/build.sh "$DATA" "$GRADLE_HOME" >/dev/null || exit 1
./server.sh start jars config-no-discord.yml loopback-plugin/out/vcd-loopback-test.jar || exit 1
node loopback.test.js || status=1

# The loopback server should log no errors from the fork at all.
errors=$(docker logs vcd-e2e-mc 2>&1 | grep -E 'Exception|ERROR' | grep -i -E 'voicechat-discord|amsam0|softslayer' || true)
if [ -n "$errors" ]; then
  echo "FAIL unexpected errors in the server log:"; echo "$errors" | head; status=1
fi
./server.sh stop
exit $status
