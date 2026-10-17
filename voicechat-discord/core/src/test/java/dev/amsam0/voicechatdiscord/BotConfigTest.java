package dev.amsam0.voicechatdiscord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {
    private final List<String> errors = new ArrayList<>();

    private static Map<String, Object> bot(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }

    private List<BotConfig> parse(Object... bots) {
        return BotConfig.parse(List.of(bots), errors::add);
    }

    @Test
    void botsWithoutAModeWorkLikeUpstream() {
        List<BotConfig> bots = parse(bot("token", "abc", "vc_id", 1234567890123456789L));
        assertEquals(List.of(new BotConfig("abc", 1234567890123456789L, BotConfig.Mode.PLAYER, null)), bots);
        assertTrue(errors.isEmpty());
    }

    @Test
    void callBotsGetTheirName() {
        List<BotConfig> bots = parse(bot("token", "abc", "vc_id", 1L, "mode", "call", "name", "Friends"));
        assertEquals(BotConfig.Mode.CALL, bots.get(0).mode());
        assertEquals("friends", bots.get(0).name(), "names are lower-cased so /dvc call isn't case sensitive");
    }

    @Test
    void unnamedCallBotsAreNumbered() {
        List<BotConfig> bots = parse(
                bot("token", "a", "vc_id", 1L, "mode", "call"),
                bot("token", "b", "vc_id", 2L, "mode", "CALL"),
                bot("token", "c", "vc_id", 3L));
        assertEquals("call", bots.get(0).name());
        assertEquals("call2", bots.get(1).name());
        assertNull(bots.get(2).name(), "player bots don't need a name");
    }

    @Test
    void duplicateCallNamesAreRejected() {
        List<BotConfig> bots = parse(
                bot("token", "a", "vc_id", 1L, "mode", "call", "name", "friends"),
                bot("token", "b", "vc_id", 2L, "mode", "call", "name", "friends"));
        assertEquals(1, bots.size());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("already named"));
    }

    @Test
    void namesThatCantBeTypedAsOneWordAreRejected() {
        assertTrue(parse(bot("token", "a", "vc_id", 1L, "mode", "call", "name", "my friends")).isEmpty());
        assertEquals(1, errors.size());
    }

    @Test
    void unknownModeIsRejected() {
        assertTrue(parse(bot("token", "a", "vc_id", 1L, "mode", "proximity")).isEmpty());
        assertTrue(errors.get(0).contains("mode must be"));
    }

    @Test
    void missingOrWrongTypedPropertiesAreSkippedLikeUpstream() {
        List<BotConfig> bots = parse(
                bot("vc_id", 1L),
                bot("token", "a"),
                bot("token", "a", "vc_id", "not a number"),
                "not a map",
                bot("token", "ok", "vc_id", 5L));
        assertEquals(1, bots.size());
        assertEquals("ok", bots.get(0).token());
        assertEquals(4, errors.size());
    }

    @Test
    void smallIdsParsedAsIntegersStillWork() {
        // YAML gives an Integer for small numbers; upstream cast straight to Long and failed.
        assertEquals(42L, parse(bot("token", "a", "vc_id", 42)).get(0).vcId());
    }

    @Test
    void missingBotsListIsEmpty() {
        assertTrue(BotConfig.parse(null, errors::add).isEmpty());
    }
}
