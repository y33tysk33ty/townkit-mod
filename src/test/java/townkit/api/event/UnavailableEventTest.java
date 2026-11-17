package townkit.api.event;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.core.event.EventBindings;

/** After a Towntopia update an event may be unavailable: listening must not crash the addon's setup. */
class UnavailableEventTest {
    @Test
    void listeningToAnUnavailableEventGivesADisabledSubscription() {
        TypedEvent<String> missing = new TypedEvent<>("not_bound", String.class);
        Subscription s = EventBindings.listen(missing, "test", e -> { });
        assertTrue(s.isDisabled());
        s.remove(); // harmless
    }
}
