package dev.amsam0.voicechatdiscord;

/**
 * What a {@link CallSession} needs from a Discord bot. {@link DiscordBot} implements it with the Rust natives;
 * tests use a fake.
 */
public interface DiscordVoice {
    /**
     * Logs in if needed and joins the voice channel. Blocks until joined.
     *
     * @return the voice channel's name
     */
    String connect() throws Exception;

    /**
     * Leaves the voice channel. Stays logged in, so the next {@link #connect()} is quick.
     */
    void disconnect();

    /**
     * Blocks for the next 20 ms of Discord audio: one Opus packet with everyone who is talking mixed together.
     *
     * @return the packet, or an empty array if nobody has talked for about a second
     */
    byte[] nextDiscordAudio();

    /**
     * Queues 20 ms of Opus audio from one Minecraft player to be played in the voice channel.
     * Audio from different senders is mixed.
     */
    void sendToDiscord(int senderId, byte[] opusData);

    /**
     * Resets the Opus decoders of senders who've been quiet for a while, so the next thing they say doesn't
     * start with a glitch. Call it every half second or so.
     */
    void resetIdleSenders();
}
