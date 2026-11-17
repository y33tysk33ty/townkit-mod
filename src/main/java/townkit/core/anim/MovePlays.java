package townkit.core.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import townkit.api.anim.PlayMode;

/**
 * The move each key (a loaded entity, in TownKit) plays: one at a time, the latest replacing the one before, with the
 * ticks it has left. Pure bookkeeping: syncing, saving and holding still are {@link CoreAnimations}' job. Not thread
 * safe (the server thread only, in TownKit).
 *
 * @param <K> the key; compared with {@code equals} (entities: by identity)
 */
public final class MovePlays<K> {
    /** Asked whether the villager may stand still for a play that wants it. */
    @FunctionalInterface
    public interface HoldGate<K> {
        boolean hold(K key);
    }

    /**
     * One play.
     *
     * @param ticksLeft ticks until it ends by itself; -1 for a loop until stopped
     * @param seq       a number of its own (never 0), so the same move started again is a change clients see
     * @param held      the villager stands still for it
     */
    public record Play(String owner, ResourceLocation move, boolean once, int ticksLeft, int seq, boolean held) {
        public Play {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(move, "move");
        }

        Play tick() {
            return new Play(owner, move, once, ticksLeft - 1, seq, held);
        }
    }

    private final Map<K, Play> plays = new HashMap<>();
    private int nextSeq = 1;

    /**
     * Starts {@code move} on {@code key}, replacing what it played. A once play lasts {@code onceTicks}; a loop
     * {@link PlayMode#loopTicks()} (0: until stopped). The gate is asked only when the mode holds still.
     */
    public Play start(K key, String owner, ResourceLocation move, PlayMode mode, int onceTicks, HoldGate<K> gate) {
        Objects.requireNonNull(key, "key");
        boolean held = mode.holdsStill() && gate.hold(key);
        int ticks = mode.isOnce() ? Math.max(1, onceTicks) : mode.loopTicks() == 0 ? -1 : mode.loopTicks();
        Play p = new Play(owner, move, mode.isOnce(), ticks, seq(), held);
        plays.put(key, p);
        return p;
    }

    /** Starts a saved loop again on {@code key} ({@code ticksLeft} -1: until stopped), not held. */
    public Play restore(K key, String owner, ResourceLocation move, int ticksLeft) {
        Objects.requireNonNull(key, "key");
        Play p = new Play(owner, move, false, ticksLeft, seq(), false);
        plays.put(key, p);
        return p;
    }

    private int seq() {
        int s = nextSeq;
        nextSeq = nextSeq == Integer.MAX_VALUE ? 1 : nextSeq + 1;
        return s;
    }

    public Optional<Play> get(K key) {
        return Optional.ofNullable(plays.get(key));
    }

    /** Stops what {@code key} plays; false when it played nothing. */
    public boolean stop(K key) {
        return plays.remove(key) != null;
    }

    /** Forgets {@code key} (it is gone). */
    public void drop(K key) {
        plays.remove(key);
    }

    /** One tick: the keys whose play ended by itself on this tick (and is gone now). */
    public List<K> tick() {
        if (plays.isEmpty()) return List.of();
        List<K> ended = null;
        for (Iterator<Map.Entry<K, Play>> it = plays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<K, Play> e = it.next();
            Play p = e.getValue();
            if (p.ticksLeft() < 0) continue;
            if (p.ticksLeft() <= 1) {
                it.remove();
                if (ended == null) ended = new ArrayList<>();
                ended.add(e.getKey());
            } else {
                e.setValue(p.tick());
            }
        }
        return ended == null ? List.of() : ended;
    }

    public boolean isEmpty() {
        return plays.isEmpty();
    }

    /** The keys playing something now (a copy). */
    public List<K> keys() {
        return List.copyOf(plays.keySet());
    }

    public void clear() {
        plays.clear();
    }
}
