package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import dev.amsam0.voicechatdiscord.CallSessionTest.ManualExecutor;
import dev.amsam0.voicechatdiscord.DiscordText.Button;
import dev.amsam0.voicechatdiscord.DiscordText.Click;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Join requests without Discord: a fake {@link DiscordText}, a real {@link CallSession} over a fake voice
 * connection, and a hand-cranked executor, scheduler and clock.
 */
class CallRequestsTest {
    /**
     * Records every message, edit and nickname, like the chat would show them.
     */
    static final class FakeText implements DiscordText {
        record Message(String content, List<Button> buttons) {
        }

        final Map<Long, Message> messages = new HashMap<>();
        final List<String> nicknames = new ArrayList<>();
        int humans = 1;
        int logIns = 0;
        Exception failLogIn = null;
        Exception failNickname = null;
        long nextId = 100;

        @Override
        public void ensureLoggedIn() throws Exception {
            logIns++;
            if (failLogIn != null) throw failLogIn;
        }

        @Override
        public int humansInVoiceChannel() {
            return humans;
        }

        @Override
        public long postMessage(String content, List<Button> buttons) {
            long id = nextId++;
            messages.put(id, new Message(content, buttons));
            return id;
        }

        @Override
        public void editMessage(long messageId, String content, List<Button> buttons) {
            assertTrue(messages.containsKey(messageId), "edited a message that was never posted: " + messageId);
            messages.put(messageId, new Message(content, buttons));
        }

        @Override
        public void setNickname(String nickname) throws Exception {
            if (failNickname != null) throw failNickname;
            nicknames.add(nickname);
        }

        @Override
        public Click nextClick(long timeoutMs) {
            return null;
        }

        Message only() {
            assertEquals(1, messages.size(), "messages: " + messages);
            return messages.values().iterator().next();
        }

        long onlyId() {
            assertEquals(1, messages.size(), "messages: " + messages);
            return messages.keySet().iterator().next();
        }

        String buttonId(long messageId, String label) {
            for (Button b : messages.get(messageId).buttons()) {
                if (b.label().equals(label)) return b.id();
            }
            fail("no " + label + " button on " + messages.get(messageId));
            return null;
        }

        String lastNickname() {
            return nicknames.isEmpty() ? "<never set>" : nicknames.get(nicknames.size() - 1);
        }
    }

    private FakeText text;
    private Platform platform;
    private VoicechatServerApi api;
    private ManualExecutor discord;
    private final List<Runnable> scheduled = new ArrayList<>();
    private long now = 1_000_000;
    private final Map<UUID, VoicechatConnection> connections = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private CallSession call;

    @BeforeEach
    void setUp() {
        text = new FakeText();
        platform = mock(Platform.class);
        api = mock(VoicechatServerApi.class);
        discord = new ManualExecutor();
        when(platform.getName(any())).thenAnswer(i -> names.get(((ServerPlayer) i.getArgument(0)).getUuid()));
        when(api.getConnectionOf(any(UUID.class))).thenAnswer(i -> connections.get(i.<UUID>getArgument(0)));
        when(api.createStaticAudioChannel(any(UUID.class), any(), any())).thenAnswer(i -> mock(StaticAudioChannel.class));
        call = new CallSession("friends", 42L, new CallSessionTest.FakeVoice(), () -> api, platform, Runnable::run,
                r -> new Thread(() -> {
                }));
    }

    private CallRequests requests(boolean approval) {
        return new CallRequests(call, text, platform, approval, discord,
                (task, delayMs) -> {
                    assertEquals(CallRequests.REQUEST_TIMEOUT_MS, delayMs);
                    scheduled.add(task);
                    return () -> scheduled.remove(task);
                },
                () -> now, r -> new Thread(r));
    }

    private ServerPlayer player(String name) {
        UUID uuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUuid()).thenReturn(uuid);
        when(player.getServerLevel()).thenReturn(mock(ServerLevel.class));
        VoicechatConnection connection = mock(VoicechatConnection.class);
        when(connection.isInstalled()).thenReturn(true);
        when(connection.getPlayer()).thenReturn(player);
        connections.put(uuid, connection);
        names.put(uuid, name);
        return player;
    }

    private List<String> messagesTo(ServerPlayer player) {
        ArgumentCaptor<Component[]> captor = ArgumentCaptor.forClass(Component[].class);
        verify(platform, atLeast(0)).sendMessage(eq(player), captor.capture());
        List<String> messages = new ArrayList<>();
        for (Component[] parts : captor.getAllValues()) {
            StringBuilder s = new StringBuilder();
            for (Component part : parts) s.append(part.text());
            messages.add(s.toString());
        }
        return messages;
    }

    private String lastMessageTo(ServerPlayer player) {
        List<String> messages = messagesTo(player);
        assertFalse(messages.isEmpty(), "no messages to the player");
        return messages.get(messages.size() - 1);
    }

    private void click(CallRequests requests, long messageId, String label, String who) {
        requests.handleClick(new Click(text.buttonId(messageId, label), messageId, who));
        discord.runAll();
    }

    /**
     * Asks, and lets the request get posted.
     */
    private long ask(CallRequests requests, ServerPlayer player) {
        int before = text.messages.size();
        requests.request(player);
        discord.runAll();
        assertEquals(before + 1, text.messages.size(), "the request should have been posted");
        return text.nextId - 1;
    }

    @Test
    void askingPostsLetInAndDenyAndDoesntJoinYet() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve_42");

        requests.request(steve);
        assertTrue(lastMessageTo(steve).contains("Asking"));
        assertTrue(text.messages.isEmpty(), "posting happens on the Discord thread");
        discord.runAll();

        FakeText.Message posted = text.only();
        assertTrue(posted.content().contains("**Steve\\_42** wants to join"), posted.content());
        assertTrue(posted.content().contains("<t:"), "shows when it expires");
        assertEquals(List.of("Let in", "Deny"), posted.buttons().stream().map(Button::label).toList());
        assertFalse(call.contains(steve.getUuid()));
        assertTrue(requests.isPending(steve.getUuid()));
        assertEquals(1, scheduled.size(), "expiry is scheduled");
    }

    @Test
    void letInJoinsAndTheRequestBecomesTheInCallMessage() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);

        click(requests, id, "Let in", "Alice");

        assertTrue(call.contains(steve.getUuid()));
        assertFalse(requests.isPending(steve.getUuid()));
        assertTrue(scheduled.isEmpty(), "expiry is cancelled");
        FakeText.Message msg = text.only();
        assertTrue(msg.content().contains("**Steve** is in the call from Minecraft, let in by Alice"), msg.content());
        assertEquals(List.of("Remove"), msg.buttons().stream().map(Button::label).toList());
        assertEquals("MC: Steve", text.lastNickname());
        assertTrue(messagesTo(steve).stream().anyMatch(m -> m.contains("let in")));
        assertTrue(messagesTo(steve).stream().noneMatch(m -> m.contains("Alice")), "Discord names stay in Discord");
    }

    @Test
    void denyKeepsThemOutWithACooldown() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);

        click(requests, id, "Deny", "Alice");

        assertFalse(call.contains(steve.getUuid()));
        FakeText.Message msg = text.only();
        assertTrue(msg.content().contains("Alice said no"), msg.content());
        assertTrue(msg.buttons().isEmpty());
        assertTrue(lastMessageTo(steve).contains("said no"));
        assertEquals("<never set>", text.lastNickname());

        requests.request(steve);
        assertTrue(lastMessageTo(steve).contains("Wait 30 seconds"), lastMessageTo(steve));
        now += CallRequests.COOLDOWN_MS;
        ask(requests, steve);
    }

    @Test
    void noAnswerIsANo() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);

        scheduled.remove(0).run();
        discord.runAll();

        assertFalse(call.contains(steve.getUuid()));
        assertFalse(requests.isPending(steve.getUuid()));
        assertTrue(text.messages.get(id).content().contains("Nobody answered"));
        assertTrue(text.messages.get(id).buttons().isEmpty());
        assertTrue(lastMessageTo(steve).contains("Nobody in the Discord call answered"));

        // A click that arrives after it expired doesn't let them in.
        requests.handleClick(new Click("vcd:allow:whatever", id, "Alice"));
        discord.runAll();
        assertFalse(call.contains(steve.getUuid()));
    }

    @Test
    void onlyTheFirstAnswerCounts() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);
        String allow = text.buttonId(id, "Let in");
        String deny = text.buttonId(id, "Deny");

        requests.handleClick(new Click(deny, id, "Bob"));
        requests.handleClick(new Click(allow, id, "Alice"));
        discord.runAll();

        assertFalse(call.contains(steve.getUuid()));
    }

    @Test
    void emptyVoiceChannelMeansNobodyToAsk() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        text.humans = 0;

        requests.request(steve);
        discord.runAll();

        assertTrue(text.messages.isEmpty());
        assertFalse(requests.isPending(steve.getUuid()));
        assertTrue(lastMessageTo(steve).contains("Nobody is in the Discord call"));
        // Not their fault, so no cooldown.
        text.humans = 1;
        ask(requests, steve);
    }

    @Test
    void cantReachDiscord() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        text.failLogIn = new Exception("bad token");

        requests.request(steve);
        discord.runAll();

        assertTrue(text.messages.isEmpty());
        assertFalse(requests.isPending(steve.getUuid()));
        assertTrue(lastMessageTo(steve).contains("Couldn't reach Discord"));
        verify(platform).error(contains("couldn't log in"), any(Throwable.class));
    }

    @Test
    void askingTwiceDoesntPostTwice() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        ask(requests, steve);

        requests.request(steve);
        discord.runAll();

        assertEquals(1, text.messages.size());
        assertTrue(lastMessageTo(steve).contains("already asked"));
    }

    @Test
    void staleButtonsAreCleanedUp() {
        CallRequests requests = requests(true);
        long old = text.postMessage("Steve wants to join (from before a restart)", List.of());

        requests.handleClick(new Click("vcd:allow:deadbeef", old, "Alice"));
        requests.handleClick(new Click("vcd:remove:" + UUID.randomUUID(), old, "Alice"));
        discord.runAll();

        assertTrue(call.participants().isEmpty());
        assertTrue(text.messages.get(old).content().contains("isn't in the call anymore"));
        assertTrue(text.messages.get(old).buttons().isEmpty());
    }

    @Test
    void removeKicksThemOutForFiveMinutes() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);
        click(requests, id, "Let in", "Alice");

        click(requests, id, "Remove", "Bob");

        assertFalse(call.contains(steve.getUuid()));
        FakeText.Message msg = text.only();
        assertTrue(msg.content().contains("let in by Alice"), msg.content());
        assertTrue(msg.content().contains("removed by Bob"), msg.content());
        assertTrue(msg.buttons().isEmpty());
        assertNull(text.lastNickname(), "bot's own name is back");
        assertTrue(lastMessageTo(steve).contains("removed you"));

        requests.request(steve);
        assertTrue(lastMessageTo(steve).contains("Wait 300 seconds"), lastMessageTo(steve));
    }

    @Test
    void leavingIsShownAndTheNicknameFollows() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        ServerPlayer alex = player("Alex");
        long steveMsg = ask(requests, steve);
        click(requests, steveMsg, "Let in", "Alice");
        long alexMsg = ask(requests, alex);
        click(requests, alexMsg, "Let in", "Alice");
        assertEquals("MC: Steve, Alex", text.lastNickname());

        call.leave(steve.getUuid()); // /dvc hangup or quitting
        discord.runAll();

        assertTrue(text.messages.get(steveMsg).content().contains("has left"));
        assertTrue(text.messages.get(steveMsg).buttons().isEmpty());
        assertTrue(text.messages.get(alexMsg).content().contains("is in the call"));
        assertEquals("MC: Alex", text.lastNickname());
    }

    @Test
    void hangingUpEveryoneMarksEveryoneLeft() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);
        click(requests, id, "Let in", "Alice");

        call.hangUpAll();
        discord.runAll();

        assertTrue(text.only().content().contains("has left"));
        assertNull(text.lastNickname());
    }

    @Test
    void cancellingBeforeOrAfterPostingUpdatesTheMessage() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        ServerPlayer alex = player("Alex");

        // Before the request reached Discord: it's posted, then marked cancelled.
        requests.request(steve);
        assertTrue(requests.cancel(steve.getUuid(), "They cancelled it."));
        discord.runAll();
        assertTrue(text.only().content().contains("cancelled"), text.only().content());
        assertTrue(text.only().buttons().isEmpty());
        assertTrue(scheduled.isEmpty());

        // After.
        long id = ask(requests, alex);
        assertTrue(requests.cancel(alex.getUuid(), "They left the server."));
        discord.runAll();
        assertTrue(text.messages.get(id).content().contains("They left the server."));
        assertTrue(scheduled.isEmpty());

        assertFalse(requests.cancel(alex.getUuid(), "again"), "nothing left to cancel");
    }

    @Test
    void stopCancelsOpenRequests() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);

        requests.stop();
        discord.runAll();

        assertFalse(requests.isPending(steve.getUuid()));
        assertTrue(text.messages.get(id).content().contains("reloading"));
    }

    @Test
    void withApprovalOffTheyJoinStraightAwayButTheChannelStillSees() {
        CallRequests requests = requests(false);
        ServerPlayer steve = player("Steve");

        requests.request(steve);
        assertTrue(call.contains(steve.getUuid()), "no asking");
        discord.runAll();

        FakeText.Message msg = text.only();
        assertTrue(msg.content().contains("**Steve** is in the call from Minecraft."), msg.content());
        assertFalse(msg.content().contains("let in by"));
        assertEquals(List.of("Remove"), msg.buttons().stream().map(Button::label).toList());
        assertEquals("MC: Steve", text.lastNickname());

        click(requests, text.onlyId(), "Remove", "Bob");
        assertFalse(call.contains(steve.getUuid()));
        assertTrue(text.only().content().contains("removed by Bob"));
    }

    @Test
    void leavingBeforeTheJoinNoticeIsPostedStillEndsAsLeft() {
        CallRequests requests = requests(false);
        ServerPlayer steve = player("Steve");

        requests.request(steve);
        call.leave(steve.getUuid());
        discord.runAll();

        // Either nothing was posted, or what was posted says they left. Never a stale "is in the call".
        for (FakeText.Message m : text.messages.values()) {
            assertFalse(m.content().contains("is in the call"), m.content());
        }
        String nick = text.lastNickname();
        assertTrue(nick == null || nick.equals("<never set>"), "bot shows its own name: " + nick);
    }

    @Test
    void aPlayerWithoutTheModDoesntBotherDiscord() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        when(connections.get(steve.getUuid()).isInstalled()).thenReturn(false);

        requests.request(steve);
        discord.runAll();

        assertTrue(text.messages.isEmpty());
        assertFalse(requests.isPending(steve.getUuid()));
        assertTrue(lastMessageTo(steve).contains("Simple Voice Chat mod"));
    }

    @Test
    void aPlayerWhoLosesTheModBeforeBeingLetInIsToldWhy() {
        CallRequests requests = requests(true);
        ServerPlayer steve = player("Steve");
        long id = ask(requests, steve);
        when(connections.get(steve.getUuid()).isInstalled()).thenReturn(false); // disconnected their voice chat

        click(requests, id, "Let in", "Alice");

        assertFalse(call.contains(steve.getUuid()));
        assertTrue(text.only().content().contains("couldn't join"));
        assertTrue(lastMessageTo(steve).contains("Simple Voice Chat mod"));
    }

    @Test
    void missingChangeNicknameWarnsOnce() {
        CallRequests requests = requests(false);
        text.failNickname = new Exception("Missing Permissions");

        requests.request(player("Steve"));
        requests.request(player("Alex"));
        discord.runAll();

        verify(platform, times(1)).warn(contains("Change Nickname"));
        assertEquals(2, call.participants().size(), "the call still works");
    }

    @Test
    void nicknameFitsDiscordsLimit() {
        assertNull(CallRequests.nickname(List.of()));
        assertEquals("MC: Steve", CallRequests.nickname(List.of("Steve")));
        assertEquals("MC: Steve, Alex", CallRequests.nickname(List.of("Steve", "Alex")));
        String many = CallRequests.nickname(List.of("Notch_Fan_2009", "xX_Slayer_Xx", "Alexandra", "Bob"));
        assertTrue(many.length() <= CallRequests.NICKNAME_MAX_LENGTH, many);
        assertEquals("MC: Notch_Fan_2009 +3", many);
        assertEquals("MC: 2 listening", CallRequests.nickname(List.of("A".repeat(40), "B")));
    }

    @Test
    void namesCantFormatOrPing() {
        assertEquals("Steve\\_42", CallRequests.escape("Steve_42"));
        assertEquals("\\*\\*bold\\*\\*", CallRequests.escape("**bold**"));
        assertEquals("\\@everyone", CallRequests.escape("@everyone"));
        assertEquals("\\<\\@123\\>", CallRequests.escape("<@123>"));
    }
}
