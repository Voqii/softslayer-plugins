pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

rootProject.name = "voicechat-discord"
// SoftSlayer fork: only the Paper plugin is built. Upstream also includes "fabric", "neoforge",
// "fabric:26.2" and "neoforge:26.2" (see ss-build/settings.upstream.kts).
include(
    "core",
    "paper",
)
