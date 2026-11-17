package townkit.core.bubble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleState;

/**
 * Addon states ({@link BubbleState}) shown with the timing of Towntopia's {@code ThoughtBubbles.onState}: a villager is
 * checked every {@link #CHECK_INTERVAL} ticks (the caller staggers them); the winning state's icon shows when it starts,
 * again {@link #REPEAT_MIN} to {@link #REPEAT_MIN} + {@link #REPEAT_RANDOM} ticks later while it lasts and the villager
 * has no bubble, and the state's resolved icon once when it ends; nothing while no player is near. Of the active states
 * the highest priority wins (the later registered on a tie); one at priority 0 or below gives way to Towntopia's own
 * state. States may be added and removed from any thread; {@link #check} runs on one thread (the server's).
 *
 * @param <V> the villager (an entity in the game, anything in tests)
 */
public final class StateTicker<V> {
    public static final int CHECK_INTERVAL = 40;
    public static final int REPEAT_MIN = 500;
    public static final int REPEAT_RANDOM = 700;

    /** What the ticker needs from the world. */
    public interface Host<V> {
        /** Whether {@code v} is in {@code state} (may throw: counts as false). */
        boolean active(BubbleState state, V v);

        boolean playerNearby(V v);

        /** Whether Towntopia's own state for {@code v} is anything but "fine". */
        boolean towntopiaBusy(V v);

        /** Whether {@code v} shows a bubble now (Towntopia's or an addon's). */
        boolean hasBubble(V v);

        void show(V v, BubbleIcon icon);

        int nextInt(V v, int bound);
    }

    private static final class Track {
        @Nullable
        BubbleState last;
        long repeatAt;
        long checkedAt;
    }

    private final Host<V> host;
    private final Consumer<String> warn;
    private final List<BubbleState> states = new CopyOnWriteArrayList<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** Per villager: the state it shows. Only villagers with a state showing (or just checked) have one. */
    private final Map<V, Track> tracks = new HashMap<>();
    /** States removed since the last check: their villagers are forgotten on the checking thread. */
    private final List<BubbleState> forgotten = new CopyOnWriteArrayList<>();

    public StateTicker(Host<V> host, Consumer<String> warn) {
        this.host = Objects.requireNonNull(host, "host");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** Registers {@code state}; the result removes it (once). Throws IllegalArgumentException if its id is taken. */
    public synchronized Runnable add(BubbleState state) {
        for (BubbleState s : this.states) {
            if (s.id().equals(state.id())) throw new IllegalArgumentException("bubble state " + state.id() + " is already registered");
        }
        this.states.add(state);
        return () -> remove(state);
    }

    private synchronized void remove(BubbleState state) {
        if (this.states.removeIf(s -> s == state)) this.forgotten.add(state);
    }

    public boolean isEmpty() {
        return this.states.isEmpty();
    }

    /** The state {@code v} shows now; null for none. Checking thread. */
    @Nullable
    public BubbleState current(V v) {
        forget();
        Track t = this.tracks.get(v);
        return t == null ? null : t.last;
    }

    /** Drops what is kept for villagers {@code keep} says no to (e.g. unloaded ones). Checking thread. */
    public void retain(Predicate<V> keep) {
        this.tracks.keySet().removeIf(v -> !keep.test(v));
    }

    /** Forgets every villager (states stay registered). Checking thread. */
    public void clearTracks() {
        this.tracks.clear();
    }

    private void forget() {
        if (this.forgotten.isEmpty()) return;
        List<BubbleState> gone = new ArrayList<>(this.forgotten);
        this.forgotten.removeAll(gone);
        this.tracks.values().removeIf(t -> t.last != null && gone.contains(t.last));
    }

    /** One check of {@code v} at game time {@code now}. */
    public void check(V v, long now) {
        forget();
        List<BubbleState> all = List.copyOf(this.states);
        if (all.isEmpty()) return;
        List<BubbleState> active = new ArrayList<>(2);
        BubbleState winner = null;
        for (BubbleState s : all) {
            if (!test(s, v)) continue;
            active.add(s);
            if (winner == null || s.priority() >= winner.priority()) winner = s;
        }
        if (winner != null && winner.priority() <= 0 && this.host.towntopiaBusy(v)) winner = null;

        Track t = this.tracks.get(v);
        if (t == null) {
            if (winner == null) return;
            t = new Track();
            this.tracks.put(v, t);
        } else if (now - t.checkedAt > 2L * CHECK_INTERVAL) {
            t.last = null; // checks were missed (no player came near): start afresh, no resolved icon for an old state
        }
        t.checkedAt = now;

        if (!this.host.playerNearby(v)) {
            t.last = winner;
            t.repeatAt = now; // like Towntopia: no repeat timer runs, so the icon is due as soon as a player comes
            if (winner == null) this.tracks.remove(v);
            return;
        }
        if (winner != t.last) {
            BubbleState previous = t.last;
            t.last = winner;
            if (winner != null) {
                show(v, t, winner.icon(), now);
                return;
            }
            // the previous state ended (not merely outranked by Towntopia's): its resolved icon, once
            if (previous != null && !active.contains(previous) && previous.resolved().isPresent()) {
                this.host.show(v, previous.resolved().get());
            }
            this.tracks.remove(v);
            return;
        }
        if (winner != null && now >= t.repeatAt && !this.host.hasBubble(v)) show(v, t, winner.icon(), now);
    }

    private void show(V v, Track t, BubbleIcon icon, long now) {
        this.host.show(v, icon);
        t.repeatAt = now + REPEAT_MIN + this.host.nextInt(v, REPEAT_RANDOM);
    }

    private boolean test(BubbleState s, V v) {
        try {
            return this.host.active(s, v);
        } catch (Throwable e) { // an addon's check: never into the tick
            if (this.warned.add(s.id().toString())) {
                this.warn.accept("[TownKit] bubble state " + s.id() + ": its check threw " + e + "; it counts as false (logged once)");
            }
            return false;
        }
    }
}
