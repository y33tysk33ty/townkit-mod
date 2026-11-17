package townkit.core.skin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import townkit.api.skin.SkinRef;

/**
 * Per-villager skin overrides, a stack per key (an entity on the server): the last one put wins, and removing it
 * brings back the one under it. Each owner has at most one saved and one temporary override per key: putting again
 * replaces its earlier one of the same kind and goes on top. Not thread-safe (the server thread owns it).
 *
 * @param <K> the key; compared with {@code equals} (an entity is compared by identity)
 */
public final class SkinOverrides<K> {
    /** One override: who put it, whether it is saved with the villager, the skin, and a serial that names this put. */
    public record Entry(String owner, boolean saved, SkinRef skin, long serial) {
    }

    /** A saved override, as kept in TownKit's villager data (oldest first). */
    public record Saved(String owner, SkinRef skin) {
        public static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("owner").forGetter(Saved::owner),
            ResourceLocation.CODEC.xmap(SkinRef::of, SkinRef::id).fieldOf("skin").forGetter(Saved::skin)).apply(i, Saved::new));
        public static final Codec<List<Saved>> LIST_CODEC = CODEC.listOf();

        public Saved {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(skin, "skin");
        }
    }

    /** Oldest first; only keys with at least one entry. */
    private final Map<K, List<Entry>> stacks = new HashMap<>();
    private long next = 1;

    /** Puts {@code owner}'s override on top of {@code key}'s stack, replacing its earlier one of the same kind. Returns its serial. */
    public long put(K key, String owner, boolean saved, SkinRef skin) {
        List<Entry> stack = this.stacks.computeIfAbsent(key, k -> new ArrayList<>(2));
        stack.removeIf(e -> e.saved() == saved && e.owner().equals(owner));
        long serial = this.next++;
        stack.add(new Entry(owner, saved, skin, serial));
        return serial;
    }

    /** Removes the override put with {@code serial}; false when it is gone already (removed, replaced or dropped). */
    public boolean remove(K key, long serial) {
        return removeIf(key, e -> e.serial() == serial);
    }

    /** Removes {@code owner}'s saved or temporary override; false when it has none. */
    public boolean removeOwner(K key, String owner, boolean saved) {
        return removeIf(key, e -> e.saved() == saved && e.owner().equals(owner));
    }

    private boolean removeIf(K key, java.util.function.Predicate<Entry> which) {
        List<Entry> stack = this.stacks.get(key);
        if (stack == null || !stack.removeIf(which)) return false;
        if (stack.isEmpty()) this.stacks.remove(key);
        return true;
    }

    /**
     * Puts saved overrides (oldest first) under whatever {@code key} has now: they were set before anything put since
     * the entity loaded. An owner that already has a saved override here keeps that one.
     */
    public void loadSaved(K key, List<Saved> saved) {
        if (saved.isEmpty()) return;
        List<Entry> stack = this.stacks.computeIfAbsent(key, k -> new ArrayList<>(saved.size()));
        List<Entry> under = new ArrayList<>(saved.size());
        for (Saved s : saved) {
            boolean has = stack.stream().anyMatch(e -> e.saved() && e.owner().equals(s.owner()))
                || under.stream().anyMatch(e -> e.owner().equals(s.owner()));
            if (!has) under.add(new Entry(s.owner(), true, s.skin(), this.next++));
        }
        stack.addAll(0, under);
        if (stack.isEmpty()) this.stacks.remove(key);
    }

    /** The override that wins for {@code key}: the last one put. */
    public Optional<SkinRef> top(K key) {
        List<Entry> stack = this.stacks.get(key);
        return stack == null ? Optional.empty() : Optional.of(stack.get(stack.size() - 1).skin());
    }

    /** {@code key}'s overrides, oldest first. */
    public List<Entry> entries(K key) {
        List<Entry> stack = this.stacks.get(key);
        return stack == null ? List.of() : List.copyOf(stack);
    }

    /** Forgets everything for {@code key}. */
    public void drop(K key) {
        this.stacks.remove(key);
    }

    public void clear() {
        this.stacks.clear();
    }

    public boolean isEmpty() {
        return this.stacks.isEmpty();
    }
}
