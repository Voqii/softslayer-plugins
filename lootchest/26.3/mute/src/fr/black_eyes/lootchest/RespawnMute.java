package fr.black_eyes.lootchest;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * voqii fork: a global switch that silences every chest respawn announcement (natural, /lc respawn,
 * /lc respawnall) without touching each chest's own settings. Toggled with /lc mute and /lc unmute,
 * remembered in plugins/LootChest/mute.yml so it survives restarts. Chests still respawn normally.
 */
public final class RespawnMute {

	private static final String KEY = "respawn-announcements-muted";
	private static Boolean muted;

	private RespawnMute() {
	}

	private static File file() {
		return new File(Main.getInstance().getDataFolder(), "mute.yml");
	}

	public static synchronized boolean isMuted() {
		if (muted == null) {
			muted = YamlConfiguration.loadConfiguration(file()).getBoolean(KEY, false);
		}
		return muted;
	}

	public static synchronized void setMuted(boolean value) {
		muted = value;
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set(KEY, value);
		try {
			yaml.save(file());
		} catch (IOException e) {
			Main.getInstance().getLogger().warning("Could not save mute.yml: " + e.getMessage());
		}
	}
}
