package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Events held for a village that isn't registered: delivered in order once it is, dropped if it never is; tied to the
 * server that held them.
 */
class HeldEventsTest {
    private final Object server = new Object();
    private final String founded = new String("founded");
    private final String failed = new String("failed");

    @Test
    void deliversInOrderForARegisteredOwner() {
        HeldEvents<String> h = new HeldEvents<>();
        List<String> out = new ArrayList<>();
        assertTrue(h.hold(this.server, this.founded, () -> out.add("town hall added")), "first hold asks for a drain");
        assertFalse(h.hold(this.server, this.founded, () -> out.add("house added")), "a drain is already pending");
        assertTrue(h.holds(this.server, this.founded));
        assertEquals(0, h.drain(this.server, o -> o == this.founded));
        assertEquals(List.of("town hall added", "house added"), out);
        assertTrue(h.isEmpty());
    }

    @Test
    void dropsForAnOwnerThatNeverRegistered() {
        HeldEvents<String> h = new HeldEvents<>();
        List<String> out = new ArrayList<>();
        h.hold(this.server, this.failed, () -> out.add("ghost town hall"));
        h.hold(this.server, this.founded, () -> out.add("real"));
        assertEquals(1, h.drain(this.server, o -> o == this.founded));
        assertEquals(List.of("real"), out);
        assertFalse(h.holds(this.server, this.failed));
        assertTrue(h.isEmpty());
    }

    @Test
    void ownersCompareByIdentity() {
        HeldEvents<String> h = new HeldEvents<>();
        h.hold(this.server, this.founded, () -> { });
        assertFalse(h.holds(this.server, new String("founded")));
    }

    @Test
    void aDeliveryThatHoldsAgainAsksForAnotherDrain() {
        HeldEvents<String> h = new HeldEvents<>();
        List<String> out = new ArrayList<>();
        boolean[] again = new boolean[1];
        h.hold(this.server, this.founded, () -> again[0] = h.hold(this.server, this.founded, () -> out.add("second")));
        h.drain(this.server, o -> true);
        assertTrue(again[0], "held during a drain: a new drain must be scheduled");
        assertTrue(out.isEmpty());
        h.drain(this.server, o -> true);
        assertEquals(List.of("second"), out);
    }

    /** A server stopped with events held (its drain task died with it): the next server starts clean and drains. */
    @Test
    void serverChangedWithEventsHeld() {
        HeldEvents<String> h = new HeldEvents<>();
        Object oldServer = new Object();
        Object newServer = new Object();
        List<String> out = new ArrayList<>();
        assertTrue(h.hold(oldServer, this.failed, () -> out.add("old world")));
        // oldServer stops here: its drain never runs
        assertTrue(h.hold(newServer, this.founded, () -> out.add("new town hall")), "a new server always reschedules");
        assertFalse(h.holds(newServer, this.failed), "the old server's entries are gone");
        assertEquals(0, h.drain(oldServer, o -> true), "a stale drain from the old server does nothing");
        assertTrue(out.isEmpty());
        assertEquals(0, h.drain(newServer, o -> o == this.founded));
        assertEquals(List.of("new town hall"), out);
        assertTrue(h.isEmpty());
        // dropping still works on the new server
        assertTrue(h.hold(newServer, this.failed, () -> out.add("ghost")));
        assertEquals(1, h.drain(newServer, o -> o == this.founded));
        assertEquals(List.of("new town hall"), out);
    }

    @Test
    void clearForgetsEverythingAndTheNextHoldReschedules() {
        HeldEvents<String> h = new HeldEvents<>();
        List<String> out = new ArrayList<>();
        assertTrue(h.hold(this.server, this.founded, () -> out.add("lost")));
        h.clear();
        assertTrue(h.isEmpty());
        assertTrue(h.hold(this.server, this.founded, () -> out.add("kept")), "after clear a drain is scheduled again");
        h.drain(this.server, o -> true);
        assertEquals(List.of("kept"), out);
    }
}
