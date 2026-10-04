package dev.voqii.cfddr;

import net.momirealms.customfishing.api.mechanic.game.GameExpansion;
import net.momirealms.customfishing.api.mechanic.game.GameFactory;

/**
 * Registers the "ddr" game type with CustomFishing.
 * Drop the jar into plugins/CustomFishing/expansions/minigame/.
 */
public class DDRGameExpansion extends GameExpansion {

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getAuthor() {
        return "voqii";
    }

    @Override
    public String getGameType() {
        return "ddr";
    }

    @Override
    public GameFactory getGameFactory() {
        return DDRGame::new;
    }
}
