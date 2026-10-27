package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bspfsystems.yamlconfiguration.configuration.InvalidConfigurationException;
import org.bspfsystems.yamlconfiguration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.amsam0.voicechatdiscord.Constants.CONFIG_HEADER;

/**
 * Core code between Paper and Fabric.
 */
public final class Core {
    public static VoicechatServerApi api; // Initiated by VoicechatPlugin
    public static Platform platform; // Initiated upon startup by platform

    /**
     * Player-mode bots ({@code /dvc start}).
     */
    public static ArrayList<DiscordBot> bots = new ArrayList<>();
    /**
     * Call-mode bots ({@code /dvc call}), each wrapping its own {@link DiscordBot}.
     */
    public static final List<CallSession> calls = new CopyOnWriteArrayList<>(); // read from Simple Voice Chat's threads

    /**
     * What has to be shut down, in order, before a call bot's natives can be freed.
     */
    private record CallResources(CallSession call, CallRequests requests, ExecutorService worker,
                                 ScheduledExecutorService discord, DiscordBot bot) {
    }

    private static final ArrayList<CallResources> callResources = new ArrayList<>();
    /**
     * Every call's join requests, including calls added with {@link #addCall}.
     */
    private static final Map<CallSession, CallRequests> requestsByCall = new ConcurrentHashMap<>();
    public static int debugLevel = 0;

    private static native void initializeNatives();

    private static native void setDebugLevel(int debugLevel);

    private static native void shutdownNatives();

    public static void enable() {
        platform.info("Enabling " + Constants.PLUGIN_ID + " " + Constants.VERSION);

        try {
            LibraryLoader.load("voicechat_discord");
            initializeNatives();
        } catch (Throwable e) {
            platform.error("Failed to load natives: " + e);
            throw new RuntimeException(e);
        }

        loadConfig();

        platform.setOnPlayerLeaveHandler(Core::onPlayerLeave);

        platform.info("Enabled " + Constants.PLUGIN_ID + " " + Constants.VERSION);
    }

    public static void disable() {
        platform.info("Disabling " + Constants.PLUGIN_ID + " " + Constants.VERSION);

        int toShutdown = bots.size();
        platform.info("Shutting down " + toShutdown + " bot" + (toShutdown != 1 ? "s" : ""));

        clearBots();

        platform.info("Successfully shutdown " + toShutdown + " bot" + (toShutdown != 1 ? "s" : ""));

        try {
            shutdownNatives();
            platform.info("Successfully shutdown native runtime");
        } catch (Throwable e) {
            platform.error("Failed to shutdown native runtime", e);
        }

        platform.info("Disabled " + Constants.PLUGIN_ID + " " + Constants.VERSION);
    }

    @SuppressWarnings({"DataFlowIssue", "unchecked", "ResultOfMethodCallIgnored"})
    public static void loadConfig() {
        File configFile = new File(platform.getConfigPath());

        if (!configFile.getParentFile().exists())
            configFile.getParentFile().mkdirs();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException e) {
            platform.debug("IOException when loading config", e);
        } catch (InvalidConfigurationException e) {
            platform.error("Failed to load config file");
            throw new RuntimeException(e);
        }

        LinkedHashMap<String, String> defaultBot = new LinkedHashMap<>();
        defaultBot.put("token", "DISCORD_BOT_TOKEN_HERE");
        defaultBot.put("vc_id", "VOICE_CHANNEL_ID_HERE");
        config.addDefault("bots", List.of(defaultBot));

        config.addDefault("alert_ops_of_updates", true);

        config.addDefault("debug_level", 0);

        config.getOptions().setCopyDefaults(true);
        config.getOptions().setHeader(CONFIG_HEADER);
        try {
            config.save(configFile);
        } catch (IOException e) {
            platform.error("Failed to save config file: " + e);
            throw new RuntimeException(e);
        }

        bots.clear();
        calls.clear();
        callResources.clear();
        requestsByCall.clear();

        for (BotConfig bot : BotConfig.parse(config.getList("bots"), platform::error)) {
            DiscordBot discordBot = new DiscordBot(bot.token(), bot.vcId());
            if (bot.mode() == BotConfig.Mode.CALL) {
                String name = bot.name();
                ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "voicechat-discord: Call '" + name + "' connection"));
                CallSession call = new CallSession(name, bot.vcId(), discordBot, () -> api, platform, worker,
                        r -> daemon(r, "voicechat-discord: Call '" + name + "' audio"));
                ScheduledExecutorService discord = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voicechat-discord: Call '" + name + "' chat"));
                CallRequests requests = new CallRequests(call, discordBot, platform, bot.approval(), discord,
                        (task, delayMs) -> {
                            var future = discord.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                            return () -> future.cancel(false);
                        },
                        System::currentTimeMillis,
                        r -> daemon(r, "voicechat-discord: Call '" + name + "' buttons"));
                requests.start();
                requestsByCall.put(call, requests);
                calls.add(call);
                callResources.add(new CallResources(call, requests, worker, discord, discordBot));
            } else {
                bots.add(discordBot);
            }
        }

        platform.info("Using " + bots.size() + " bot" + (bots.size() != 1 ? "s" : "")
                + " for /dvc start and " + calls.size() + " for /dvc call");

        try {
            debugLevel = (int) config.get("debug_level");
            if (debugLevel > 0) platform.info("Debug level has been set to " + debugLevel);
            setDebugLevel(debugLevel);
        } catch (ClassCastException e) {
            platform.error("Please make sure the debug_level option is a valid integer");
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public static void clearBots() {
        bots.forEach(discordBot -> {
            discordBot.stop();
            discordBot.free();
        });
        bots.clear();

        for (CallResources r : callResources) {
            r.call().hangUpAll(
                    Component.red("The Discord call was ended because the plugin is reloading. Rejoin with "),
                    Component.white("/dvc call"),
                    Component.red(" in a moment."));
        }
        // hangUpAll only queues the disconnect. The natives can't be freed until the connection worker
        // has run it, the chat thread has updated its messages, and the audio and button threads have stopped
        // calling into them.
        for (CallResources r : callResources) {
            r.requests().stop();
            r.discord().shutdown();
            try {
                if (!r.discord().awaitTermination(15, TimeUnit.SECONDS)) {
                    platform.warn("Call '" + r.call().name() + "' took too long to update its Discord messages");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            r.worker().shutdown();
            try {
                if (!r.worker().awaitTermination(15, TimeUnit.SECONDS)) {
                    platform.warn("Call '" + r.call().name() + "' took too long to leave Discord");
                }
                if (!r.call().awaitAudioStopped(3000)) {
                    platform.warn("Call '" + r.call().name() + "' audio thread didn't stop");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            r.bot().free();
        }
        calls.clear();
        callResources.clear();
        requestsByCall.clear();
    }

    private static void onPlayerLeave(UUID playerUuid) {
        DiscordBot bot = getBotForPlayer(playerUuid);
        if (bot != null) {
            platform.info("Stopping bot for player " + playerUuid);
            bot.stop();
        }
        for (CallRequests r : requestsByCall.values()) r.cancel(playerUuid, "They left the server.");
        for (CallSession call : calls) call.leave(playerUuid);
    }

    public static @Nullable CallRequests getRequests(CallSession call) {
        return requestsByCall.get(call);
    }

    public static @Nullable CallRequests getPendingRequestFor(UUID playerUuid) {
        for (CallRequests r : requestsByCall.values()) {
            if (r.isPending(playerUuid)) return r;
        }
        return null;
    }

    /**
     * Adds a call that isn't in the config. Only the end-to-end tests' loopback plugin uses it; the caller owns
     * shutting it down, and a config reload forgets it.
     */
    public static void addCall(CallSession call, CallRequests requests) {
        requestsByCall.put(call, requests);
        calls.add(call);
    }

    public static @Nullable CallSession getCall(@Nullable String name) {
        if (name == null) return calls.size() == 1 ? calls.get(0) : null;
        for (CallSession call : calls) {
            if (call.name().equalsIgnoreCase(name)) return call;
        }
        return null;
    }

    public static @Nullable CallSession getCallForPlayer(UUID playerUuid) {
        for (CallSession call : calls) {
            if (call.contains(playerUuid)) return call;
        }
        return null;
    }

    public static @Nullable DiscordBot getBotForPlayer(UUID playerUuid) {
        return getBotForPlayer(playerUuid, false);
    }

    public static @Nullable DiscordBot getBotForPlayer(UUID playerUuid, boolean fallbackToAvailableBot) {
        for (DiscordBot bot : bots) {
            if (bot.player() != null && bot.player().getUuid().equals(playerUuid))
                return bot;
        }
        if (fallbackToAvailableBot)
            return getAvailableBot();
        return null;
    }

    private static @Nullable DiscordBot getAvailableBot() {
        for (DiscordBot bot : bots) {
            if (bot.player() == null)
                return bot;
        }
        return null;
    }
}
