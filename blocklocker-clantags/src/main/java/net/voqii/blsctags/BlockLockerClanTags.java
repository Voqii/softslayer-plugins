package net.voqii.blsctags;

import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

import nl.rutgerkok.blocklocker.BlockLockerAPIv2;
import nl.rutgerkok.blocklocker.BlockLockerPlugin;

/**
 * Companion plugin that teaches BlockLocker to recognise SimpleClans clan tags
 * on protection signs, in addition to the full clan name it already supports.
 *
 * <p>It touches nothing in BlockLocker itself: on enable it registers an extra
 * {@link SimpleClansTagGroupSystem} through BlockLocker's public API. Remove the
 * jar to remove the behaviour.
 */
public final class BlockLockerClanTags extends JavaPlugin {

    @Override
    public void onEnable() {
        try {
            BlockLockerPlugin blockLocker = BlockLockerAPIv2.getPlugin();
            blockLocker.getGroupSystems().addSystem(new SimpleClansTagGroupSystem());
            getLogger().info("Registered SimpleClans clan-tag matching with BlockLocker. "
                    + "Clans can now be referenced on protection signs by tag or full name.");
        } catch (Throwable t) {
            // Never take the server down over this; just make the failure loud.
            getLogger().log(Level.SEVERE,
                    "Could not register clan-tag group system with BlockLocker. "
                            + "Clan-tag matching is NOT active. Are BlockLocker and SimpleClans both loaded?",
                    t);
        }
    }
}
