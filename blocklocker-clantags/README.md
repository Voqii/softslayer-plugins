# BlockLockerClanTags

Lets a **SimpleClans** clan be referenced on a **BlockLocker** protection sign by its
short **clan tag**, not just its full clan name.

## The problem

BlockLocker's built-in SimpleClans support matches a sign's `[Group]` line against the
clan's full name only:

```java
// BlockLocker 1.14.1, impl/group/SimpleClansGroupSystem.java
return clanPlayer.getClan().getName().equalsIgnoreCase(groupName);
```

Minecraft sign lines are limited by **pixel width (~90px, roughly 15 characters)**, not
character count, so any clan whose name is longer simply cannot be written on the sign.
And because BlockLocker treats each sign line as an independent entry, a long
`[ClanName]` cannot be split across two lines either. Clan tags, abbreviations, and
partial names all fail, since the comparison is an exact (case-insensitive) equality
against the full name.

## The fix

This plugin registers an **additional** group system through BlockLocker's public API
that also matches `clan.getTag()`. BlockLocker OR-combines all registered group systems,
so **both the tag and the full name work** — nothing that already worked stops working.

BlockLocker itself is **not modified**, so it can be updated normally without re-patching.

## Install

1. Ensure **BlockLocker** and **SimpleClans** are installed.
2. Drop `BlockLockerClanTags-1.0.0.jar` into `plugins/`.
3. Restart the server.

On success the console logs:

```
[BlockLockerClanTags] Registered SimpleClans clan-tag matching with BlockLocker.
```

Then a chest sign like the following grants access to the whole clan:

```
[Private]
[wlf]
```

## Behaviour

| Sign line | Matches? |
|---|---|
| Clan tag (`wlf`) | yes (new) |
| Full clan name (`wolf`) | yes (unchanged) |
| Any letter case | yes — matching is case-insensitive |
| Partial name / abbreviation | no — must equal the tag or the name |

Uses `getTag()` (color codes stripped), so the plain text typed on the sign matches even
when the clan's tag is colored. Clan leaders are recognised as group leaders by tag as
well, which is what BlockLocker uses for sign-editing rights.

## Build

The host has no JDK or Maven, so `build.sh` compiles inside a Temurin container against
the jars the running server already has:

```bash
./build.sh      # -> dist/BlockLockerClanTags-1.0.0.jar
```

Two non-obvious points, both handled by the script:

- The top-level `purpur-*.jar` is a **Paperclip launcher** and does *not* contain the
  Bukkit API classes. The real API plus its transitive dependencies (Adventure, JetBrains
  annotations) live in the server's `/data/libraries` cache.
- `purpur-api` is compiled to **Java 25 bytecode** (class file v69), so an older `javac`
  cannot read it. The build uses JDK 25 but emits Java 21 bytecode for portability.

A `pom.xml` is included for a conventional Maven build if the dependencies are available
from upstream repositories; `build.sh` is the path known to work on this host.

## Testing

`testharness/` contains a temporary plugin that exercises the matcher against live
SimpleClans data and prints PASS/FAIL to the console. It covers tag matching, name
matching (regression), case-insensitivity, rejection of partial/unrelated names,
clanless players, null input, leader semantics, and confirming registration inside
BlockLocker. Last run: **13 passed, 0 failed**.

To run it: build it against the main jar, drop both in `plugins/`, restart, then
**remove the harness jar** — it is not for production.

## Known upstream issue (not introduced by this plugin)

BlockLocker 1.14.1's own `SimpleClansGroupSystem` has two unguarded null dereferences:

- `isInGroup` calls `clanPlayer.getClan().getName()` without null-checking `getClan()`,
  which can throw for a player whose clan record exists but has no clan (e.g. after
  resigning).
- `isGroupLeader` calls `clanPlayer.isLeader()` before its null check, which can throw
  for a player who has never been in a clan.

This plugin null-checks everything and is unaffected, but BlockLocker's own system is
still invoked alongside it, so the upstream error can surface independently. It is worth
reporting upstream; a local patched BlockLocker build would be the workaround if hit.
