package fr.black_eyes.holograms.api.utils.reflect;

import fr.black_eyes.holograms.shared.reflect.ReflectUtil;
import org.bukkit.Bukkit;

public enum Version {
    v1_8_R1(8, Platform.ALL, "1.8"),
    v1_8_R2(8, Platform.ALL, "1.8.3"),
    v1_8_R3(8, Platform.ALL, "1.8.4", "1.8.5", "1.8.6", "1.8.7", "1.8.8"),
    v1_9_R1(9, Platform.ALL, "1.9", "1.9.2"),
    v1_9_R2(9, Platform.ALL, "1.9.4"),
    v1_10_R1(10, Platform.ALL, "1.10", "1.10.2"),
    v1_11_R1(11, Platform.ALL, "1.11", "1.11.1", "1.11.2"),
    v1_12_R1(12, Platform.ALL, "1.12", "1.12.1", "1.12.2"),
    v1_13_R1(13, Platform.ALL, "1.13"),
    v1_13_R2(13, Platform.ALL, "1.13.1", "1.13.2"),
    v1_14_R1(14, Platform.ALL, "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4"),
    v1_15_R1(15, Platform.ALL, "1.15", "1.15.1", "1.15.2"),
    v1_16_R1(16, Platform.ALL, "1.16", "1.16.1"),
    v1_16_R2(16, Platform.ALL, "1.16.2", "1.16.3"),
    v1_16_R3(16, Platform.ALL, "1.16.4", "1.16.5"),
    v1_17_R1(17, Platform.ALL, "1.17", "1.17.1"),
    v1_18_R1(18, Platform.ALL, "1.18", "1.18.1"),
    v1_18_R2(18, Platform.ALL, "1.18.2"),
    v1_19_R1(19, Platform.ALL, "1.19", "1.19.1", "1.19.2"),
    v1_19_R2(19, Platform.ALL, "1.19.3"),
    v1_19_R3(19, Platform.ALL, "1.19.4"),
    v1_20_R1(20, Platform.ALL, "1.20", "1.20.1"),
    v1_20_R2(20, Platform.ALL, "1.20.2"),
    v1_20_R3(20, Platform.ALL, "1.20.3", "1.20.4"),
    v1_20_R4(20, Platform.ALL, "1.20.5", "1.20.6"),
    v1_21_R1(21, Platform.ALL, "1.21", "1.21.1"),
    v1_21_R2(21, Platform.ALL, "1.21.2", "1.21.3"),
    v1_21_R3(21, Platform.ALL, "1.21.4"),
    v1_21_R4(21, Platform.ALL, "1.21.5"),
    v1_21_R5(21, Platform.ALL, "1.21.6", "1.21.7", "1.21.8"),
    v1_21_R6(21, Platform.SPIGOT, "1.21.9", "1.21.10"),
    paper_v1_21_R6(21, Platform.PAPER, "1.21.9", "1.21.10"),
    v1_21_R7(21, Platform.SPIGOT, "1.21.11"),
    paper_v1_21_R7(21, Platform.PAPER, "1.21.11"),
    v26_1(26, Platform.SPIGOT, "26.1", "26.1.1", "26.1.2"),
    paper_v26_1(26, Platform.PAPER, "26.1", "26.1.1", "26.1.2"),
    v26_2(26, Platform.SPIGOT, "26.2"),
    paper_v26_2(26, Platform.PAPER, "26.2"),
    v26_3(26, Platform.SPIGOT, "26.3"),
    paper_v26_3(26, Platform.PAPER, "26.3");

    public static final Version CURRENT;
    public static final Platform CURRENT_SERVER_PLATFORM;
    public static final String CURRENT_MINECRAFT_VERSION;
    private final int minor;
    private final Platform platform;
    private final String[] minecraftVersions;

    public static Version getCurrentVersion() {
        return Version.fromMinecraftVersion(CURRENT_MINECRAFT_VERSION, CURRENT_SERVER_PLATFORM);
    }

    private static Platform getServerPlatform() {
        return ReflectUtil.isPaper ? Platform.PAPER : Platform.SPIGOT;
    }

    private static String getCurrentMinecraftVersion() {
        String bukkitVersion = Bukkit.getServer().getBukkitVersion();
        String mcVersion = bukkitVersion.split("-", 2)[0];
        return mcVersion.replaceFirst("^(\\d+(?:\\.\\d+)*).*$", "$1");
    }

    public static Version fromMinecraftVersion(String minecraftVersion, Platform currentPlatform) {
        for (Version version : Version.values()) {
            if (!version.platform.equals((Object)Platform.ALL) && !version.platform.equals((Object)currentPlatform)) continue;
            for (String candidateMinecraftVersion : version.getMinecraftVersions()) {
                if (!candidateMinecraftVersion.equals(minecraftVersion)) continue;
                return version;
            }
        }
        return null;
    }

    public static boolean is(int minor) {
        return CURRENT.getMinor() == minor;
    }

    public static boolean is(Version version) {
        if (version == null) {
            throw new NullPointerException("version is marked non-null but is null");
        }
        return CURRENT == version;
    }

    public static boolean after(int minor) {
        return CURRENT.getMinor() > minor;
    }

    private Version(int minor, Platform platform, String ... minecraftVersions) {
        this.minor = minor;
        this.platform = platform;
        this.minecraftVersions = minecraftVersions;
    }

    public int getMinor() {
        return this.minor;
    }

    public Platform getPlatform() {
        return this.platform;
    }

    public String[] getMinecraftVersions() {
        return this.minecraftVersions;
    }

    static {
        CURRENT_SERVER_PLATFORM = Version.getServerPlatform();
        CURRENT_MINECRAFT_VERSION = Version.getCurrentMinecraftVersion();
        CURRENT = Version.getCurrentVersion();
    }
}
