package dev.amsam0.voicechatdiscord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One entry under {@code bots:} in the config.
 *
 * @param mode     {@link Mode#PLAYER} (upstream behaviour, {@code /dvc start}) or {@link Mode#CALL} ({@code /dvc call})
 * @param name     only used by call bots, to pick one with {@code /dvc call <name>}
 * @param approval call bots only: someone in the voice channel has to let each player in (default true)
 * @param udpPort  local UDP port for the Discord voice connection: {@link #UDP_PORT_SERVER} (default) = the Minecraft
 *                 server's port, 0 = random (upstream behaviour). Hosts that only let UDP replies in on assigned
 *                 ports need an assigned one, or joining Discord voice times out.
 */
public record BotConfig(String token, long vcId, Mode mode, String name, boolean approval, int udpPort) {
    public static final int UDP_PORT_SERVER = -1;

    public enum Mode {PLAYER, CALL}

    /**
     * Reads the {@code bots:} list. Bad entries are reported to {@code error} and skipped, like upstream.
     */
    public static List<BotConfig> parse(List<?> entries, Consumer<String> error) {
        List<BotConfig> bots = new ArrayList<>();
        Set<String> callNames = new HashSet<>();
        if (entries == null) return bots;

        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> bot)) {
                error.accept("Failed to load a bot, it isn't a list of properties.");
                continue;
            }
            if (bot.get("token") == null) {
                error.accept("Failed to load a bot, missing token property.");
                continue;
            }
            if (bot.get("vc_id") == null) {
                error.accept("Failed to load a bot, missing vc_id property.");
                continue;
            }
            if (!(bot.get("token") instanceof String token) || !(bot.get("vc_id") instanceof Number vcId)) {
                error.accept("Failed to load a bot. Please make sure that the token property is a string and the vc_id property is a number.");
                continue;
            }

            Object modeValue = bot.get("mode");
            Mode mode;
            if (modeValue == null || modeValue.toString().equalsIgnoreCase("player")) {
                mode = Mode.PLAYER;
            } else if (modeValue.toString().equalsIgnoreCase("call")) {
                mode = Mode.CALL;
            } else {
                error.accept("Failed to load the bot for vc_id " + vcId + ": mode must be `player` or `call`, not `" + modeValue + "`.");
                continue;
            }

            String name = null;
            boolean approval = true;
            if (mode == Mode.CALL) {
                Object approvalValue = bot.get("approval");
                if (approvalValue != null && !(approvalValue instanceof Boolean)) {
                    error.accept("Failed to load the call bot for vc_id " + vcId + ": approval must be true or false, not `" + approvalValue + "`.");
                    continue;
                }
                if (approvalValue != null) approval = (Boolean) approvalValue;

                name = bot.get("name") != null
                        ? bot.get("name").toString().toLowerCase(Locale.ROOT).trim()
                        : "call" + (callNames.isEmpty() ? "" : callNames.size() + 1);
                if (!name.matches("[a-z0-9_-]+")) {
                    error.accept("Failed to load the call bot for vc_id " + vcId + ": name `" + name + "` may only use letters, numbers, - and _.");
                    continue;
                }
                if (!callNames.add(name)) {
                    error.accept("Failed to load the call bot for vc_id " + vcId + ": another call bot is already named `" + name + "`.");
                    continue;
                }
            }

            int udpPort = UDP_PORT_SERVER;
            Object udpPortValue = bot.get("udp_port");
            if (udpPortValue != null) {
                if (!(udpPortValue instanceof Number port) || port.intValue() < 0 || port.intValue() > 65535) {
                    error.accept("Failed to load the bot for vc_id " + vcId + ": udp_port must be a port number (0 = random), not `" + udpPortValue + "`.");
                    continue;
                }
                udpPort = port.intValue();
            }

            bots.add(new BotConfig(token, vcId.longValue(), mode, name, approval, udpPort));
        }
        return bots;
    }
}
