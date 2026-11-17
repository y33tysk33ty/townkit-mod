package townkit.core.event;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import townkit.api.Subscription;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.core.TownKit;
import townkit.core.building.BuildingEventHooks;

/**
 * Events TownKit fires itself (not from a Towntopia hook), with the same isolation as hook listeners: a throwing
 * handler is logged, and ten failures in a row switch it off. An event may have a {@link Lifecycle}: whatever it needs
 * (e.g. Towntopia hooks) is attached with its first listener and detached with its last, so an event nobody listens to
 * costs nothing.
 */
public final class DirectEvents {
    private static final int MAX_FAILURES = 10;
    private static final Set<TypedEvent<?>> DIRECT = Set.of(TownKitEvents.ADDON_ACTIVITY_STARTED, TownKitEvents.ADDON_ACTIVITY_STOPPED,
        TownKitEvents.BUILDING_ADDED, TownKitEvents.BUILDING_REMOVED, TownKitEvents.BUILDING_VALIDITY_CHANGED,
        TownKitEvents.BUILDING_BLOCKS_CHANGED, TownKitEvents.VILLAGE_STATE_CHANGED, TownKitEvents.RAID_STAGE_CHANGED,
        TownKitEvents.RAID_STARTED, TownKitEvents.RAID_ENDED, TownKitEvents.SHOP_BUYING, TownKitEvents.SHOP_BOUGHT,
        TownKitEvents.BUBBLE_SHOWING, TownKitEvents.MOVE_STARTING);
    private static final Map<TypedEvent<?>, List<Listener<?>>> LISTENERS = new ConcurrentHashMap<>();
    /** Guards first-listener / last-listener transitions so attach and detach never interleave. */
    private static final Object LOCK = new Object();

    private DirectEvents() {
    }

    /** What an event attaches while it has listeners. */
    public interface Lifecycle {
        /** False when the event can't be delivered on this Towntopia (listening then gives a disabled subscription). */
        boolean available();

        /** Called with the first listener. */
        void attach();

        /** Called when the last listener goes. */
        void detach();
    }

    /** Loaded on first use, so events without a lifecycle never load the building hooks. */
    private static final class Lifecycles {
        static final Map<TypedEvent<?>, Lifecycle> ALL = BuildingEventHooks.lifecycles();
    }

    private static Lifecycle lifecycle(TypedEvent<?> event) {
        if (event == TownKitEvents.VILLAGE_STATE_CHANGED || event == TownKitEvents.RAID_STAGE_CHANGED
            || event == TownKitEvents.RAID_STARTED || event == TownKitEvents.RAID_ENDED) {
            return VillageWatcher.INSTANCE;
        }
        if (event == TownKitEvents.SHOP_BUYING || event == TownKitEvents.SHOP_BOUGHT) {
            return townkit.core.shop.ShopHooks.EVENTS;
        }
        if (event == TownKitEvents.BUBBLE_SHOWING) return townkit.core.bubble.BubbleHooks.EVENTS;
        if (event == TownKitEvents.MOVE_STARTING) return townkit.core.anim.MoveStartHooks.EVENTS;
        return event == TownKitEvents.BUILDING_ADDED || event == TownKitEvents.BUILDING_REMOVED
            || event == TownKitEvents.BUILDING_VALIDITY_CHANGED || event == TownKitEvents.BUILDING_BLOCKS_CHANGED
            ? Lifecycles.ALL.get(event) : null;
    }

    private static final class Listener<T> implements Subscription {
        final TypedEvent<T> event;
        final String owner;
        final Consumer<T> handler;
        int failures;
        volatile boolean disabled;

        Listener(TypedEvent<T> event, String owner, Consumer<T> handler) {
            this.event = event;
            this.owner = owner;
            this.handler = handler;
        }

        @Override
        public void remove() {
            synchronized (LOCK) {
                List<Listener<?>> l = LISTENERS.get(this.event);
                if (l != null && l.remove(this) && l.isEmpty()) {
                    Lifecycle lc = lifecycle(this.event);
                    if (lc != null) lc.detach();
                }
            }
        }

        @Override
        public boolean isDisabled() {
            return this.disabled;
        }
    }

    public static boolean isDirect(TypedEvent<?> event) {
        return DIRECT.contains(event);
    }

    /** Whether TownKit can deliver this direct event on the installed Towntopia. */
    public static boolean available(TypedEvent<?> event) {
        Lifecycle lc = lifecycle(event);
        return lc == null || lc.available();
    }

    public static <T> Subscription listen(TypedEvent<T> event, String owner, Consumer<T> handler) {
        Listener<T> l = new Listener<>(event, owner, handler);
        synchronized (LOCK) {
            List<Listener<?>> ls = LISTENERS.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>());
            boolean first = ls.isEmpty();
            ls.add(l);
            if (first) {
                Lifecycle lc = lifecycle(event);
                if (lc != null) lc.attach();
            }
        }
        return l;
    }

    /** Cheap check so callers only build the event when someone listens. */
    public static boolean has(TypedEvent<?> event) {
        List<Listener<?>> l = LISTENERS.get(event);
        return l != null && !l.isEmpty();
    }

    @SuppressWarnings("unchecked")
    public static <T> void fire(TypedEvent<T> event, T payload) {
        List<Listener<?>> ls = LISTENERS.get(event);
        if (ls == null) return;
        for (Listener<?> raw : ls) {
            Listener<T> l = (Listener<T>) raw;
            if (l.disabled) continue;
            try {
                l.handler.accept(payload);
                l.failures = 0;
            } catch (Throwable t) { // like Hooks: an addon's Error (a missing optional dependency, say) must not reach the tick
                l.failures++;
                if (l.failures <= 3 || l.failures == MAX_FAILURES) {
                    TownKit.LOG.error("[TownKit] handler from '{}' on event {} failed ({} in a row)", l.owner, event.name(), l.failures, t);
                }
                if (l.failures >= MAX_FAILURES) l.disabled = true;
            }
        }
    }
}
