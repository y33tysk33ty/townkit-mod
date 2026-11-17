package townkit.core.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The poll runs on the server tick: nothing it throws may end the server. */
class VillageWatcherGuardTest {
    @Test
    void aFailedReadIsRetriedOnTheNextPoll() {
        VillageWatcher w = new VillageWatcher();
        assertDoesNotThrow(() -> w.guardedPoll(() -> {
            throw new IllegalStateException("one bad read");
        }));
        AtomicInteger polls = new AtomicInteger();
        w.guardedPoll(polls::incrementAndGet);
        assertEquals(1, polls.get());
        assertTrue(w.available());
    }

    @Test
    void aLinkageFailureStopsPolling() {
        VillageWatcher w = new VillageWatcher();
        assertDoesNotThrow(() -> w.guardedPoll(() -> {
            throw new NoSuchMethodError("Towntopia changed");
        }));
        AtomicInteger polls = new AtomicInteger();
        w.guardedPoll(polls::incrementAndGet);
        assertEquals(0, polls.get());
        assertFalse(w.available());
    }
}
