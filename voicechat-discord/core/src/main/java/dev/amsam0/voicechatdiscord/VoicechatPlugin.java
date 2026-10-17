package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.*;

import static dev.amsam0.voicechatdiscord.Constants.PLUGIN_ID;
import static dev.amsam0.voicechatdiscord.Core.api;
import static dev.amsam0.voicechatdiscord.Core.platform;

public abstract class VoicechatPlugin implements de.maxhenkel.voicechat.api.VoicechatPlugin {
    public VoicechatPlugin() {
        ensurePlatformInitialized();
    }

    protected abstract void ensurePlatformInitialized();

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi serverApi) {
        api = (VoicechatServerApi) serverApi;
        platform.info("Successfully initialized Simple Voice Chat plugin");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(JoinGroupEvent.class, GroupManager::onJoinGroup);
        registration.registerEvent(LeaveGroupEvent.class, GroupManager::onLeaveGroup);
        registration.registerEvent(CreateGroupEvent.class, GroupManager::onGroupCreated);
        registration.registerEvent(RemoveGroupEvent.class, GroupManager::onGroupRemoved);

        // Call mode. Low priority so mute plugins run first: once one cancels a packet, Simple Voice Chat stops
        // dispatching it, so muted players never reach Discord.
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            for (CallSession call : Core.calls) call.onMicrophone(event);
        }, MICROPHONE_PRIORITY);
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> ensureVolumeCategory());
    }

    static final int MICROPHONE_PRIORITY = -100;

    private static boolean volumeCategoryRegistered = false;

    /**
     * Gives players a "Discord" slider in Simple Voice Chat's volume settings for call audio. Also called on the
     * first /dvc call, in case this plugin was loaded after Simple Voice Chat's server had started.
     */
    public static synchronized void ensureVolumeCategory() {
        if (volumeCategoryRegistered || api == null) return;
        try {
            api.registerVolumeCategory(api.volumeCategoryBuilder()
                    .setId(CallSession.VOLUME_CATEGORY)
                    .setName("Discord")
                    .setDescription("People in the Discord voice call you joined with /dvc call")
                    .build());
            volumeCategoryRegistered = true;
        } catch (Throwable e) {
            platform.error("Couldn't register the Discord volume category", e);
        }
    }
}
