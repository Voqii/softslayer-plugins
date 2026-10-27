package dev.softslayer.vcdloopback;

import dev.amsam0.voicechatdiscord.CallRequests;
import dev.amsam0.voicechatdiscord.CallSession;
import dev.amsam0.voicechatdiscord.Core;
import dev.amsam0.voicechatdiscord.DiscordText;
import dev.amsam0.voicechatdiscord.DiscordVoice;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEST ONLY, never install on a real server. Adds a call named "loopback" whose "Discord" just plays back what
 * the call's players say, so the Simple Voice Chat half of call mode can be tested end to end without Discord.
 */
public final class LoopbackPlugin extends JavaPlugin {
    /**
     * Stands in for the Discord channel: whatever Minecraft sends comes back as the channel's audio.
     */
    static final class LoopbackVoice implements DiscordVoice {
        private final LinkedBlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();

        @Override
        public String connect() {
            return "Loopback";
        }

        @Override
        public void disconnect() {
            audio.clear();
        }

        @Override
        public byte[] nextDiscordAudio() {
            try {
                byte[] next = audio.poll(1, TimeUnit.SECONDS);
                return next == null ? new byte[0] : next;
            } catch (InterruptedException e) {
                return new byte[0];
            }
        }

        @Override
        public void sendToDiscord(int senderId, byte[] opusData) {
            audio.add(opusData);
        }

        @Override
        public void resetIdleSenders() {
        }
    }

    /**
     * Stands in for the voice channel's text chat. One person is always "in the channel", and /loopback-answer
     * clicks a button as them.
     */
    static final class LoopbackText implements DiscordText {
        final Map<Long, String> messages = new ConcurrentHashMap<>();
        final Map<Long, List<Button>> buttons = new ConcurrentHashMap<>();
        final AtomicLong nextId = new AtomicLong(1);
        final LinkedBlockingQueue<Click> clicks = new LinkedBlockingQueue<>();
        volatile String nickname = null;

        @Override
        public void ensureLoggedIn() {
        }

        @Override
        public int humansInVoiceChannel() {
            return 1;
        }

        @Override
        public long postMessage(String content, List<Button> buttons) {
            long id = nextId.getAndIncrement();
            messages.put(id, content);
            this.buttons.put(id, buttons);
            return id;
        }

        @Override
        public void editMessage(long messageId, String content, List<Button> buttons) {
            messages.put(messageId, content);
            this.buttons.put(messageId, buttons);
        }

        @Override
        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        @Override
        public Click nextClick(long timeoutMs) {
            try {
                return clicks.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        }

        /**
         * Clicks the newest message's button with this label.
         */
        boolean click(String label) {
            for (long id = nextId.get() - 1; id >= 1; id--) {
                for (Button b : buttons.getOrDefault(id, List.of())) {
                    if (b.label().equalsIgnoreCase(label)) {
                        clicks.add(new Click(b.id(), id, "LoopbackFriend"));
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final LoopbackText text = new LoopbackText();

    @Override
    public void onEnable() {
        // After voicechat-discord has loaded its config (depend: in plugin.yml).
        CallSession call = new CallSession("loopback", 42, new LoopbackVoice(), () -> Core.api, Core.platform,
                Executors.newSingleThreadExecutor(), Thread::new);
        ScheduledExecutorService discord = Executors.newSingleThreadScheduledExecutor();
        CallRequests requests = new CallRequests(call, text, Core.platform, true, discord,
                (task, delayMs) -> {
                    var future = discord.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                },
                System::currentTimeMillis, Thread::new);
        requests.start();
        Core.addCall(call, requests);
        getLogger().warning("TEST ONLY: added call 'loopback'");
    }

    /**
     * /loopback-answer <let in|deny|remove> clicks that button; /loopback-chat shows the newest message and the
     * bot's nickname, as "CHAT: ..." and "NICK: ...".
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("loopback-answer")) {
            String button = String.join(" ", args);
            sender.sendMessage(text.click(button) ? "clicked " + button : "no " + button + " button");
            return true;
        }
        long newest = text.nextId.get() - 1;
        sender.sendMessage("CHAT: " + text.messages.getOrDefault(newest, "<none>"));
        sender.sendMessage("NICK: " + text.nickname);
        return true;
    }
}
