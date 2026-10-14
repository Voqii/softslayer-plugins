# SS-Bot (guild chat ↔ Discord)

discord.js bot that links each SlayerGuilds guild to its own Discord channel. Guild chat goes to Discord,
Discord messages go back into that guild only. Talks to the SlayerGuilds bridge (see BRIDGE.md in the SlayerGuilds repo).

Setup: copy `.env.example` → `.env`, fill in `DISCORD_TOKEN` and `BRIDGE_SECRET` (must equal `secret` in
`plugins/SimpleClans/bridge-config.yml`), then `docker compose up -d --build`. Linked channels live in `data/links.json`.

Linking: someone with Manage Server runs `/guildlink tag:X` in a channel and gets a one-time code. A guild leader types `/. CODE` in game.

Transports (`BRIDGE_MODE`):
- `poll`: the bot polls the plugin's localhost bridge. The bot has to run on the same host as the MC server.
- `listen`: the plugin runs `mode: remote` and POSTs to `<remote-url>/sync` every 1.5 s. Use this when the MC host has
  no shell or inbound ports (Pterodactyl panel hosts). Expose LISTEN_HOST:LISTEN_PORT over HTTPS (e.g. a Cloudflare tunnel).
  `GET /health` shows whether the server is online and how many guilds are cached.

Names from Discord are mapped to Minecraft players through DiscordSRV's account links.
