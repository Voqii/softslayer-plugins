# SlayerGuilds — test checklist (dialog menus + 2026-10-16 applications fixes)

Build `2.19.3-SNAPSHOT-a84e494` (branch `dialog-ui`). Install steps: `INSTALL.md`.
Needs Purpur/Paper **26.1.2 or newer** (no Spigot).

Use a **non-op** account for most of this, plus one op for section 6. Ops see every button, which hides
permission problems. Most sections need a second account too (invites, applications, allies).

When something's off, note the section number, what you clicked, what you expected and what happened.

## 1. Rivals and wars are gone

- [ ] `/guild help`: no rival, rivalries or war commands.
- [ ] `/guild rival add X` and `/guild war start X` are unknown commands.
- [ ] `/guild profile` and `/guild lookup`: kill totals read `[Ally / Neutral / Civilian]`, with no Rival
      column. Old rival kills now count under Neutral, and your KDR is unchanged.
- [ ] Killing a player from another guild records a Neutral kill. Bounties still pay out in Death Valley.
- [ ] Land sharing (if a protection plugin is installed) still works.
- [ ] On first boot, the console said it removed the rival/war settings from config.yml (mc-dev: 8)
      and, if any guild had them, that it cleared old rivalries/wars.

## 2. Main menu (`/guild`)

- [ ] Opens as a popup, not a chest. Hovering a button shows a tooltip.
- [ ] Every button in the main menu, Leader tools and Staff tools starts with an item icon (no pink/black
      missing-texture squares).
- [ ] While any guild popup is open the world goes dark around you; it comes back the moment the popup closes
      (Close, Esc, opening a chest, the wiki button). The wiki button posts the link in chat. No Blindness icon shows.
      `settings.menu-backdrop: false` turns it off.
- [ ] **Without a guild:** Create, Apply, Browse, Slayer Score, Player leaderboard, Bounties,
      My settings and Help are shown. When you have a pending invite, an extra Invite button appears.
- [ ] **In a guild:** the header shows your head, rank and guild, plus bank, Slayer Score position, members
      and days until tax. Leader tools appear only for leaders, and Staff tools only with `simpleclans.mod.staffgui`.
- [ ] An unverified guild doesn't show Members or Warps, and the header says it isn't verified.
- [ ] The **Chat** button cycles Guild → Ally → Off. It skips Ally if your rank can't use ally chat.
      Your normal chat messages go where the button says.
- [ ] `Esc` closes every dialog. No dialog gets stuck on a "waiting" screen.

## 3. Forms

- [ ] Create a guild with tag + name. A taken or reserved tag gives the normal chat error.
- [ ] Apply: the dropdown lists only guilds that are open to applications. An empty message shows the "Couldn't do that" page.
- [ ] Invite (from another account's invite): Accept / Decline work. **Esc only goes back** (it must not decline).
- [ ] Bank: Deposit / Withdraw with `5000`, `2.5k` and `1m`. A bad amount (`abc`) shows the error page, and Back
      returns to the bank. After a deposit/withdraw the bank screen shows the new balance. Deposit all / Withdraw all
      ask for confirmation first. Buttons your rank can't use are hidden. An **unverified** guild has no Bank button.
- [ ] Bulletin board: post a message, then clear it (leader).
- [ ] My settings: each toggle saves, and leaving the guild asks for confirmation first.

## 4. Leader tools

- [ ] Invite a player by name.
- [ ] Applications: see section 10.
- [ ] Settings: toggle applications, friendly fire and deposit/withdraw, change the description, set a wiki link and
      clear it (empty box). Only the things you changed are announced in chat.
- [ ] Rename / change tag: changing only the name doesn't touch the tag, and the reverse. With two leaders, a tag change starts a vote.
- [ ] Members → click a member → their options (promote, demote, trust, set rank, kick). Back returns to the
      roster, and Back again returns to the menu.
- [ ] Ranks opens the ranks chest. Its Back returns to Leader tools.
- [ ] Home & regroup, Banner (holding a banner / reset), Verify (unverified guilds only).
- [ ] Disband: with **one leader** the guild is disbanded after the popup, with no second "type confirm" in chat.
      With **two leaders** it starts the vote.

## 5. Warps, allies, bounties, info

- [ ] Warps: list, set a warp (shows the cost; a bad name gives the error page), teleport (2 s stand-still),
      delete (with confirmation).
- [ ] With an ally that has warps: Offers → Pay / Decline (Esc only goes back), then Ally warps → teleport.
- [ ] Allies: propose an alliance (dropdown), end one (it warns about warp access), Every alliance.
- [ ] Bounties: the list, Set a bounty (leaders; there's a confirmation with the amount), Check a player.
- [ ] Guild info: the banner, wiki button (Minecraft asks before opening the link), tax breakdown, Go home.
- [ ] From Slayer Score or Browse guilds, click **another** guild: its info shows without your guild's
      buttons (no Go home). There's an "Apply to join" button if you have no guild and they're open. Back returns to the chest.

## 6. Staff tools (op or `simpleclans.mod.staffgui`)

- [ ] Guild actions: pick a guild from the dropdown, then Verify, Set home here, Go to their home, bulletin, bank balance,
      give/take/set money, rename, recolor tag, permanent, disband (with confirmation).
- [ ] Player actions: type a name, then kick, make/remove leader, ban/unban, reset KDR, place in a guild, purge
      (with confirmation), look up.
- [ ] Server actions: reload, global FF allow/auto, collect tax now and reset everyone's KDR (both with confirmation).
- [ ] Browse opens the old staff chests. Back returns to Staff tools.
- [ ] A non-staff account doesn't see Staff tools at all.

## 7. Chest screens (reskin)

- [ ] Every chest's Back / Previous / Next are iron arrow heads. The frame row is black glass.
- [ ] Slayer Score: burgundy sides, 7 guilds per row, the rank shown as the stack number (#1 = no number),
      Back in the bottom middle, and Previous / Next in the bottom corners when there's more than one page.
- [ ] Player leaderboard: each head's stack number is the player's rank.
- [ ] Clicking a chest button makes a click sound.

## 8. Old clients and the switch

- [ ] Join with a client **older than 1.21.6** (ViaVersion): `/guild` opens the old chest menu, and it works.
- [ ] Set `settings.menu-style: chest` in config.yml and run `/guild admin reload`: everyone gets chests. Set it back to `dialog`.

## 9. Restart

- [ ] Restart the server. Guilds, ranks, banks, warps, wiki links, applications and settings are all still there,
      and the startup log has no SimpleClans errors.

## 10. Applications (rewritten 2026-10-16)

Needs a leader account and a guildless account. Open the guild first: Leader tools → Settings → Open to applications.

- [ ] **Leader online:** when someone applies, the leader gets `X applied to join your guild: "…" [Accept] [Deny]`
      with a ping. Clicking [Accept] / [Deny] works.
- [ ] The main menu's Leader tools button shows `(N new)`, and Leader tools shows `Applications (N)`.
- [ ] **Leader offline:** on next login the leader gets `Your guild has N application(s) waiting. [Review]`.
- [ ] Application screen: Accept / Deny / Back. **Pressing Esc does NOT deny** (it goes back).
- [ ] Accept works while the applicant is offline; they're in the guild when they log in.
- [ ] Deny: the applicant is told if online, and the other leaders see it.
- [ ] Applicant side: the main menu shows "Application sent to …" and a **My application** button → Withdraw.
      `/guild applications withdraw` does the same.
- [ ] Applying to a second guild replaces the first (you're told).
- [ ] A guild can still **invite** someone who has a pending application elsewhere.
- [ ] Inviting someone who already has an invite waiting says so (and doesn't charge an invite fee).
- [ ] `/guild applications list` works for an **unverified** guild and shows each message.
- [ ] Turning applications off keeps the pending ones in the list.
- [ ] **Restart the server:** pending applications are still there.
