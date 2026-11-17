package townkit.api.event;

import java.util.function.Consumer;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;

/**
 * A typed, named event. Nothing is attached until something listens, so an event no addon uses costs nothing.
 * Handlers that throw are logged; ten failures in a row switch the handler off ({@link Subscription#isDisabled()}).
 *
 * @since 0.3.0
 */
public final class TypedEvent<T> {
    private final String name;
    private final Class<T> type;

    TypedEvent(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * Start delivering this event to {@code handler}. Never throws for an event TownKit can't deliver right now: it logs
     * a warning and returns a subscription that is already {@link Subscription#isDisabled() disabled}. Check
     * {@link #available()} to tell.
     */
    public Subscription listen(String owner, Consumer<T> handler) {
        return TownKitBackend.get().listen(this, owner, handler);
    }

    public String name() {
        return this.name;
    }

    public Class<T> type() {
        return this.type;
    }

    /**
     * False when TownKit can't deliver this event: Towntopia changed the code it watches, or the event is built on hooks
     * and {@code enabled=false} switched every hook off. The polled village events keep working with hooks off.
     * {@code disabledClasses} is not checked: an event whose one class is switched off that way still reads as available
     * and never fires.
     */
    public boolean available() {
        return TownKitBackend.get().available(this);
    }

    @Override
    public String toString() {
        return "TypedEvent[" + this.name + "]";
    }
}
