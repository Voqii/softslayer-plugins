# CustomJoinMessages

Source lives in [Voqii/minecraft](https://github.com/Voqii/minecraft). Build from branch **`26.3-brigadier`**
(1.5.6-voqii, the current version). `main` is the old 1.5.1.

`1.5.2-voqii-laptop.patch` holds the 1.5.2 changes that were only ever uncommitted on the laptop, applied on top of
1.5.1 (`main` at 9d9d65c). It was the version on the live server before 1.5.4:
- custom (per-player) AFK/return messages get a **white** `&f* ` prefix, built-in defaults keep the grey `&7* `
- defaults use the player's EssentialsX display name (coloured nickname) for `{NAME}`, with `&7` re-asserted after it

1.5.4 was rebuilt without this (grey `&7* ` on every broadcast, plain name). **1.5.5-voqii ports it onto
`26.3-brigadier`**, so this patch is now history only. It doesn't need to be applied again.
