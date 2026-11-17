package townkit.core.nav;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import townkit.api.Subscription;
import townkit.api.nav.PathRule;

/**
 * The path rules owners registered, folded into one immutable {@link Snapshot} per change.
 *
 * <p>Writers lock; readers never do. Every change builds a whole new snapshot and publishes it through a volatile
 * field (and to the listener, under the lock), so a reader never sees a half-updated table. Blocks are compared by
 * identity (as Towntopia's own block maps are).
 *
 * @param <B> block type (strings in unit tests)
 */
public final class PathRuleTable<B> {
    /** Fixed-point multipliers in 1/64. {@code never}: no walker routes over the block. */
    public record Entry(boolean never, int cost64, int speed64) {
    }

    /**
     * Everything the route search reads. The maps must not be modified after publishing. {@code raider} holds only
     * rules with includeRaiders and no never; never is read from {@code villager} for every walker.
     *
     * @param costScale 2 when any entry costs less than a plain tile (so scaled edges stay above the heuristic)
     * @param anyPace   true when any entry changes speed
     */
    public record Snapshot<B>(IdentityHashMap<B, Entry> villager, IdentityHashMap<B, Entry> raider, int costScale,
            boolean anyPace) {
    }

    private record Reg<B>(String owner, B block, PathRule rule) {
    }

    private static final Snapshot<?> EMPTY = new Snapshot<>(new IdentityHashMap<>(), new IdentityHashMap<>(), 1,
            false);

    private final Consumer<Snapshot<B>> onPublish;
    private final Object lock = new Object();
    private final List<Reg<B>> regs = new ArrayList<>();
    private volatile Snapshot<B> snapshot = empty();

    public PathRuleTable() {
        this(s -> { });
    }

    public PathRuleTable(Consumer<Snapshot<B>> onPublish) {
        this.onPublish = onPublish;
    }

    @SuppressWarnings("unchecked")
    private static <B> Snapshot<B> empty() {
        return (Snapshot<B>) EMPTY;
    }

    public Subscription add(String owner, B block, PathRule rule) {
        Reg<B> reg = new Reg<>(owner, block, rule);
        synchronized (lock) {
            regs.add(reg);
            publish();
        }
        return new Subscription() {
            @Override
            public void remove() {
                synchronized (lock) {
                    if (regs.remove(reg)) {
                        publish();
                    }
                }
            }

            @Override
            public boolean isDisabled() {
                return false;
            }
        };
    }

    public Snapshot<B> snapshot() {
        return snapshot;
    }

    /** Every rule on {@code block} folded into one (as for villagers; no raider flag); empty when it has none. */
    public Optional<PathRule> combined(B block) {
        Acc acc = new Acc();
        boolean any = false;
        synchronized (lock) {
            for (Reg<B> r : regs) {
                if (r.block == block) {
                    acc.add(r.rule);
                    any = true;
                }
            }
        }
        return any ? Optional.of(acc.toRule()) : Optional.empty();
    }

    /** Caller holds the lock. */
    private void publish() {
        IdentityHashMap<B, Acc> villager = new IdentityHashMap<>();
        IdentityHashMap<B, Acc> raider = new IdentityHashMap<>();
        for (Reg<B> r : regs) {
            villager.computeIfAbsent(r.block, b -> new Acc()).add(r.rule);
            if (r.rule.appliesToRaiders() && !r.rule.isNever()) {
                raider.computeIfAbsent(r.block, b -> new Acc()).add(r.rule);
            }
        }
        boolean[] flags = new boolean[2]; // scale, pace
        IdentityHashMap<B, Entry> v = finish(villager, flags);
        IdentityHashMap<B, Entry> rd = finish(raider, flags);
        Snapshot<B> next = regs.isEmpty() ? empty() : new Snapshot<>(v, rd, flags[0] ? 2 : 1, flags[1]);
        snapshot = next;
        onPublish.accept(next);
    }

    private static <B> IdentityHashMap<B, Entry> finish(IdentityHashMap<B, Acc> in, boolean[] flags) {
        IdentityHashMap<B, Entry> out = new IdentityHashMap<>();
        for (var e : in.entrySet()) {
            Entry entry = e.getValue().toEntry();
            flags[0] |= entry.cost64() < 64;
            flags[1] |= entry.speed64() != 64;
            out.put(e.getKey(), entry);
        }
        return out;
    }

    /** Folds the rules on one block: never wins, lowest cost, highest speed. */
    private static final class Acc {
        boolean never;
        double minCost = Double.POSITIVE_INFINITY;
        double maxSpeed = Double.NEGATIVE_INFINITY;

        void add(PathRule rule) {
            never |= rule.isNever();
            rule.cost().ifPresent(c -> minCost = Math.min(minCost, c));
            rule.speed().ifPresent(s -> maxSpeed = Math.max(maxSpeed, s));
        }

        /** The folded rule itself (every rule has a never, a cost or a speed, so one of them starts it). */
        PathRule toRule() {
            PathRule rule = never ? PathRule.never() : null;
            if (!Double.isInfinite(minCost)) {
                rule = rule == null ? PathRule.cost(minCost) : rule.withCost(minCost);
            }
            if (!Double.isInfinite(maxSpeed)) {
                rule = rule == null ? PathRule.speed(maxSpeed) : rule.withSpeed(maxSpeed);
            }
            return rule;
        }

        Entry toEntry() {
            double cost = Double.isInfinite(minCost) ? 1 : minCost;
            double speed = maxSpeed < 0 ? 1 : maxSpeed;
            int cost64 = (int) Math.max(32, Math.min(4096, Math.round(64 * cost / speed)));
            int speed64 = (int) Math.max(1, Math.min(4096, Math.round(64 * speed))); // clamp before the cast
            return new Entry(never, cost64, speed64);
        }
    }

    /** The cost of one edge: {@code edge * costScale * cost64 / 64}, rounded up. Use cost64 = 64 for no rule. */
    public static int scaleCost(int edge, int cost64, int costScale) {
        return (edge * costScale * cost64 + 63) >> 6;
    }
}
