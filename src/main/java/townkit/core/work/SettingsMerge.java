package townkit.core.work;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A settings save from a player's screen: the player can only change the entries their screen shows. A player whose
 * client lacks TownKit sees Towntopia's lists only, so they can't switch (or wipe) an addon's entry they never saw.
 */
public final class SettingsMerge {
    private SettingsMerge() {
    }

    /**
     * The saved switches after a save: {@code saved}, with each of {@code fromClient}'s values for an id in
     * {@code clientKnows} (the ids the saving player's screen showed). Ids the client didn't know keep their saved value
     * (or stay unsaved). Keeps {@code saved}'s order, new ids after it.
     */
    public static Map<String, Boolean> merge(Map<String, Boolean> saved, Map<String, Boolean> fromClient, Set<String> clientKnows) {
        Map<String, Boolean> out = new LinkedHashMap<>(saved);
        for (Map.Entry<String, Boolean> e : fromClient.entrySet()) {
            if (clientKnows.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }
}
