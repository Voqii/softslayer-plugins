package net.voqii.blsctagstest;

import java.util.UUID;

import org.bukkit.plugin.java.JavaPlugin;

import net.voqii.blsctags.SimpleClansTagGroupSystem;
import nl.rutgerkok.blocklocker.BlockLockerAPIv2;
import nl.rutgerkok.blocklocker.group.GroupSystem;

/**
 * TEMPORARY test harness. Exercises SimpleClansTagGroupSystem against the live
 * SimpleClans data and reports PASS/FAIL to the console. Not for production.
 */
public final class TagTestHarness extends JavaPlugin {

    // Real clan on this server: tag "wlf", name "wolf", leader YosemiteSamson.
    private static final UUID LEADER = UUID.fromString("4beebebc-4c83-4772-8768-205f3ef68380");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private int pass;
    private int fail;

    private void check(String label, boolean actual, boolean expected) {
        if (actual == expected) {
            pass++;
            getLogger().info("PASS  " + label + "  (got " + actual + ")");
        } else {
            fail++;
            getLogger().warning("FAIL  " + label + "  expected " + expected + " but got " + actual);
        }
    }

    @Override
    public void onEnable() {
        // Give SimpleClans a moment to finish loading clan data from SQLite.
        getServer().getScheduler().runTaskLater(this, this::runTests, 100L);
    }

    private void runTests() {
        getLogger().info("================ CLAN TAG MATCHING TESTS ================");
        SimpleClansTagGroupSystem sys = new SimpleClansTagGroupSystem();

        // The whole point: the short TAG now matches.
        check("tag 'wlf' matches", sys.matchesClan(LEADER, "wlf"), true);
        check("tag uppercase 'WLF' matches", sys.matchesClan(LEADER, "WLF"), true);

        // Existing behaviour must not regress: the full NAME still matches.
        check("name 'wolf' still matches", sys.matchesClan(LEADER, "wolf"), true);
        check("name mixed case 'WoLf' matches", sys.matchesClan(LEADER, "WoLf"), true);

        // Negative cases.
        check("unrelated 'notaclan' rejected", sys.matchesClan(LEADER, "notaclan"), false);
        check("partial tag 'wl' rejected", sys.matchesClan(LEADER, "wl"), false);
        check("empty string rejected", sys.matchesClan(LEADER, ""), false);

        // Robustness: must not throw.
        check("player with no clan rejected", sys.matchesClan(NOBODY, "wlf"), false);
        check("null group name rejected", sys.matchesClan(LEADER, null), false);

        // Leader semantics (BlockLocker uses this for sign editing rights).
        check("leader recognised for tag", sys.isClanLeader(LEADER, "wlf"), true);
        check("leader recognised for name", sys.isClanLeader(LEADER, "wolf"), true);
        check("non-member not leader", sys.isClanLeader(NOBODY, "wlf"), false);

        // Confirm our system is actually registered inside BlockLocker.
        boolean registered = false;
        for (GroupSystem g : BlockLockerAPIv2.getPlugin().getGroupSystems().getReloadSurvivors()) {
            if (g instanceof SimpleClansTagGroupSystem) {
                registered = true;
            }
        }
        check("registered in BlockLocker group systems", registered, true);

        getLogger().info("================ RESULT: " + pass + " passed, " + fail + " failed ================");
    }
}
