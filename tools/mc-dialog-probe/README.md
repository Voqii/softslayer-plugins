# mc-dialog-probe

Scripted Minecraft client (mineflayer) for testing dialog/chest menus on a throwaway OFFLINE-mode server
(never on mc-dev: it's online-mode). `npm install` first.

    V=1.21.11 node probe.js '[{"wait":2000},{"chat":"/guild"},{"click":"Bank"},{"click":"Deposit","inputs":{"amount":"5k"}},{"slot":49}]'

Steps: `chat` (sends a chat line/command), `click` (button whose label contains the text; `inputs` = dialog
input values), `slot` (clicks a slot in the open chest), `wait` (ms after the step, default 1500).
Prints every dialog (type, title, body, inputs, buttons), chest layouts and chat. `V=1.21.4` tests the
pre-dialog chest fallback. Server needs ViaVersion + ViaBackwards (copy from mc-dev's plugins).
`U=<name>` sets the username (default `DlgTester`), so several bots can run at once, e.g. a guild leader in
the background and an applicant in the foreground.

A throwaway server on port 25599 (put the Purpur jar and plugins — SimpleClans, Vault, EssentialsX, ViaVersion,
ViaBackwards — in `./sgtest` first; RCON on 25598 so it can't clash with another server):

    docker run -d --name sg-apptest --network host -e EULA=TRUE -e TYPE=CUSTOM -e CUSTOM_SERVER=/data/purpur-26.3-2642.jar \
      -e ONLINE_MODE=FALSE -e SERVER_PORT=25599 -e RCON_PORT=25598 -e LEVEL_TYPE=minecraft:flat -v "$PWD/sgtest":/data \
      itzg/minecraft-server:java25

Console commands: `docker exec sg-apptest rcon-cli --port 25598 <command>`. Remove it with `docker rm -f sg-apptest`.

Workarounds baked in: physics off and movement packets dropped (else "invalid move" kicks through Via);
`custom_click_action` payload is written as varint-length-prefixed anonymous NBT (minecraft-data's
option(nbt) definition is wrong and the server fails to decode it).
