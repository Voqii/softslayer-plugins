# SoftSlayer fork of Simple Voice Chat Discord Bridge

Fork of [amsam0/voicechat-discord](https://gitlab.com/amsam0/voicechat-discord) 3.2.1 (MIT), branch `call-mode`.
Version `3.2.1-call.2`. Only the Paper plugin is built (Fabric/NeoForge are left out of `settings.gradle.kts`).

## Why

A friend who only has Simple Voice Chat (SVC) in game should be able to talk with a group that's in a Discord
voice channel. Upstream can't do that well:

- Upstream's `/dvc start` makes Discord the voice of one player who **doesn't** have SVC, so someone has to stay
  in game as that stand-in.
- Upstream forwards only one Discord speaker per 20 ms (`discord_receive.rs` took `tick.speaking.values().next()`),
  so two people talking at once glitch. Their README says to keep bot channels to 2 people because of it.

## What changed

1. **All Discord speakers are mixed** (Rust). Songbird now decodes every user to 48 kHz mono
   (`DecodeMode::Decode`, `discord_bot/mod.rs`), `discord_receive.rs` mixes everyone talking that tick
   (`audio_util::mix_voices`, clipping instead of overflow) and encodes one Opus packet. DAVE decryption still
   happens in songbird. This also fixes `/dvc start`.
2. **Call mode** (`CallSession.java`). A bot with `mode: call` links its voice channel with any number of players
   who have SVC. `/dvc call [name]` to join, `/dvc hangup` to leave, `/dvc calls` to see who's in.
   - Players hear the Discord channel, mixed, as non-positional audio on a static channel (`createStaticAudioChannel`,
     ignores distance and follows them across dimensions), with its own "Discord" volume slider in SVC's settings.
   - Their microphone goes to Discord (`MicrophonePacketEvent`, registered at priority -100: once a mute plugin
     cancels a packet SVC stops dispatching it, so muted players never reach Discord).
   - Players in an isolated SVC group still hear the call. The Bukkit SVC (2.6.21) doesn't bypass isolation for
     this channel type by itself, so the plugin calls `setBypassGroupIsolation(true)` (reflection: not in the
     2.4.11 API we compile against; on older SVC it logs one warning instead).
   - The bot is only in the voice channel while someone is in the call, so its presence means someone in game
     can hear the channel.
   - Permission `voicechat-discord.call` (default op) for `/dvc call` and `/dvc calls`.
4. **Join requests: the Discord channel decides who listens** (`CallRequests.java`, Rust `discord_bot/text.rs` +
   the `Handler` in `log_in.rs`). A Discord voice channel is invite-only; the permission node alone would let
   whoever hands out ranks in game decide who hears it. So with `approval` on (the default):
   - `/dvc call` posts "Steve wants to join this voice channel from Minecraft" with **Let in** / **Deny** buttons in
     the voice channel's own text chat. Only someone in that voice channel can answer: the bot tracks the
     channel's members from gateway voice-state events (bots don't count), and anyone else who clicks gets a
     private "Only people in this voice channel can answer." No answer in 60 s is a no.
   - If nobody (no human) is in the channel, nothing is posted: the player is told there's nobody to ask.
     A player without the Simple Voice Chat mod is refused before anything is posted.
   - Denied / no answer: 30 s before that player can ask again. Removed: 5 minutes.
   - `/dvc hangup` cancels an open request; leaving the server cancels it too.
   - Discord names never reach the game (the player is told "the Discord call said no", not who).
   With approval on or off, the channel always sees who's listening: each Minecraft player in the call has a
   message ("Steve is in the call from Minecraft, let in by Alice") with a **Remove** button, edited when they
   leave or are removed, and the bot's nickname lists them ("MC: Steve, Alex", shortened to fit 32 characters,
   back to the bot's own name when nobody's listening). Clicks on stale buttons (e.g. from before a restart) turn
   the message into "This request isn't open anymore." `approval: false` on a call bot skips the asking only.
3. Small fixes: `getBotForPlayer` compared UUIDs with `==`; small `vc_id`s that YAML reads as Integer failed the
   `(Long)` cast (`BotConfig`).

Config:

```yaml
bots:
- token: CALL_BOT_TOKEN
  vc_id: 1234567890123456789
  mode: call
  name: friends        # only needed with more than one call bot: /dvc call friends
- token: PLAYER_BOT_TOKEN   # no mode = upstream behaviour, /dvc start
  vc_id: 9876543210987654321
```

The Discord bot needs Connect and Speak in the channel (the upstream invite link's permissions cover it), and for
join requests also View Channel, Send Messages and Read Message History in that voice channel, plus Change
Nickname. Without Change Nickname everything else still works (one warning in the console).

```yaml
- token: CALL_BOT_TOKEN
  vc_id: 1234567890123456789
  mode: call
  approval: false      # optional: let players in without asking (they're still shown in the channel)
```

## Building

No host JDK or Rust needed, everything runs in Docker (`ss-build/`):

```sh
# natives: Rust + zig, so the .so works on glibc >= 2.26 like upstream's releases
docker build -t vcd-natives -f ss-build/natives.Dockerfile ss-build
cd core && docker run --rm -u $(id -u):$(id -g) -e CARGO_HOME=/cargo -e HOME=/tmp -e OPUS_STATIC=1 \
  -v <cargo cache>:/cargo -v "$PWD":/src -w /src vcd-natives sh -c \
  'for t in x86_64 aarch64; do cargo zigbuild --target $t-unknown-linux-gnu.2.26 --release; done'
mkdir -p src/main/resources/natives/linux-x64 src/main/resources/natives/linux-aarch64
cp target/x86_64-unknown-linux-gnu/release/libvoicechat_discord.so src/main/resources/natives/linux-x64/
cp target/aarch64-unknown-linux-gnu/release/libvoicechat_discord.so src/main/resources/natives/linux-aarch64/
cd ..
# plugin (JDK 25; paperweight pulls the 26.3 dev bundle)
docker run --rm -u $(id -u):$(id -g) -e GRADLE_USER_HOME=/gradle -e HOME=/tmp -v <gradle cache>:/gradle \
  -v "$PWD":/src -w /src eclipse-temurin:25-jdk ./gradlew --no-daemon :paper:build
```

Jar: `paper/build/libs/voicechat-discord-paper-3.2.1-call.1.jar` (Linux x64 + arm64 natives only; add macOS or
Windows natives the same way if the server ever runs on one).

## Tests

Run all of these after any change:

| What | How | Covers |
|---|---|---|
| Rust | `cargo test --release` in `core/` (same container) | mixing: 2 and 3 people at once come through balanced after a real Opus round trip, silence sends nothing, clipping, odd frame sizes. Mutation-checked: putting upstream's "first speaker only" back fails 4 of 6. |
| Java | `./gradlew :core:test` | `CallSessionTest` (call lifecycle against a fake Discord + mocked SVC API: joining, connect failures, hanging up mid-connect, audio routing, mute/cancel, isolation bypass, resets), `CallRequestsTest` (asking, let in / deny / no answer, only the first answer counts, cooldowns, remove, stale buttons, cancelling before and after posting, nobody in the channel, no mod, nickname, approval off, Markdown/mention escaping) and `BotConfigTest`. Mutation-checked: skipping approval fails 14 tests. |
| End to end, no Discord | `ss-build/e2e/run-all.sh <data dir> <gradle cache>` | Throwaway offline Purpur 26.3 + real SVC 2.6.21 + the jar, driven by `svc-client.js`, a headless SVC client (real handshake, AES-GCM UDP, Opus). `no-discord.test.js`: permission, no-mod refusal, names, bad token. `loopback.test.js`: a test-only plugin stands in for Discord (voice and the channel's chat; `/loopback-answer let in\|deny\|remove` clicks as someone in the channel), checking the whole request flow through real commands, mic → call → every participant, outsiders hear nothing, isolated groups, flush, hangup, Remove, quitting, rejoining. |
| End to end, real Discord | `VC_ID=… BRIDGE_TOKEN=… SPEAKER1_TOKEN=… SPEAKER2_TOKEN=… ss-build/e2e/run-discord.sh <data dir>` | With approval on: bots in the channel don't count, so nothing is posted. Then (config switched to `approval: false` + `/dvc reloadconfig`): two test bots talk at once, and the game must hear both; the game talks and Discord must hear it; the join notice and nickname appear and are undone at hangup; the bot leaves. Needs a test Discord server with three bot apps. Bots can't click buttons, so Let in / Deny / Remove by a real person is only covered by the loopback test. |

`ss-build/e2e/jars/` must hold the `voicechat-bukkit`, `ViaVersion` and `ViaBackwards` jars of the server you're
targeting (the test client speaks 1.21.11 through ViaVersion). `npm install` in `ss-build/e2e` first.

## Updating from upstream

`git remote add upstream https://gitlab.com/amsam0/voicechat-discord.git`, merge, then re-run everything above.
Watch `discord_receive.rs` (our mixing) and `Core.loadConfig` (bot modes).
