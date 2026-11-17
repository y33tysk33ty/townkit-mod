package townkit.core.bubble;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import townkit.api.bubble.BubbleIcon;

/**
 * What addons did to Towntopia's own bubbles: hides and swaps of built-in icons (for every villager, or for one), and
 * extra idle thoughts. Rules may be added and removed from any thread; {@link #decide} and {@link #pickIdle} read a
 * snapshot.
 *
 * <ul>
 *   <li>A villager's own rules for an icon beat the global ones for that icon.</li>
 *   <li>Within one level a hide beats every swap; among swaps the last registered wins, and removing it brings back
 *   the one registered before.</li>
 * </ul>
 */
public final class BubbleRules {
    /** Weight of Towntopia's own idle thoughts: six icons, 1 each. */
    public static final int TOWNTOPIA_IDLE_WEIGHT = 6;

    /** What the rules make of one built-in icon: hidden, swapped for {@link #replacement}, or (neither) untouched. */
    public record Decision(boolean hidden, @Nullable BubbleIcon replacement) {
        public static final Decision NONE = new Decision(false, null);
        static final Decision HIDE = new Decision(true, null);

        public boolean untouched() {
            return !this.hidden && this.replacement == null;
        }
    }

    /** One rule; {@code villager} null for every villager, {@code replacement} null for a hide. */
    private record Rule(@Nullable UUID villager, ResourceLocation builtIn, @Nullable BubbleIcon replacement) {
    }

    private record Idle(BubbleIcon icon, int weight) {
    }

    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<Idle> idle = new CopyOnWriteArrayList<>();

    /** Adds a hide ({@code replacement} null) or a swap of {@code builtIn}; the result removes it (once). */
    public Runnable add(@Nullable UUID villager, ResourceLocation builtIn, @Nullable BubbleIcon replacement) {
        Rule r = new Rule(villager, Objects.requireNonNull(builtIn, "builtIn"), replacement);
        this.rules.add(r);
        return () -> this.rules.removeIf(x -> x == r); // by identity: two equal rules are two rules
    }

    public boolean isEmpty() {
        return this.rules.isEmpty();
    }

    /** What happens to {@code builtIn} shown on {@code villager} (null: only global rules apply). */
    public Decision decide(@Nullable UUID villager, ResourceLocation builtIn) {
        if (this.rules.isEmpty()) return Decision.NONE;
        List<Rule> snapshot = List.copyOf(this.rules);
        if (villager != null) {
            Decision own = level(snapshot, villager, builtIn);
            if (own != null) return own;
        }
        Decision global = level(snapshot, null, builtIn);
        return global == null ? Decision.NONE : global;
    }

    @Nullable
    private static Decision level(List<Rule> rules, @Nullable UUID villager, ResourceLocation builtIn) {
        BubbleIcon swap = null;
        boolean any = false;
        for (Rule r : rules) {
            if (!Objects.equals(r.villager(), villager) || !r.builtIn().equals(builtIn)) continue;
            if (r.replacement() == null) return Decision.HIDE;
            any = true;
            swap = r.replacement();
        }
        return any ? new Decision(false, swap) : null;
    }

    // --- idle thoughts ---------------------------------------------------------------------------------------------

    /** Adds {@code icon} to the idle-thought pool with {@code weight}; the result removes it (once). */
    public Runnable addIdle(BubbleIcon icon, int weight) {
        Idle i = new Idle(Objects.requireNonNull(icon, "icon"), weight);
        this.idle.add(i);
        return () -> this.idle.removeIf(x -> x == i);
    }

    public boolean hasIdle() {
        return !this.idle.isEmpty();
    }

    /**
     * Picks from the whole pool, Towntopia's six thoughts (weight {@link #TOWNTOPIA_IDLE_WEIGHT}) and the addon ones:
     * null means Towntopia's own pick stands, otherwise the addon thought picked.
     */
    @Nullable
    public BubbleIcon pickIdle(RandomSource random) {
        List<Idle> pool = List.copyOf(this.idle);
        if (pool.isEmpty()) return null;
        int total = TOWNTOPIA_IDLE_WEIGHT;
        for (Idle i : pool) total += i.weight();
        int r = random.nextInt(total) - TOWNTOPIA_IDLE_WEIGHT;
        if (r < 0) return null;
        for (Idle i : pool) {
            r -= i.weight();
            if (r < 0) return i.icon();
        }
        return null; // not reached
    }
}
