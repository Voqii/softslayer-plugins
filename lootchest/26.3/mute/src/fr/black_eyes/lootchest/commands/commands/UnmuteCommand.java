package fr.black_eyes.lootchest.commands.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import fr.black_eyes.lootchest.RespawnMute;
import fr.black_eyes.lootchest.commands.SubCommand;

/** voqii fork: /lc unmute — turn chest respawn announcements back on. */
public class UnmuteCommand extends SubCommand {

	public UnmuteCommand() {
		super("unmute");
	}

	@Override
	protected void onCommand(CommandSender sender, String[] args) {
		boolean wasMuted = RespawnMute.isMuted();
		RespawnMute.setMuted(false);
		sender.sendMessage(ChatColor.translateAlternateColorCodes('&', wasMuted
				? "&6Chest respawn announcements are &aon &6again."
				: "&6Chest respawn announcements weren't muted."));
	}
}
