package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.core.hook.HookManifest;

/** Every hook the building events attach exists in the hook manifest generated from Towntopia's jar. */
class BuildingEventHooksTest {
    private static final Set<TypedEvent<?>> EVENTS = Set.of(TownKitEvents.BUILDING_ADDED, TownKitEvents.BUILDING_REMOVED,
            TownKitEvents.BUILDING_VALIDITY_CHANGED, TownKitEvents.BUILDING_BLOCKS_CHANGED);

    @Test
    void everyHookKeyIsInTheManifest() {
        Set<String> all = new HashSet<>();
        for (TypedEvent<?> e : EVENTS) {
            Set<String> keys = BuildingEventHooks.keys(e);
            assertFalse(keys.isEmpty(), e.name() + " has hooks");
            all.addAll(keys);
        }
        for (String k : all) {
            assertTrue(HookManifest.get().byKey.containsKey(k), k + " is a generated hook");
        }
    }

    @Test
    void validityCoversEveryWriterOfIsValid() {
        Set<String> keys = BuildingEventHooks.keys(TownKitEvents.BUILDING_VALIDITY_CHANGED);
        assertTrue(keys.containsAll(Set.of(BuildingEventHooks.VALIDATE, BuildingEventHooks.REBUILD,
                BuildingEventHooks.SET_ACCESS)), keys.toString());
    }

    @Test
    void otherEventsHaveNoBuildingHooks() {
        assertTrue(BuildingEventHooks.keys(TownKitEvents.DAY_ROLLOVER).isEmpty());
    }
}
