package fr.black_eyes.lootchest.commands.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import fr.black_eyes.lootchest.RespawnMute;
import fr.black_eyes.lootchest.commands.SubCommand;

/** voqii fork: /lc mute — silence all chest respawn announcements until /lc unmute. */
public class MuteCommand extends SubCommand {

	public MuteCommand() {
		super("mute");
	}

	@Override
	protected void onCommand(CommandSender sender, String[] args) {
		boolean already = RespawnMute.isMuted();
		RespawnMute.setMuted(true);
		sender.sendMessage(ChatColor.translateAlternateColorCodes('&', already
				? "&6Chest respawn announcements were already &cmuted&6."
				: "&6Chest respawn announcements are now &cmuted&6. Chests still respawn. Use &b/lc unmute &6to turn them back on."));
	}
}
