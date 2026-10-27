package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import dev.amsam0.voicechatdiscord.DiscordText.Button;
import dev.amsam0.voicechatdiscord.DiscordText.Click;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongSupplier;

/**
 * Who gets into a {@link CallSession}, decided by the people already in the Discord voice channel.
 * <p>
 * {@code /dvc call} doesn't join straight away: the bot posts "Steve wants to join from Minecraft" with Let in /
 * Deny buttons in the voice channel's own text chat, and only someone in that voice channel can answer (the natives
 * check that before a click gets here). Nobody answering within {@link #REQUEST_TIMEOUT_MS} counts as no.
 * <p>
 * Whether or not approval is on, the channel can always see who's listening: every player in the call has a
 * message in the chat (with a Remove button) that's updated when they leave, and the bot's nickname lists them.
 * <p>
 * Threads: Discord calls block, so they all run on {@code discord} (one at a time, in order). Button clicks are
 * read on their own thread. Player-facing messages are sent from whichever thread finds out.
 */
public final class CallRequests {
    static final long REQUEST_TIMEOUT_MS = 60_000;
    /**
     * After a no (or no answer), before the same player can ask again.
     */
    static final long COOLDOWN_MS = 30_000;
    static final long REMOVED_COOLDOWN_MS = 5 * 60_000;
    static final int NICKNAME_MAX_LENGTH = 32;
    private static final String ID_PREFIX = "vcd:";

    /**
     * Runs something later; tests replace it.
     */
    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMs);
    }

    public interface Cancellable {
        void cancel();
    }

    private static final class Pending {
        final String id = randomId();
        final ServerPlayer player;
        final String name;
        long messageId = 0;
        boolean done = false;
        @Nullable Cancellable timeout = null;

        Pending(ServerPlayer player, String name) {
            this.player = player;
            this.name = name;
        }
    }

    private static final class Member {
        final String name;
        /**
         * Who let them in, or null with approval off.
         */
        final @Nullable String approvedBy;
        long messageId = 0;
        @Nullable String removedBy = null;
        boolean gone = false;

        Member(String name, @Nullable String approvedBy) {
            this.name = name;
            this.approvedBy = approvedBy;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CallSession call;
    private final DiscordText text;
    private final Platform platform;
    private final boolean approval;
    private final Executor discord;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final ThreadFactory clickThreads;

    private final Map<UUID, Pending> pendingByPlayer = new HashMap<>();
    private final Map<String, Pending> pendingById = new HashMap<>();
    private final Map<UUID, Member> members = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private @Nullable String nickname = null;
    private boolean warnedNickname = false;
    private volatile boolean running = false;
    private @Nullable Thread clickThread = null;

    public CallRequests(CallSession call, DiscordText text, Platform platform, boolean approval, Executor discord,
                        Scheduler scheduler, LongSupplier clock, ThreadFactory clickThreads) {
        this.call = call;
        this.text = text;
        this.platform = platform;
        this.approval = approval;
        this.discord = discord;
        this.scheduler = scheduler;
        this.clock = clock;
        this.clickThreads = clickThreads;
        call.setOnParticipantsChanged(() -> discord.execute(this::participantsChanged));
    }

    public CallSession call() {
        return call;
    }

    public boolean approval() {
        return approval;
    }

    /**
     * Starts reading button clicks.
     */
    public void start() {
        running = true;
        clickThread = clickThreads.newThread(() -> {
            while (running) {
                Click click = text.nextClick(1000);
                if (click != null) {
                    try {
                        handleClick(click);
                    } catch (Throwable e) {
                        platform.error("Call '" + call.name() + "' couldn't handle a button click", e);
                    }
                }
            }
        });
        clickThread.start();
    }

    /**
     * Cancels open requests and stops reading clicks. Call after {@link CallSession#hangUpAll}, then wait for the
     * {@code discord} executor to finish (it still has the message edits to do) before freeing the bot.
     */
    public void stop() {
        List<Pending> open;
        synchronized (this) {
            open = new ArrayList<>(pendingById.values());
        }
        for (Pending p : open) cancel(p.player.getUuid(), "The request was cancelled because the server is reloading.");
        running = false;
        Thread thread = clickThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized boolean isPending(UUID player) {
        return pendingByPlayer.containsKey(player);
    }

    /**
     * {@code /dvc call}: asks to join (or joins, with approval off).
     */
    public void request(ServerPlayer player) {
        UUID uuid = player.getUuid();
        String name = platform.getName(player);
        Pending pending;
        synchronized (this) {
            if (call.contains(uuid)) {
                platform.sendMessage(player, Component.yellow("You're already in this call. Leave with "),
                        Component.white("/dvc hangup"), Component.yellow("."));
                return;
            }
            if (pendingByPlayer.containsKey(uuid)) {
                platform.sendMessage(player, Component.yellow("You've already asked. Waiting for someone in the Discord call to answer ("),
                        Component.white("/dvc hangup"), Component.yellow(" cancels)."));
                return;
            }
            // Don't bother the channel about someone who couldn't join anyway.
            if (!call.hasVoiceChat(player)) {
                platform.sendMessage(player, Component.red("You need the Simple Voice Chat mod installed and connected to join a Discord call."));
                return;
            }
            Long until = cooldownUntil.get(uuid);
            long now = clock.getAsLong();
            if (until != null && now < until) {
                long seconds = (until - now + 999) / 1000;
                platform.sendMessage(player, Component.red("Wait " + seconds + " second" + (seconds == 1 ? "" : "s") + " before asking again."));
                return;
            }
            if (!approval) {
                pending = null;
            } else {
                pending = new Pending(player, name);
                pendingByPlayer.put(uuid, pending);
                pendingById.put(pending.id, pending);
            }
        }

        if (pending == null) {
            join(player, name, null, 0);
            return;
        }
        platform.sendMessage(player, Component.yellow("Asking the people in the Discord call to let you in..."));
        discord.execute(() -> post(pending));
    }

    private void post(Pending p) {
        try {
            text.ensureLoggedIn();
        } catch (Throwable e) {
            platform.error("Call '" + call.name() + "' couldn't log in to Discord", e);
            if (finish(p)) {
                platform.sendMessage(p.player, Component.red("Couldn't reach Discord. Ask a server admin to check the console."));
            }
            return;
        }
        if (text.humansInVoiceChannel() == 0) {
            if (finish(p)) {
                platform.sendMessage(p.player, Component.red("Nobody is in the Discord call right now, so there's nobody to let you in. Try again once someone's there."));
            }
            return;
        }
        long expiresAt = (System.currentTimeMillis() + REQUEST_TIMEOUT_MS) / 1000;
        long messageId;
        try {
            messageId = text.postMessage(
                    "🎮 **" + escape(p.name) + "** wants to join this voice channel from Minecraft. "
                            + "Anyone in the channel can answer. Expires <t:" + expiresAt + ":R>.",
                    List.of(new Button(ID_PREFIX + "allow:" + p.id, "Let in", Button.Style.SUCCESS),
                            new Button(ID_PREFIX + "deny:" + p.id, "Deny", Button.Style.DANGER)));
        } catch (Throwable e) {
            platform.error("Call '" + call.name() + "' couldn't post a join request. Does the bot have Send Messages in the voice channel?", e);
            if (finish(p)) {
                platform.sendMessage(p.player, Component.red("Couldn't ask the Discord call. Ask a server admin to check the console."));
            }
            return;
        }
        boolean cancelled;
        synchronized (this) {
            p.messageId = messageId;
            cancelled = p.done;
            if (!cancelled) p.timeout = scheduler.schedule(() -> discord.execute(() -> expire(p)), REQUEST_TIMEOUT_MS);
        }
        if (cancelled) edit(messageId, "🎮 **" + escape(p.name) + "** cancelled their request to join from Minecraft.", List.of());
    }

    /**
     * Takes a request out of the open ones.
     *
     * @return false if something else already finished it
     */
    private synchronized boolean finish(Pending p) {
        if (p.done) return false;
        p.done = true;
        if (p.timeout != null) p.timeout.cancel();
        pendingByPlayer.remove(p.player.getUuid(), p);
        pendingById.remove(p.id);
        return true;
    }

    private void expire(Pending p) {
        if (!finish(p)) return;
        synchronized (this) {
            cooldownUntil.put(p.player.getUuid(), clock.getAsLong() + COOLDOWN_MS);
        }
        edit(p.messageId, "🎮 **" + escape(p.name) + "** asked to join from Minecraft. Nobody answered.", List.of());
        platform.sendMessage(p.player, Component.red("Nobody in the Discord call answered. You can ask again in a bit."));
    }

    /**
     * {@code /dvc hangup} before being let in, or leaving the server.
     *
     * @return whether there was an open request
     */
    public boolean cancel(UUID player, String why) {
        Pending p;
        synchronized (this) {
            p = pendingByPlayer.get(player);
        }
        if (p == null || !finish(p)) return false;
        discord.execute(() -> {
            // Still being posted: post() sees it's done and edits it once it has the message ID.
            if (p.messageId != 0) edit(p.messageId, "🎮 **" + escape(p.name) + "** asked to join from Minecraft. " + why, List.of());
        });
        return true;
    }

    void handleClick(Click click) {
        String id = click.customId();
        if (!id.startsWith(ID_PREFIX)) return;
        String[] parts = id.substring(ID_PREFIX.length()).split(":", 2);
        if (parts.length != 2) return;
        switch (parts[0]) {
            case "allow", "deny" -> answer(parts[1], parts[0].equals("allow"), click);
            case "remove" -> remove(parts[1], click);
            default -> {
            }
        }
    }

    private void answer(String requestId, boolean allow, Click click) {
        Pending p;
        synchronized (this) {
            p = pendingById.get(requestId);
        }
        if (p == null || !finish(p)) {
            // From before a restart, or answered a moment ago by someone else.
            discord.execute(() -> edit(click.messageId(), "This request isn't open anymore.", List.of()));
            return;
        }
        String who = escape(click.userName());
        if (!allow) {
            synchronized (this) {
                cooldownUntil.put(p.player.getUuid(), clock.getAsLong() + COOLDOWN_MS);
            }
            discord.execute(() -> edit(p.messageId, "🎮 **" + escape(p.name) + "** asked to join from Minecraft. " + who + " said no.", List.of()));
            platform.sendMessage(p.player, Component.red("The Discord call said no to your request."));
            return;
        }
        join(p.player, p.name, click.userName(), p.messageId);
    }

    /**
     * Puts the player in the call and makes sure the channel has a message saying so.
     *
     * @param approvedBy who let them in, or null with approval off
     * @param messageId  the request message to turn into the "in the call" one, or 0 to post a new one
     */
    private void join(ServerPlayer player, String name, @Nullable String approvedBy, long messageId) {
        UUID uuid = player.getUuid();
        Member member = new Member(name, approvedBy);
        member.messageId = messageId;
        CallSession.JoinResult result = call.join(player);
        boolean joined = result == CallSession.JoinResult.JOINED || result == CallSession.JoinResult.CONNECTING;
        // After joining: a participantsChanged() queued before the join must not see them as already gone.
        if (joined) {
            synchronized (this) {
                members.put(uuid, member);
            }
        }
        switch (result) {
            case JOINED -> platform.sendMessage(player,
                    Component.green(approvedBy != null ? "You've been let in! " : ""),
                    Component.green("You're in the Discord voice call "), Component.white(String.valueOf(call.channelName())),
                    Component.green(". Leave with "), Component.white("/dvc hangup"), Component.green("."));
            case CONNECTING -> platform.sendMessage(player,
                    Component.green(approvedBy != null ? "You've been let in! " : ""), Component.yellow("Connecting to Discord..."));
            case ALREADY_IN_CALL -> platform.sendMessage(player,
                    Component.yellow("You're already in this call. Leave with "), Component.white("/dvc hangup"), Component.yellow("."));
            case NO_VOICE_CHAT -> platform.sendMessage(player,
                    Component.red("You need the Simple Voice Chat mod installed and connected to join a Discord call."));
            case FAILED -> platform.sendMessage(player,
                    Component.red("Couldn't join the call. Ask a server admin to check the console."));
        }

        String who = "**" + escape(name) + "**";
        discord.execute(() -> {
            if (!joined) {
                if (messageId != 0) edit(messageId, "🎮 " + who + " was let in by " + escape(approvedBy) + " but couldn't join.", List.of());
                return;
            }
            boolean leftAlready;
            synchronized (this) {
                if (member.gone) return; // participantsChanged() already wrote that they left
                // Left between joining and being registered: no participantsChanged() will notice, so do it here.
                leftAlready = !call.contains(uuid);
                if (leftAlready) {
                    member.gone = true;
                    members.remove(uuid, member);
                }
            }
            if (leftAlready) {
                if (messageId != 0) {
                    edit(messageId, leftText(member), List.of());
                } else {
                    platform.debug(name + " left call '" + call.name() + "' before the channel was told they joined");
                }
                return;
            }
            String content = inCallText(member);
            List<Button> buttons = List.of(new Button(ID_PREFIX + "remove:" + uuid, "Remove", Button.Style.SECONDARY));
            if (messageId != 0) {
                edit(messageId, content, buttons);
                return;
            }
            long posted;
            try {
                posted = text.postMessage(content, buttons);
            } catch (Throwable e) {
                platform.error("Call '" + call.name() + "' couldn't post who joined. Does the bot have Send Messages in the voice channel?", e);
                return;
            }
            boolean goneMeanwhile;
            synchronized (this) {
                member.messageId = posted;
                goneMeanwhile = member.gone;
            }
            if (goneMeanwhile) edit(posted, leftText(member), List.of());
        });
    }

    private void remove(String uuidText, Click click) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            return;
        }
        Member member;
        synchronized (this) {
            member = members.get(uuid);
            if (member == null || member.gone || member.messageId != click.messageId()) member = null;
            else member.removedBy = click.userName();
            if (member != null) cooldownUntil.put(uuid, clock.getAsLong() + REMOVED_COOLDOWN_MS);
        }
        if (member == null) {
            discord.execute(() -> edit(click.messageId(), "This player isn't in the call anymore.", List.of()));
            return;
        }
        ServerPlayer player = null;
        for (ServerPlayer p : call.participants()) {
            if (p.getUuid().equals(uuid)) player = p;
        }
        // participantsChanged() writes the message, with removedBy.
        if (call.leave(uuid) && player != null) {
            platform.sendMessage(player, Component.red("Someone in the Discord call removed you from it."));
        }
    }

    /**
     * Runs on {@code discord} after anyone joins or leaves: marks who left in the chat and updates the nickname.
     */
    private void participantsChanged() {
        List<Member> left = new ArrayList<>();
        List<String> names = new ArrayList<>();
        synchronized (this) {
            Set<UUID> inCall = new HashSet<>();
            for (ServerPlayer p : call.participants()) {
                inCall.add(p.getUuid());
                Member m = members.get(p.getUuid());
                names.add(m != null ? m.name : platform.getName(p));
            }
            members.entrySet().removeIf(e -> {
                if (inCall.contains(e.getKey())) return false;
                e.getValue().gone = true;
                left.add(e.getValue());
                return true;
            });
        }
        for (Member m : left) {
            if (m.messageId != 0) edit(m.messageId, leftText(m), List.of());
        }
        String wanted = nickname(names);
        if (!java.util.Objects.equals(wanted, nickname)) {
            try {
                text.setNickname(wanted);
                nickname = wanted;
            } catch (Throwable e) {
                if (!warnedNickname) {
                    warnedNickname = true;
                    platform.warn("Call '" + call.name() + "' couldn't change its Discord nickname to show who's listening. "
                            + "Give the bot Change Nickname. (" + e.getMessage() + ")");
                }
            }
        }
    }

    private static String inCallText(Member m) {
        return "🎮 **" + escape(m.name) + "** is in the call from Minecraft"
                + (m.approvedBy != null ? ", let in by " + escape(m.approvedBy) : "") + ". They hear everyone here.";
    }

    private static String leftText(Member m) {
        return "🎮 **" + escape(m.name) + "** was in the call from Minecraft"
                + (m.approvedBy != null ? " (let in by " + escape(m.approvedBy) + ")" : "")
                + (m.removedBy != null ? " and was removed by " + escape(m.removedBy) + "." : " and has left.");
    }

    private void edit(long messageId, String content, List<Button> buttons) {
        if (messageId == 0) return;
        try {
            text.editMessage(messageId, content, buttons);
        } catch (Throwable e) {
            platform.warn("Call '" + call.name() + "' couldn't update a message in the voice channel's chat: " + e.getMessage());
        }
    }

    /**
     * "MC: Steve, Alex", shortened to "MC: Steve +2" (and so on) to fit Discord's 32 characters; null when nobody's
     * listening, which puts the bot's own name back.
     */
    static @Nullable String nickname(List<String> names) {
        if (names.isEmpty()) return null;
        for (int shown = names.size(); shown >= 1; shown--) {
            String nick = "MC: " + String.join(", ", names.subList(0, shown)) + (shown < names.size() ? " +" + (names.size() - shown) : "");
            if (nick.length() <= NICKNAME_MAX_LENGTH) return nick;
        }
        return "MC: " + names.size() + " listening";
    }

    /**
     * Minecraft names have underscores, which Discord would turn into italics.
     */
    static String escape(String s) {
        return s.replaceAll("([\\\\*_~`|>#\\[\\]()<:@-])", "\\\\$1");
    }

    private static String randomId() {
        return Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE);
    }
}
