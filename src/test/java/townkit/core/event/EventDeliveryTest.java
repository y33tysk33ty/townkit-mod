package townkit.core.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import townkit.api.hooks.HookListener;

/** TownKit's own mapping from a hook call to an event must never be blamed on (and switch off) an addon's handler. */
class EventDeliveryTest {
    @Test
    void aMappingThatThrowsSkipsTheEventWithoutThrowing() {
        AtomicInteger handled = new AtomicInteger();
        HookListener l = EventBindings.deliver("test_event", call -> {
            throw new IllegalStateException("mapping broke");
        }, e -> handled.incrementAndGet());
        assertDoesNotThrow(() -> l.handle(null));
        assertEquals(0, handled.get());
    }

    @Test
    void aMappingThatGivesNothingSkipsTheEvent() {
        AtomicInteger handled = new AtomicInteger();
        HookListener l = EventBindings.deliver("test_event", call -> null, e -> handled.incrementAndGet());
        l.handle(null);
        assertEquals(0, handled.get());
    }

    @Test
    void aMappedEventReachesTheHandler() {
        AtomicInteger handled = new AtomicInteger();
        HookListener l = EventBindings.deliver("test_event", call -> "event", e -> handled.incrementAndGet());
        l.handle(null);
        assertEquals(1, handled.get());
    }
}
