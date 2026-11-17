package townkit.core.building;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Events held back for an owner (a village) that isn't registered yet, in the order they happened, for one server.
 * {@link #drain} delivers the ones whose owner is now registered and drops the rest. Owners and servers compare by
 * identity. The queue belongs to the server that held its events: holding for a different server (the old one stopped
 * with events held and its drain task died with it) forgets the old entries and asks for a fresh drain, so a stopped
 * server can never leave the queue stuck or keep its villages alive. Not thread-safe: server thread only.
 */
final class HeldEvents<K> {
    private record Held<K>(K owner, Runnable deliver) {
    }

    private final List<Held<K>> held = new ArrayList<>(2);
    /** The server the entries (and the pending drain) belong to. */
    @Nullable
    private Object server;
    /** A drain task is queued on {@link #server}. */
    private boolean drainPending;

    boolean isEmpty() {
        return this.held.isEmpty();
    }

    /** Whether anything is held for this owner on this server (later events for it must queue behind). */
    boolean holds(Object server, K owner) {
        if (server != this.server) return false;
        for (Held<K> h : this.held) {
            if (h.owner() == owner) return true;
        }
        return false;
    }

    /**
     * Holds an event. Returns true when the caller must queue a drain on {@code server} (none is pending for it).
     */
    boolean hold(Object server, K owner, Runnable deliver) {
        if (server != this.server) {
            this.clear();
            this.server = server;
        }
        this.held.add(new Held<>(owner, deliver));
        if (this.drainPending) return false;
        this.drainPending = true;
        return true;
    }

    /**
     * Delivers, in order, every held event whose owner is registered now; drops the others. A drain from a server
     * that no longer owns the queue does nothing. Empties the queue first, so a delivery that holds new events queues
     * them for the next drain (and {@link #hold} asks for one).
     *
     * @return how many were dropped
     */
    int drain(Object server, Predicate<K> registered) {
        if (server != this.server) return 0;
        this.drainPending = false;
        List<Held<K>> now = new ArrayList<>(this.held);
        this.held.clear();
        int dropped = 0;
        for (Held<K> h : now) {
            if (registered.test(h.owner())) h.deliver().run();
            else dropped++;
        }
        return dropped;
    }

    /** Forgets everything (a server is stopping). */
    void clear() {
        this.held.clear();
        this.server = null;
        this.drainPending = false;
    }
}
