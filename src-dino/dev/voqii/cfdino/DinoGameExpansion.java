package dev.voqii.cfdino;

import net.momirealms.customfishing.api.mechanic.game.GameExpansion;
import net.momirealms.customfishing.api.mechanic.game.GameFactory;

/**
 * Registers the "dino" game type (Chrome offline T-Rex style runner) with CustomFishing.
 * Drop the jar into plugins/CustomFishing/expansions/minigame/.
 */
public class DinoGameExpansion extends GameExpansion {

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
        return "dino";
    }

    @Override
    public GameFactory getGameFactory() {
        return DinoGame::new;
    }
}
