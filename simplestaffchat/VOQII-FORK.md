# SimpleStaffChat 4.4.0-voqii (SoftSlayer fork)

Fork of RefracDevelopment/SimpleStaffChat 4.3.5 (upstream archived, last release 2024) for Paper/Purpur 26.3.
Only the Spigot module is built.

**Admin chat = adult chat.** On SoftSlayer the "admin chat" channel is the 18+ adult chat (Jask's setup:
"Anywhere it says admin chat, assume adult chat"). Code and config keys still say `adminchat`; the bundled
default configs are SoftSlayer's (`/adult`, `/adultchat`, `[18+]` format).

## Build

    docker run --rm -u 1000:1000 -v ~/.m2-ssc:/m2 -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-21 \
      mvn -q -B -Dmaven.repo.local=/m2/repository -pl SimpleStaffChat-Spigot -am clean package

Jar: `SimpleStaffChat-Spigot/target/SimpleStaffChat-Spigot-4.4.0-voqii.jar`

## Changes vs 4.3.4/4.3.5

- 26.3: `api-version` added (was loaded as a legacy plugin); dropped the XSeries version gate, FoliaLib,
  libby, bStats and the blocking update check on enable.
- Console output goes through the plugin logger (Component messages to the console sender never showed up).
- Security: the typed message is inserted as a component, so players can no longer inject MiniMessage
  (`<click:run_command:...>`, hovers) or expand PlaceholderAPI placeholders through staff/adult chat;
  `&` colour codes still work. `|` in messages is no longer turned into `⎟`.
- Discord: webhooks post off-thread, `@everyone`/`@here` are defused, unset webhooks are skipped,
  console staff chat went to the dev webhook.
- `/adult` from console threw an NPE (typo `fconsole-adminchat-format`).
- Dev chat checked the dev-toggle permission instead of the devchat permission.
- `/schide <channel>` un-hid your other channels; `/schide all` had an operator-precedence bug.
- `/chat` and `/schide` accept `adult` (and `admin`), are case-insensitive, show usage on a bad argument,
  and tab-complete.
- Toggle commands are only registered for enabled channels; commands carry their permission, so players
  without it don't see them.
- One chat listener instead of three (a message could go to two channels); toggle state is thread-safe;
  symbol prefixes work without chat-toggle and aren't treated as regex.
- The sender always sees their own message even without the `.see` permission.
- Reload honours `commands.reload.aliases` (plus the old `/staffchatreload`, `/screload`), and the prefix
  is re-read on reload.
