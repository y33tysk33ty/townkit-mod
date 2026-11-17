package townkit.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A settings save keeps what the saving player's screen couldn't show. */
class SettingsMergeTest {
    private static Map<String, Boolean> map(Object... pairs) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Boolean) pairs[i + 1]);
        return m;
    }

    @Test
    void clientWithoutTownKitKeepsAddonEntries() {
        Map<String, Boolean> saved = map("minecraft:bread", true, "minecraft:cake", true, "mymod:stew", false);
        // a client that only shows Towntopia's dishes: whatever it says about an addon id is not its to say
        Map<String, Boolean> fromClient = map("minecraft:bread", false, "minecraft:cake", true, "mymod:stew", true);
        Map<String, Boolean> out = SettingsMerge.merge(saved, fromClient, Set.of("minecraft:bread", "minecraft:cake"));
        assertEquals(map("minecraft:bread", false, "minecraft:cake", true, "mymod:stew", false), out);
        // an addon id the client never mentions keeps its saved value too, and one never saved stays unsaved
        assertEquals(map("minecraft:bread", true, "mymod:stew", false),
            SettingsMerge.merge(map("minecraft:bread", false, "mymod:stew", false), map("minecraft:bread", true, "mymod:pie", true),
                Set.of("minecraft:bread")));
    }

    @Test
    void clientWithTownKitCanSwitchAddonEntries() {
        Map<String, Boolean> saved = map("minecraft:bread", true, "mymod:stew", false);
        Map<String, Boolean> fromClient = map("minecraft:bread", false, "mymod:stew", true);
        Map<String, Boolean> out = SettingsMerge.merge(saved, fromClient, Set.of("minecraft:bread", "mymod:stew"));
        assertEquals(map("minecraft:bread", false, "mymod:stew", true), out);
        // a new id the client knows is saved
        assertEquals(map("minecraft:bread", true, "mymod:pie", false),
            SettingsMerge.merge(map("minecraft:bread", true), map("mymod:pie", false), Set.of("minecraft:bread", "mymod:pie")));
    }
}
