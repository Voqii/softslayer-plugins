# SlayerBot

Answers common questions in chat. A rewrite of ancHelperBot 1.5.1 (by Ancienty) for Paper 26.3.

The default config.yml is converted from the SoftSlayer prod ancHelperBot config of 2026-10-15 (`tools/prod-config.yml`, the old plugin's file; re-run `tools/convert.py` on a newer copy to pick up answer changes).

## What changed from ancHelperBot

- One entry per answer (`topics:`) with a list of example questions, instead of the answer copied on every line.
- Matching that copes with how people actually type: `what's`/`whats`/`what is`, `u`/`ur`, one-letter typos and
  swapped letters in longer words (`discrod`), plurals and `-ing`/`-er` (`dupers` = `duping`), filler words
  (`hey guys`, `does anyone know`) ignored, and synonyms via `matching.aliases`.
- Picks the single best topic, so two topics sharing a question can't fight. A load warning names any question
  that's in two topics.
- Statements need a near-exact match. Only questions (a `?`, or starting with how/what/where/is/can...) use the
  normal threshold, so "i have an xp farm" doesn't get told where the public xp farm is.
- A message that is exactly one of a topic's questions is answered however short it is (`discord?`, `what do i do`).
- `exact: true` topics only answer an exact message, for call-and-response jokes (`IBMOC` → `MAS`).
- `enabled: false` turns a topic off. Its questions still match it, so they get silence instead of a wrong answer.
- Skips cancelled chat (muted players, other filters).
- Cooldowns: the same answer at most every 30s, one answer per player every 5s.
- `[/vote]` in an answer becomes a clickable `/vote` that types the command into chat (never runs it).
- `&` codes and MiniMessage both work. `{years-since:2013}` and PlaceholderAPI placeholders work in answers.
- `/slayerbot test <message>` shows how a message scores against the top 3 topics.
- Questions that almost matched are logged to `plugins/SlayerBot/near-misses.tsv`, to show what to add.

## Commands and permissions

| | |
|---|---|
| `/slayerbot reload` | reload config.yml (alias `/helperbot`) |
| `/slayerbot test <message>` | show the score breakdown for a message |
| `/slayerbot topics` | list topics |
| `slayerbot.admin` | the commands above (default: op) |
| `slayerbot.ignore` | this player's chat never triggers an answer (default: false) |

## Build

No host JDK; build in a container:

    docker run --rm -u 1000:1000 -v "$PWD":/src -v <writable dir>:/m2 -w /src -e HOME=/tmp \
      maven:3.9-eclipse-temurin-25 mvn -q -B -Dmaven.repo.local=/m2 package

Jar: `target/SlayerBot-2.1.0.jar`.

## Tuning / tests

- `tools/check.sh <m2 dir> [cases file]` builds, then checks every stored question still matches its own topic
  and runs the cases (`tools/cases.txt`, `tools/holdout.txt`; format `expected-topic | message`, `-` = must not answer).
- `SWEEP=1 tools/check.sh <m2 dir> tools/cases.txt tools/holdout.txt` prints misses/false answers for a grid of
  `stop-word-weight` and `threshold`. 0.3 / 75 was the best trade-off.
- `kids.txt` is how 8-14 year olds type (`how 2 get mony`, `y cant i move`, `can i be admin plz`) plus kids chatting
  that must not trigger an answer. It scored 82/88 on its first run; the misses were fixed with aliases and questions, so it's no longer untuned.
- `holdout.txt` was written after tuning and never used to pick questions. 2.0.0 scores 45/48 answered and 2/26
  false answers on it. The two false answers ("is anyone in the end", "is that a xp farm") match a real question's
  only meaningful word. (With the 2026-10-15 prod config, xp-farm is disabled, so only the first is a real wrong answer.)
- `tools/convert.py` turns an ancHelperBot `questions:` list into `topics:` (grouped by answer, duplicates dropped,
  the "how do i sell stuff" conflict merged into `sell`, commented-out topics → `enabled: false`, plus the extra
  phrasings in `EXTRA` and the `exact` jokes in `JOKES`).
  `config.yml` = `tools/config-head.yml` + its output.
