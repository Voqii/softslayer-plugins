package dev.amsam0.voicechatdiscord;

import static dev.amsam0.voicechatdiscord.Core.platform;

public class PaperVoicechatPlugin extends VoicechatPlugin {
    @Override
    protected void ensurePlatformInitialized() {
        if (platform == null) {
            platform = new PaperPlatform();
        }
    }
}
