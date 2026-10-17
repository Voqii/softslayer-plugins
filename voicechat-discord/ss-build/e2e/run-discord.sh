#!/bin/sh
# The real-Discord end-to-end test (discord.test.js). Needs a Discord server you control with a voice channel and
# three bot applications invited to it (Connect + Speak): the bridge bot plus two test bots.
#   VC_ID=... BRIDGE_TOKEN=... SPEAKER1_TOKEN=... SPEAKER2_TOKEN=... run-discord.sh <writable data dir>
# or put those four lines (KEY=value) in ss-build/e2e/.env (gitignored, chmod 600) and leave them out.
# Tokens are only written into the throwaway server's data dir and removed again afterwards.
set -eu
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
: "$VC_ID" "$BRIDGE_TOKEN" "$SPEAKER1_TOKEN" "$SPEAKER2_TOKEN"
export E2E_DATA="$1"
CONFIG="$E2E_DATA/discord-config.yml"
mkdir -p "$E2E_DATA"
umask 077
printf 'bots:\n- token: %s\n  vc_id: %s\n  mode: call\n  name: friends\ndebug_level: %s\n' "$BRIDGE_TOKEN" "$VC_ID" "${DEBUG_LEVEL:-0}" > "$CONFIG"
./server.sh start jars "$CONFIG"
status=0
docker run --rm --network vcd-e2e -u "$(id -u):$(id -g)" -e HOME=/tmp -e DEBUG -e DEBUG_VOICE -e VC_ID -e BRIDGE_TOKEN -e SPEAKER1_TOKEN -e SPEAKER2_TOKEN \
  -v "$PWD":/w -w /w node:22 timeout 300 node discord.test.js || status=1
./server.sh stop
rm -f "$CONFIG" "$E2E_DATA/plugins/voicechat-discord/config.yml"
exit $status
