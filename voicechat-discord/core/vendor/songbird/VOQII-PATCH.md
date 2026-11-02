# Vendored songbird (voicechat-discord SoftSlayer fork)

songbird `next` branch at d2561d7, MIT/ISC licensed (LICENSE.md), with one change: `Config::udp_bind_port`
(`src/config.rs`) and the bind that uses it in `src/driver/connection/mod.rs`. Upstream always binds the voice UDP
socket to a random port; some game hosts only let UDP replies in on the server's assigned ports, so Discord's
IP-discovery reply never arrived ("failed to connect to Discord RTP server: connection attempt timed out").
If the configured port can't be bound, it falls back to a random port.

Removed from the vendored copy: `resources/` (sample audio/video for songbird's own tests), `images/`, logos, `.github/`.
