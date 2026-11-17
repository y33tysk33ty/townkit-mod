package townkit.core.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * Who replaces which move: swaps for everyone (key null) and swaps for one villager (key = the villager's key, e.g. its
 * npcId). For a move, the villager's own swaps beat the global ones; among either, the last registered wins, and
 * removing it brings back the one before. Pure bookkeeping, thread-safe (every method is synchronized).
 *
 * @param <K> the villager key
 */
public final class MoveSwaps<K> {
    /** One swap. */
    public record Entry(long serial, String owner, ResourceLocation from, ResourceLocation to) {
        public Entry {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** Oldest first. */
    private final List<Entry> global = new ArrayList<>();
    /** Per villager, oldest first; a key with no entries is removed. */
    private final Map<K, List<Entry>> keyed = new HashMap<>();
    private long nextSerial = 1;
    private long version;

    /** Adds a swap, global when {@code key} is null; returns its serial (for {@link #remove}). */
    public synchronized long add(@Nullable K key, String owner, ResourceLocation from, ResourceLocation to) {
        Entry e = new Entry(nextSerial++, owner, from, to);
        version++;
        if (key == null) global.add(e);
        else keyed.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        return e.serial();
    }

    /** Removes the swap with this serial; false when there is none (already removed). */
    public synchronized boolean remove(long serial) {
        if (global.removeIf(e -> e.serial() == serial)) {
            version++;
            return true;
        }
        for (var it = keyed.entrySet().iterator(); it.hasNext(); ) {
            var kv = it.next();
            if (kv.getValue().removeIf(e -> e.serial() == serial)) {
                if (kv.getValue().isEmpty()) it.remove();
                version++;
                return true;
            }
        }
        return false;
    }

    /** What {@code from} plays as for the villager {@code key} (null: only the global swaps), or null when not swapped. */
    @Nullable
    public synchronized ResourceLocation lookup(@Nullable K key, ResourceLocation from) {
        if (key != null) {
            ResourceLocation own = latest(keyed.get(key), from);
            if (own != null) return own;
        }
        return latest(global, from);
    }

    @Nullable
    private static ResourceLocation latest(@Nullable List<Entry> list, ResourceLocation from) {
        if (list == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).from().equals(from)) return list.get(i).to();
        }
        return null;
    }

    /** Every swapped move of the villager {@code key} (null: global only) and what it plays as. */
    public synchronized Map<ResourceLocation, ResourceLocation> effective(@Nullable K key) {
        Map<ResourceLocation, ResourceLocation> out = new LinkedHashMap<>();
        for (Entry e : global) out.put(e.from(), e.to());
        List<Entry> own = key == null ? null : keyed.get(key);
        if (own != null) for (Entry e : own) out.put(e.from(), e.to());
        return out;
    }

    /** The villager's own swaps, oldest first. */
    public synchronized List<Entry> entries(K key) {
        List<Entry> own = keyed.get(key);
        return own == null ? List.of() : List.copyOf(own);
    }

    /** Changes with every add, remove that removed something, and {@link #clearKeyed} (a cache key for answers). */
    public synchronized long version() {
        return version;
    }

    public synchronized boolean isEmpty() {
        return global.isEmpty() && keyed.isEmpty();
    }

    public synchronized boolean hasGlobal() {
        return !global.isEmpty();
    }

    /** Whether the villager {@code key} has swaps of its own. */
    public synchronized boolean has(K key) {
        return keyed.containsKey(key);
    }

    /** Forgets every villager's own swaps (the global ones stay). */
    public synchronized void clearKeyed() {
        keyed.clear();
        version++;
    }
}
