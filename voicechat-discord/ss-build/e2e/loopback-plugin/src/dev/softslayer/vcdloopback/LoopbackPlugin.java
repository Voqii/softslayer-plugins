package dev.softslayer.vcdloopback;

import dev.amsam0.voicechatdiscord.CallSession;
import dev.amsam0.voicechatdiscord.Core;
import dev.amsam0.voicechatdiscord.DiscordVoice;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    @Override
    public void onEnable() {
        // After voicechat-discord has loaded its config (depend: in plugin.yml).
        Core.calls.add(new CallSession("loopback", 42, new LoopbackVoice(), () -> Core.api, Core.platform,
                Executors.newSingleThreadExecutor(), Thread::new));
        getLogger().warning("TEST ONLY: added call 'loopback'");
    }
}
