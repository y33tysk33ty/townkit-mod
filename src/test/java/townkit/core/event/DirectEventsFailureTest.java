package townkit.core.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.api.event.TownKitEvents;

/** A handler's Error must not leave fire(): from a polled event it would end the server. */
class DirectEventsFailureTest {
    private Subscription sub;

    @AfterEach
    void removeListener() {
        if (this.sub != null) this.sub.remove();
    }

    @Test
    void anErrorFromAHandlerStaysInside() {
        this.sub = DirectEvents.listen(TownKitEvents.ADDON_ACTIVITY_STARTED, "test", e -> {
            throw new NoClassDefFoundError("an optional dependency");
        });
        assertDoesNotThrow(() -> DirectEvents.fire(TownKitEvents.ADDON_ACTIVITY_STARTED, null));
        assertFalse(this.sub.isDisabled());
    }

    @Test
    void tenErrorsInARowSwitchTheHandlerOff() {
        AtomicInteger calls = new AtomicInteger();
        this.sub = DirectEvents.listen(TownKitEvents.ADDON_ACTIVITY_STARTED, "test", e -> {
            calls.incrementAndGet();
            throw new StackOverflowError();
        });
        for (int i = 0; i < 12; i++) DirectEvents.fire(TownKitEvents.ADDON_ACTIVITY_STARTED, null);
        assertTrue(this.sub.isDisabled());
        assertEquals(10, calls.get());
    }
}
