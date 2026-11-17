package townkit.core.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import townkit.core.brain.InterruptGate.Kind;

class InterruptGateTest {
    @Test
    void idleAndLeisureMayBeInterrupted() {
        assertTrue(InterruptGate.allows(Kind.NONE, false, false, false));
        assertTrue(InterruptGate.allows(Kind.LEISURE, false, false, false));
    }

    @Test
    void addonOnlyWhenItAllowsAndIsAnotherActivity() {
        assertTrue(InterruptGate.allows(Kind.ADDON, true, false, false));
        assertFalse(InterruptGate.allows(Kind.ADDON, false, false, false));
        assertFalse(InterruptGate.allows(Kind.ADDON, true, true, false));
    }

    @Test
    void towntopiaActivitiesNever() {
        assertFalse(InterruptGate.allows(Kind.OTHER, true, false, false));
        assertFalse(InterruptGate.allows(Kind.OTHER, false, false, false));
    }

    @Test
    void neverDuringWaterRescue() {
        for (Kind k : Kind.values()) assertFalse(InterruptGate.allows(k, true, false, true), k.name());
    }
}
