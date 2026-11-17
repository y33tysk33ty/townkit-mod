package townkit.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When a ride whose walk isn't a village model's active move counts as dropped (no level needed). */
class RideEngineTest {
    private static final long NOW = 1000L;

    /** Not the model's active move, let go by its owner, and not ticked for longer than the limit: dropped. */
    @Test
    void notActiveWalkUntickedPastLimitIsDropped() {
        assertTrue(RideEngine.walkDropped(NOW, NOW - RideEngine.DROPPED_WALK_TICKS - 1, false, false));
    }

    /** A held walk, a walk ticked within the limit, and a member's active move (model sweep) are never dropped here. */
    @Test
    void heldOrRecentWalkIsNotDropped() {
        long old = NOW - RideEngine.DROPPED_WALK_TICKS - 1;
        assertFalse(RideEngine.walkDropped(NOW, old, false, true), "held");
        assertFalse(RideEngine.walkDropped(NOW, NOW - RideEngine.DROPPED_WALK_TICKS, false, false), "at the limit");
        assertFalse(RideEngine.walkDropped(NOW, old, true, false), "a member's active move");
    }

    /** The limit is longer than Towntopia's longest tolerated gap between two walk ticks (20) plus a 20-tick margin. */
    @Test
    void limitCoversTowntopiasResumeGap() {
        assertTrue(RideEngine.DROPPED_WALK_TICKS >= 20 + 20, String.valueOf(RideEngine.DROPPED_WALK_TICKS));
        assertTrue(RideEngine.DROPPED_WALK_TICKS < 100 + 400, "shorter than the held-walk sweep");
    }
}
