# mc-dialog-probe

Scripted Minecraft client (mineflayer) for testing dialog/chest menus on a throwaway OFFLINE-mode server
(never on mc-dev: it's online-mode). `npm install` first.

    V=1.21.11 node probe.js '[{"wait":2000},{"chat":"/guild"},{"click":"Bank"},{"click":"Deposit","inputs":{"amount":"5k"}},{"slot":49}]'

Steps: `chat` (sends a chat line/command), `click` (button whose label contains the text; `inputs` = dialog
input values), `slot` (clicks a slot in the open chest), `wait` (ms after the step, default 1500).
Prints every dialog (type, title, body, inputs, buttons), chest layouts and chat. `V=1.21.4` tests the
pre-dialog chest fallback. Server needs ViaVersion + ViaBackwards (copy from mc-dev's plugins).

Workarounds baked in: physics off and movement packets dropped (else "invalid move" kicks through Via);
`custom_click_action` payload is written as varint-length-prefixed anonymous NBT (minecraft-data's
option(nbt) definition is wrong and the server fails to decode it).
