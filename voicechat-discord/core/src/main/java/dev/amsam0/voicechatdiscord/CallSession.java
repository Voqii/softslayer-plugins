package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * A "call" bot: links one Discord voice channel with any number of Simple Voice Chat players.
 * <p>
 * Players who join (with {@code /dvc call}) hear everyone in the Discord channel, mixed, as non-positional audio
 * that ignores distance and groups, and the Discord channel hears their microphone. Nobody else in game is
 * involved, so unlike {@code /dvc start} the player needs the Simple Voice Chat mod and nobody has to stand in
 * for the Discord side.
 * <p>
 * The bot only sits in the voice channel while at least one player is in the call, so the bot being in the
 * channel means someone in game can hear it.
 */
public final class CallSession {
    /**
     * Volume slider players get in Simple Voice Chat's settings for the Discord audio.
     */
    public static final String VOLUME_CATEGORY = "discord_call";
    /**
     * How often idle Minecraft senders' decoders are reset. Matches upstream's reset thread.
     */
    static final long RESET_INTERVAL_MS = 500;

    public enum JoinResult {
        /**
         * Joined, and the bot is already in the voice channel.
         */
        JOINED,
        /**
         * Joined; the bot is connecting and the player will get a message when it's done.
         */
        CONNECTING,
        ALREADY_IN_CALL,
        /**
         * The player doesn't have Simple Voice Chat installed or connected.
         */
        NO_VOICE_CHAT,
        FAILED,
    }

    private enum State {IDLE, CONNECTING, CONNECTED}

    private record Participant(ServerPlayer player, StaticAudioChannel channel) {
    }

    private final String name;
    private final long vcId;
    private final DiscordVoice voice;
    /**
     * Looked up when needed: Simple Voice Chat may hand us the API after the config is loaded.
     */
    private final Supplier<VoicechatServerApi> api;
    private final Platform platform;
    /**
     * Runs connect/disconnect one at a time, off the server thread (both block on Discord).
     */
    private final Executor worker;
    private final ThreadFactory pumpThreads;

    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private State state = State.IDLE;
    private String channelName = null;
    /**
     * Bumped on every connect and disconnect, so a pump thread from an old connection stops.
     */
    private int generation = 0;
    private boolean discordTalking = false;
    private Thread audioThread = null;
    private long lastReset = 0;
    private Runnable onParticipantsChanged = () -> {
    };

    public CallSession(String name, long vcId, DiscordVoice voice, Supplier<VoicechatServerApi> api, Platform platform,
                       Executor worker, ThreadFactory pumpThreads) {
        this.name = name;
        this.vcId = vcId;
        this.voice = voice;
        this.api = api;
        this.platform = platform;
        this.worker = worker;
        this.pumpThreads = pumpThreads;
    }

    public String name() {
        return name;
    }

    /**
     * Runs whenever someone joins or leaves, while the call's lock is held: it must only hand the work off to
     * another thread.
     */
    public synchronized void setOnParticipantsChanged(Runnable onParticipantsChanged) {
        this.onParticipantsChanged = onParticipantsChanged;
    }

    public long vcId() {
        return vcId;
    }

    public synchronized boolean isConnected() {
        return state == State.CONNECTED;
    }

    public synchronized boolean contains(UUID player) {
        return participants.containsKey(player);
    }

    public synchronized List<ServerPlayer> participants() {
        List<ServerPlayer> players = new ArrayList<>();
        for (Participant p : participants.values()) players.add(p.player());
        return players;
    }

    /**
     * The same player always gets the same channel ID, so their client treats a rejoin as the same stream.
     */
    static UUID channelId(long vcId, UUID player) {
        return UUID.nameUUIDFromBytes(("voicechat-discord-call:" + vcId + ":" + player).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether the player has Simple Voice Chat installed and connected, i.e. could join right now.
     */
    public boolean hasVoiceChat(ServerPlayer player) {
        VoicechatServerApi api = this.api.get();
        if (api == null) return false;
        VoicechatConnection connection = api.getConnectionOf(player.getUuid());
        return connection != null && connection.isInstalled();
    }

    public synchronized JoinResult join(ServerPlayer player) {
        UUID uuid = player.getUuid();
        if (participants.containsKey(uuid)) return JoinResult.ALREADY_IN_CALL;

        VoicechatServerApi api = this.api.get();
        if (api == null) return JoinResult.NO_VOICE_CHAT;
        VoicechatConnection connection = api.getConnectionOf(uuid);
        if (connection == null || !connection.isInstalled()) return JoinResult.NO_VOICE_CHAT;

        // Simple Voice Chat ignores the level for this kind of channel: it follows the player's connection
        // across dimensions.
        StaticAudioChannel channel = api.createStaticAudioChannel(channelId(vcId, uuid), player.getServerLevel(), connection);
        if (channel == null) {
            platform.error("Couldn't create an audio channel for " + platform.getName(player) + " (call '" + name + "')");
            return JoinResult.FAILED;
        }
        channel.setCategory(VOLUME_CATEGORY);
        bypassGroupIsolation(channel);
        participants.put(uuid, new Participant(player, channel));
        platform.info(platform.getName(player) + " joined call '" + name + "'");
        onParticipantsChanged.run();

        return switch (state) {
            case CONNECTED -> JoinResult.JOINED;
            case CONNECTING -> JoinResult.CONNECTING;
            case IDLE -> {
                state = State.CONNECTING;
                int gen = ++generation;
                worker.execute(() -> connect(gen));
                yield JoinResult.CONNECTING;
            }
        };
    }

    private boolean warnedNoBypass = false;

    /**
     * Players in an isolated voice group should still hear the call. The Fabric/NeoForge Simple Voice Chat does
     * this for the channel type we create, but the Bukkit/Paper one (checked: 2.6.21) doesn't, so ask for it.
     * {@code setBypassGroupIsolation} isn't in the 2.4.11 API this project compiles against, hence reflection.
     */
    private void bypassGroupIsolation(StaticAudioChannel channel) {
        try {
            channel.getClass().getMethod("setBypassGroupIsolation", boolean.class).invoke(channel, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedNoBypass) {
                warnedNoBypass = true;
                platform.warn("This Simple Voice Chat version can't let call audio through to players in isolated "
                        + "groups. Players in an isolated group won't hear Discord calls until Simple Voice Chat is updated.");
            }
        }
    }

    /**
     * @return whether the player was in the call
     */
    public synchronized boolean leave(UUID player) {
        Participant removed = participants.remove(player);
        if (removed == null) return false;
        removed.channel().flush();
        platform.info(platform.getName(removed.player()) + " left call '" + name + "'");
        onParticipantsChanged.run();
        if (participants.isEmpty() && state == State.CONNECTED) {
            disconnectLocked();
        }
        // If we're still CONNECTING, connect() sees the empty call when it finishes and disconnects.
        return true;
    }

    /**
     * Ends the call for everyone (config reload, shutdown).
     */
    public synchronized void hangUpAll(Component... reason) {
        for (Participant p : participants.values()) {
            p.channel().flush();
            if (reason.length > 0) platform.sendMessage(p.player(), reason);
        }
        boolean anyone = !participants.isEmpty();
        participants.clear();
        if (anyone) onParticipantsChanged.run();
        if (state != State.IDLE) disconnectLocked();
    }

    private void disconnectLocked() {
        state = State.IDLE;
        channelName = null;
        generation++;
        discordTalking = false;
        worker.execute(() -> {
            voice.disconnect();
            platform.info("Call '" + name + "' left the Discord voice channel");
        });
    }

    private void connect(int gen) {
        String joinedChannel;
        try {
            joinedChannel = voice.connect();
        } catch (Throwable e) {
            platform.error("Call '" + name + "' couldn't join Discord voice channel " + vcId, e);
            synchronized (this) {
                if (gen != generation) return;
                for (Participant p : participants.values()) {
                    platform.sendMessage(p.player(),
                            Component.red("Couldn't connect to Discord. Ask a server admin to check the console."));
                }
                participants.clear();
                onParticipantsChanged.run();
                state = State.IDLE;
            }
            return;
        }

        boolean everyoneLeft;
        synchronized (this) {
            if (gen != generation) return; // hung up while connecting; disconnectLocked() already queued a disconnect
            everyoneLeft = participants.isEmpty();
            if (everyoneLeft) {
                state = State.IDLE;
                generation++;
            }
        }
        if (everyoneLeft) {
            // Outside the lock: this blocks on Discord, and join() runs on the server thread.
            voice.disconnect();
            return;
        }

        synchronized (this) {
            if (gen != generation) return;
            state = State.CONNECTED;
            channelName = joinedChannel;
            lastReset = System.currentTimeMillis();
            platform.info("Call '" + name + "' joined Discord voice channel " + joinedChannel);
            for (Participant p : participants.values()) {
                platform.sendMessage(p.player(),
                        Component.green("Connected! You're in the Discord voice call "),
                        Component.white(joinedChannel),
                        Component.green(". Leave with "),
                        Component.white("/dvc hangup"),
                        Component.green("."));
            }
            audioThread = pumpThreads.newThread(() -> {
                while (pumpOnce(gen)) {
                    // keep going
                }
            });
            audioThread.start();
        }
    }

    public synchronized String channelName() {
        return channelName;
    }

    /**
     * Moves one packet (or one silent second) of Discord audio to everyone in the call. Blocks on Discord, so
     * it's called in a loop on the pump thread.
     *
     * @return false once this connection is over and the loop should stop
     */
    boolean pumpOnce(int gen) {
        synchronized (this) {
            if (gen != generation) return false;
        }
        byte[] audio = voice.nextDiscordAudio();

        synchronized (this) {
            if (gen != generation) return false;
            if (audio.length > 0) {
                discordTalking = true;
                for (Participant p : participants.values()) p.channel().send(audio);
            } else if (discordTalking) {
                // Tell clients the stream ended, so the last word isn't left hanging in their jitter buffer.
                discordTalking = false;
                for (Participant p : participants.values()) p.channel().flush();
            }
            long now = System.currentTimeMillis();
            if (now - lastReset >= RESET_INTERVAL_MS) {
                lastReset = now;
                voice.resetIdleSenders();
            }
            return true;
        }
    }

    /**
     * Sends a call participant's microphone to Discord. Registered at low priority: if another plugin (a mute
     * plugin, say) cancels the packet, Simple Voice Chat stops dispatching it and we never see it.
     */
    public void onMicrophone(MicrophonePacketEvent event) {
        if (event.isCancelled()) return;
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;
        UUID uuid = sender.getPlayer().getUuid();
        synchronized (this) {
            if (state != State.CONNECTED || !participants.containsKey(uuid)) return;
        }
        voice.sendToDiscord(uuid.hashCode(), event.getPacket().getOpusEncodedData());
    }

    /**
     * Waits for the audio thread of a hung-up call to finish its last {@link DiscordVoice#nextDiscordAudio()}
     * (at most about a second). Call before freeing the bot.
     *
     * @return false if it's still running
     */
    public boolean awaitAudioStopped(long timeoutMs) throws InterruptedException {
        Thread thread;
        synchronized (this) {
            thread = audioThread;
        }
        if (thread == null || thread == Thread.currentThread()) return true;
        thread.join(timeoutMs);
        return !thread.isAlive();
    }

    synchronized int generationForTest() {
        return generation;
    }
}
