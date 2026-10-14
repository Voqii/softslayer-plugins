package net.voqii.blsctags;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import nl.rutgerkok.blocklocker.group.GroupSystem;

/**
 * A BlockLocker group system that lets a SimpleClans clan be referenced on a
 * protection sign by its (short) clan TAG as well as its full name.
 *
 * <p>BlockLocker's built-in SimpleClans support only matches the full clan
 * {@code getName()}, which frequently does not fit within a Minecraft sign
 * line's pixel-width limit. This system is registered <em>alongside</em> the
 * built-in one (BlockLocker OR-combines all registered group systems), so both
 * the full name and the tag resolve to the same clan.
 */
public final class SimpleClansTagGroupSystem extends GroupSystem {

    /** Resolves a player's clan, or null if SimpleClans is down / no clan. */
    private static Clan clanOf(UUID playerId) {
        SimpleClans simpleClans = SimpleClans.getInstance();
        if (simpleClans == null) {
            return null;
        }
        ClanPlayer clanPlayer = simpleClans.getClanManager().getClanPlayer(playerId);
        if (clanPlayer == null) {
            return null;
        }
        return clanPlayer.getClan();
    }

    /**
     * True when {@code groupName} from a sign matches the player's clan by tag
     * or by full name, case-insensitively.
     *
     * <p>Exposed by UUID (rather than only via {@link Player}) so the matching
     * behaviour can be exercised without an online player.
     */
    public boolean matchesClan(UUID playerId, String groupName) {
        Clan clan = clanOf(playerId);
        if (clan == null || groupName == null) {
            return false;
        }
        // getTag() is the color-stripped tag; getName() is the full clan name.
        return groupName.equalsIgnoreCase(clan.getTag())
                || groupName.equalsIgnoreCase(clan.getName());
    }

    /** True when the player matches the clan AND is one of its leaders. */
    public boolean isClanLeader(UUID playerId, String groupName) {
        SimpleClans simpleClans = SimpleClans.getInstance();
        if (simpleClans == null) {
            return false;
        }
        ClanPlayer clanPlayer = simpleClans.getClanManager().getClanPlayer(playerId);
        if (clanPlayer == null || !clanPlayer.isLeader()) {
            return false;
        }
        return matchesClan(playerId, groupName);
    }

    @Override
    public boolean isInGroup(Player player, String groupName) {
        return matchesClan(player.getUniqueId(), groupName);
    }

    @Override
    public boolean isGroupLeader(Player player, String groupName) {
        return isClanLeader(player.getUniqueId(), groupName);
    }

    @Override
    public boolean keepOnReload() {
        // Survive BlockLocker's own reload so tag matching is not silently
        // dropped until this plugin is reloaded.
        return true;
    }
}
