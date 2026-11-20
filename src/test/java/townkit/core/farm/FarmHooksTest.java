package townkit.core.farm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import townkit.core.hook.HookManifest;

/** Every hook the farm module names exists in the hook manifest generated from Towntopia's jar. */
class FarmHooksTest {
    @Test
    void everyNamedHookExists() {
        assertFalse(FarmHooks.keys().isEmpty(), "the farm module names hooks");
        for (String k : FarmHooks.keys()) {
            assertTrue(HookManifest.get().byKey.containsKey(k), k + " is a generated hook");
        }
    }
}
