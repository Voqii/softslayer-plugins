# Doorknob

Chat slur filter. When someone types the n-word (including spaced/split/leetspeak variants), everyone else sees
"I'm a doorknob!" instead. The sender and console still see the original, so staff can moderate.

- Hooks `AsyncChatEvent` at **MONITOR**. EssentialsX sets its renderer at HIGHEST and that renderer ignores the
  message argument, so Doorknob renders the line, then swaps the text out of the rendered component.
  Other players get it as a system message, so client secure-chat settings don't matter.
- Not filtered: signs, books, anvils, /msg.
- False positives were checked against a 370k English dictionary plus top-50k en/es/fr/sv word lists
  ("ni ganas", "nilgai" etc. pass).

Build: `mvn package` in `maven:3.9-eclipse-temurin-25` (see the top-level README).
