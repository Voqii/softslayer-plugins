package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Call mode without Discord or a Minecraft server: a fake {@link DiscordVoice} and a mocked Simple Voice Chat API.
 */
class CallSessionTest {
    private static final long VC_ID = 1234567890123456789L;

    /**
     * Records everything the call does to Discord.
     */
    static final class FakeVoice implements DiscordVoice {
        int connects = 0;
        int disconnects = 0;
        int resets = 0;
        Exception failNextConnect = null;
        final Queue<byte[]> discordAudio = new ArrayDeque<>();
        final List<String> sentToDiscord = new ArrayList<>();

        @Override
        public String connect() throws Exception {
            connects++;
            if (failNextConnect != null) {
                Exception e = failNextConnect;
                failNextConnect = null;
                throw e;
            }
            return "General";
        }

        @Override
        public void disconnect() {
            disconnects++;
        }

        @Override
        public byte[] nextDiscordAudio() {
            byte[] next = discordAudio.poll();
            return next == null ? new byte[0] : next;
        }

        @Override
        public void sendToDiscord(int senderId, byte[] opusData) {
            sentToDiscord.add(senderId + ":" + Arrays.toString(opusData));
        }

        @Override
        public void resetIdleSenders() {
            resets++;
        }
    }

    /**
     * Holds tasks until {@link #runAll()}, to test what happens while the bot is still connecting.
     */
    static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }

    private FakeVoice voice;
    private VoicechatServerApi api;
    private Platform platform;
    private final Map<UUID, StaticAudioChannel> channels = new HashMap<>();
    private final Map<UUID, VoicechatConnection> connections = new HashMap<>();
    private final List<Runnable> startedAudioThreads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        voice = new FakeVoice();
        api = mock(VoicechatServerApi.class);
        platform = mock(Platform.class);
        when(platform.getName(any())).thenAnswer(i -> "player-" + ((ServerPlayer) i.getArgument(0)).getUuid().toString().substring(0, 4));
        when(api.getConnectionOf(any(UUID.class))).thenAnswer(i -> connections.get(i.<UUID>getArgument(0)));
        when(api.createStaticAudioChannel(any(UUID.class), any(), any())).thenAnswer(i -> {
            VoicechatConnection connection = i.getArgument(2);
            StaticAudioChannel channel = channelsSupportBypass ? mock(ChannelWithBypass.class) : mock(StaticAudioChannel.class);
            channels.put(connection.getPlayer().getUuid(), channel);
            return channel;
        });
    }

    /**
     * What newer Simple Voice Chat versions' static channels have (not in the 2.4.11 API we compile against).
     */
    public interface ChannelWithBypass extends StaticAudioChannel {
        void setBypassGroupIsolation(boolean bypass);
    }

    private boolean channelsSupportBypass = true;

    private CallSession session(Executor worker) {
        return new CallSession("friends", VC_ID, voice, () -> api, platform, worker,
                r -> {
                    // Don't actually run the audio loop; tests drive pumpOnce() themselves.
                    startedAudioThreads.add(r);
                    return new Thread(() -> {
                    });
                });
    }

    private CallSession connectedSession() {
        return session(Runnable::run);
    }

    private ServerPlayer player(boolean hasMod) {
        UUID uuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUuid()).thenReturn(uuid);
        when(player.getServerLevel()).thenReturn(mock(ServerLevel.class));
        VoicechatConnection connection = mock(VoicechatConnection.class);
        when(connection.isInstalled()).thenReturn(hasMod);
        when(connection.getPlayer()).thenReturn(player);
        connections.put(uuid, connection);
        return player;
    }

    private MicrophonePacketEvent mic(ServerPlayer from, byte[] opus, boolean cancelled) {
        // Look the connection up before stubbing: calling a mock inside when(...) confuses Mockito.
        VoicechatConnection sender = connections.get(from.getUuid());
        MicrophonePacket packet = mock(MicrophonePacket.class);
        when(packet.getOpusEncodedData()).thenReturn(opus);
        MicrophonePacketEvent event = mock(MicrophonePacketEvent.class);
        when(event.getPacket()).thenReturn(packet);
        when(event.getSenderConnection()).thenReturn(sender);
        when(event.isCancelled()).thenReturn(cancelled);
        return event;
    }

    private List<String> messagesTo(ServerPlayer player) {
        ArgumentCaptor<Component[]> captor = ArgumentCaptor.forClass(Component[].class);
        verify(platform, atLeast(0)).sendMessage(eq(player), captor.capture());
        List<String> messages = new ArrayList<>();
        for (Component[] parts : captor.getAllValues()) {
            StringBuilder text = new StringBuilder();
            for (Component part : parts) text.append(part.text());
            messages.add(text.toString());
        }
        return messages;
    }

    @Test
    void playerWithoutTheModCantJoin() {
        CallSession call = connectedSession();
        assertEquals(CallSession.JoinResult.NO_VOICE_CHAT, call.join(player(false)));
        assertEquals(0, voice.connects);
        assertTrue(call.participants().isEmpty());
    }

    @Test
    void firstPlayerConnectsTheBotAndIsToldWhere() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);

        assertEquals(CallSession.JoinResult.CONNECTING, call.join(alice));

        assertTrue(call.isConnected());
        assertEquals(1, voice.connects);
        assertEquals("General", call.channelName());
        verify(channels.get(alice.getUuid())).setCategory(CallSession.VOLUME_CATEGORY);
        assertTrue(messagesTo(alice).stream().anyMatch(m -> m.contains("Connected") && m.contains("General")));
        assertEquals(1, startedAudioThreads.size());
    }

    @Test
    void callAudioBypassesIsolatedGroups() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);
        verify((ChannelWithBypass) channels.get(alice.getUuid())).setBypassGroupIsolation(true);
        verify(platform, never()).warn(any());
    }

    @Test
    void olderVoiceChatWithoutBypassStillWorksButWarnsOnce() {
        channelsSupportBypass = false;
        CallSession call = connectedSession();
        assertEquals(CallSession.JoinResult.CONNECTING, call.join(player(true)));
        assertEquals(CallSession.JoinResult.JOINED, call.join(player(true)));
        verify(platform, times(1)).warn(argThat(m -> m.contains("isolated")));
    }

    @Test
    void laterPlayersJoinTheExistingConnection() {
        CallSession call = connectedSession();
        call.join(player(true));
        assertEquals(CallSession.JoinResult.JOINED, call.join(player(true)));
        assertEquals(1, voice.connects);
        assertEquals(2, call.participants().size());
    }

    @Test
    void joiningTwiceIsRefused() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);
        assertEquals(CallSession.JoinResult.ALREADY_IN_CALL, call.join(alice));
    }

    @Test
    void discordAudioReachesEveryoneInTheCall() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        ServerPlayer bob = player(true);
        ServerPlayer outsider = player(true);
        call.join(alice);
        call.join(bob);
        byte[] packet = {1, 2, 3};
        voice.discordAudio.add(packet);

        assertTrue(call.pumpOnce(call.generationForTest()));

        verify(channels.get(alice.getUuid())).send(packet);
        verify(channels.get(bob.getUuid())).send(packet);
        assertNull(channels.get(outsider.getUuid()), "players not in the call get no channel at all");
    }

    @Test
    void streamIsFlushedOnceWhenDiscordGoesQuiet() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);
        StaticAudioChannel channel = channels.get(alice.getUuid());
        int gen = call.generationForTest();

        call.pumpOnce(gen); // quiet before anyone talked: nothing to flush
        verify(channel, never()).flush();

        voice.discordAudio.add(new byte[]{9});
        call.pumpOnce(gen);
        call.pumpOnce(gen); // quiet
        call.pumpOnce(gen); // still quiet
        verify(channel, times(1)).flush();
    }

    @Test
    void microphoneOfPlayersInTheCallGoesToDiscord() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        ServerPlayer outsider = player(true);
        call.join(alice);

        call.onMicrophone(mic(alice, new byte[]{7}, false));
        call.onMicrophone(mic(outsider, new byte[]{8}, false));

        assertEquals(List.of(alice.getUuid().hashCode() + ":[7]"), voice.sentToDiscord);
    }

    @Test
    void cancelledMicrophonePacketsAreNotSent() {
        // e.g. a mute plugin cancelled it at a higher priority
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);
        call.onMicrophone(mic(alice, new byte[]{7}, true));
        assertTrue(voice.sentToDiscord.isEmpty());
    }

    @Test
    void microphoneIsIgnoredUntilTheBotHasConnected() {
        ManualExecutor worker = new ManualExecutor();
        CallSession call = session(worker);
        ServerPlayer alice = player(true);
        call.join(alice);

        call.onMicrophone(mic(alice, new byte[]{7}, false));
        assertTrue(voice.sentToDiscord.isEmpty());

        worker.runAll();
        call.onMicrophone(mic(alice, new byte[]{7}, false));
        assertEquals(1, voice.sentToDiscord.size());
    }

    @Test
    void botLeavesDiscordWhenTheLastPlayerHangsUp() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        ServerPlayer bob = player(true);
        call.join(alice);
        call.join(bob);

        assertTrue(call.leave(alice.getUuid()));
        assertEquals(0, voice.disconnects, "bob is still in the call");
        assertTrue(call.isConnected());

        assertTrue(call.leave(bob.getUuid()));
        assertEquals(1, voice.disconnects);
        assertFalse(call.isConnected());
        assertNull(call.channelName());
        assertFalse(call.leave(bob.getUuid()), "can't leave twice");
    }

    @Test
    void oldAudioLoopStopsAfterHangUp() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);
        int gen = call.generationForTest();
        call.leave(alice.getUuid());

        voice.discordAudio.add(new byte[]{1});
        assertFalse(call.pumpOnce(gen));
        verify(channels.get(alice.getUuid()), never()).send(any(byte[].class));
    }

    @Test
    void hangingUpWhileConnectingLeavesDiscordAsSoonAsItConnects() {
        ManualExecutor worker = new ManualExecutor();
        CallSession call = session(worker);
        ServerPlayer alice = player(true);
        assertEquals(CallSession.JoinResult.CONNECTING, call.join(alice));
        call.leave(alice.getUuid());

        worker.runAll(); // the connect finishes now

        assertEquals(1, voice.connects);
        assertEquals(1, voice.disconnects);
        assertFalse(call.isConnected());
        assertTrue(startedAudioThreads.isEmpty());
        assertFalse(messagesTo(alice).stream().anyMatch(m -> m.contains("Connected")));
    }

    @Test
    void failedConnectTellsPlayersAndAllowsRetrying() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        voice.failNextConnect = new Exception("bad token");

        call.join(alice);

        assertFalse(call.isConnected());
        assertTrue(call.participants().isEmpty());
        assertTrue(messagesTo(alice).stream().anyMatch(m -> m.contains("Couldn't connect")));
        verify(platform).error(contains("couldn't join"), any(Exception.class));

        assertEquals(CallSession.JoinResult.CONNECTING, call.join(alice));
        assertTrue(call.isConnected());
        assertEquals(2, voice.connects);
    }

    @Test
    void rejoiningAfterTheCallEndedReconnects() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);
        call.leave(alice.getUuid());
        call.join(alice);
        assertEquals(2, voice.connects);
        assertTrue(call.isConnected());
    }

    @Test
    void hangUpAllTellsEveryoneAndDisconnects() {
        CallSession call = connectedSession();
        ServerPlayer alice = player(true);
        call.join(alice);

        call.hangUpAll(Component.red("reloading"));

        assertTrue(call.participants().isEmpty());
        assertEquals(1, voice.disconnects);
        assertTrue(messagesTo(alice).contains("reloading"));
    }

    @Test
    void idleSendersAreResetPeriodically() throws InterruptedException {
        CallSession call = connectedSession();
        call.join(player(true));
        int gen = call.generationForTest();
        Thread.sleep(CallSession.RESET_INTERVAL_MS + 50);
        call.pumpOnce(gen);
        assertEquals(1, voice.resets);
        call.pumpOnce(gen); // too soon for another
        assertEquals(1, voice.resets);
    }

    @Test
    void channelIdIsStablePerPlayerAndCall() {
        UUID player = UUID.randomUUID();
        assertEquals(CallSession.channelId(VC_ID, player), CallSession.channelId(VC_ID, player));
        assertNotEquals(CallSession.channelId(VC_ID, player), CallSession.channelId(VC_ID + 1, player));
        assertNotEquals(CallSession.channelId(VC_ID, player), CallSession.channelId(VC_ID, UUID.randomUUID()));
    }
}
