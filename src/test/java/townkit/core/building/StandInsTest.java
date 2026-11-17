package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The bridge keeps {@code instanceof} semantics for everything that is not a modded block with a rule. */
class StandInsTest {
    /** A rewritten {@code instanceof} site: Towntopia's test runs, then the bridge gets its answer. */
    private static boolean instanceOf(Object o, Class<?> c, int site) {
        return StandIns.afterInstanceOf(o, c.isInstance(o), c, site);
    }

    @Test
    void instanceOfKeepsOriginalSemantics() {
        assertFalse(instanceOf(null, Object.class, 0));
        assertTrue(instanceOf("x", CharSequence.class, -1));
        assertFalse(instanceOf("x", Integer.class, 3));
        assertFalse(instanceOf(null, Integer.class, 3));
    }

    /** 0.7.1: the bridge keeps the answer it is given (another mod's wrap of Towntopia's test may have changed it). */
    @Test
    void keepsTheAnswerItIsGiven() {
        assertTrue(StandIns.afterInstanceOf("x", true, Integer.class, 3));
        assertFalse(StandIns.afterInstanceOf("x", false, CharSequence.class, 3));
    }

    @Test
    void noRulesMeansInactive() {
        assertFalse(StandIns.active());
    }

    /**
     * With no rule the fast path is the original check plus one static boolean read; it
     * never reaches the table (so never creates it).
     */
    @Test
    void inactiveFastPathNeverTouchesTheTable() {
        assertFalse(StandIns.ACTIVE);
        assertFalse(instanceOf("x", Integer.class, 3));
        assertFalse(instanceOf(new Object(), Integer.class, 0));
        assertFalse(StandIns.tableCreated(), "the failed check reached the table");
    }
}
