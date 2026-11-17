package townkit.core.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A hook-bound event promises delivery only if its hook exists, can fire, and whatever else it reads is there. */
class EventAvailabilityTest {
    @Test
    void deliverableWithHooksOnItsHookAndItsFields() {
        assertTrue(EventBindings.hookBoundAvailable(false, true, () -> true));
    }

    @Test
    void hooksSwitchedOffMakeItUnavailableEvenWithAFullManifest() {
        assertFalse(EventBindings.hookBoundAvailable(true, true, () -> true));
    }

    @Test
    void aMissingHookMakesItUnavailable() {
        assertFalse(EventBindings.hookBoundAvailable(false, false, () -> true));
    }

    @Test
    void aMissingFieldMakesItUnavailable() {
        assertFalse(EventBindings.hookBoundAvailable(false, true, () -> false));
    }
}
