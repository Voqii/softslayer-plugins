package dev.amsam0.voicechatdiscord;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What {@link CallRequests} needs from a Discord bot: messages with buttons in the voice channel's own text chat,
 * the bot's nickname, and who's in the channel. {@link DiscordBot} implements it with the Rust natives; tests use a
 * fake. All of these block on Discord, so never call them on the server thread.
 */
public interface DiscordText {
    /**
     * Logs in if needed. Blocks until logged in.
     */
    void ensureLoggedIn() throws Exception;

    /**
     * People (not bots) in the voice channel right now.
     */
    int humansInVoiceChannel();

    /**
     * Posts in the voice channel's text chat. Mentions never ping.
     *
     * @return the message ID
     */
    long postMessage(String content, List<Button> buttons) throws Exception;

    /**
     * Replaces a message's text and buttons (an empty list removes them).
     */
    void editMessage(long messageId, String content, List<Button> buttons) throws Exception;

    /**
     * @param nickname null puts the bot's own name back
     */
    void setNickname(@Nullable String nickname) throws Exception;

    /**
     * Waits for the next button click on one of our messages by someone in the voice channel (the natives reply
     * to everyone else themselves).
     *
     * @return null if nobody clicked within the timeout
     */
    @Nullable Click nextClick(long timeoutMs);

    record Button(String id, String label, Style style) {
        public enum Style {
            PRIMARY(1), SECONDARY(2), SUCCESS(3), DANGER(4);

            final int discordValue;

            Style(int discordValue) {
                this.discordValue = discordValue;
            }
        }
    }

    /**
     * @param userName their server nickname, or their Discord name
     */
    record Click(String customId, long messageId, String userName) {
    }
}
