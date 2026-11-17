package townkit.api.bubble;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.village.Villager;

/**
 * An ongoing state a villager can be in, shown the way Towntopia shows its own states (hungry, no bed, no work...):
 * the icon pops up when the state starts, again every 500 to 1200 ticks while it lasts, and the {@link #resolved()}
 * icon (if any) when it ends; only while a player is within 20 blocks. Register it with {@link TownKitBubbles#state}.
 *
 * <p>{@link #active()} is checked on the server thread, once every 40 ticks per villager near a player; a check that
 * throws counts as false (logged once per state). Of the states active on a villager the one with the highest
 * {@link #priority()} shows (the later registered on a tie). Towntopia's own states sit at priority 0: a state above
 * 0 outranks them (Towntopia's state bubble and its idle thoughts stay away while it shows), a state at 0 or below
 * shows only while Towntopia's state is "fine". Showing an icon is all a state does; its effect on happiness stays
 * with the addon ({@link Villager#addWellbeing}).
 *
 * @since 0.7.0
 */
public final class BubbleState {
    private final ResourceLocation id;
    private final BubbleIcon icon;
    @Nullable
    private final BubbleIcon resolved;
    private final int priority;
    private final Predicate<Villager> active;

    private BubbleState(Builder b) {
        this.id = b.id;
        this.icon = b.icon;
        this.resolved = b.resolved;
        this.priority = b.priority;
        this.active = b.active;
    }

    /** A state {@code id} that shows {@code icon} while {@code active} says so; priority 0, no resolved icon. */
    public static Builder builder(ResourceLocation id, BubbleIcon icon, Predicate<Villager> active) {
        return new Builder(id, icon, active);
    }

    public ResourceLocation id() {
        return this.id;
    }

    public BubbleIcon icon() {
        return this.icon;
    }

    /** The icon shown once when the state ends; empty for none. */
    public Optional<BubbleIcon> resolved() {
        return Optional.ofNullable(this.resolved);
    }

    /** Towntopia's states are at 0; higher shows first. */
    public int priority() {
        return this.priority;
    }

    /** Whether a villager is in this state (server thread). */
    public Predicate<Villager> active() {
        return this.active;
    }

    @Override
    public String toString() {
        return "BubbleState[" + this.id + ", " + this.icon.id() + ", priority " + this.priority + "]";
    }

    /**
     * Builds a {@link BubbleState}.
     *
     * @since 0.7.0
     */
    public static final class Builder {
        private final ResourceLocation id;
        private final BubbleIcon icon;
        private final Predicate<Villager> active;
        @Nullable
        private BubbleIcon resolved;
        private int priority;

        private Builder(ResourceLocation id, BubbleIcon icon, Predicate<Villager> active) {
            this.id = Objects.requireNonNull(id, "id");
            this.icon = Objects.requireNonNull(icon, "icon");
            this.active = Objects.requireNonNull(active, "active");
        }

        /** The icon shown once when the state ends. */
        public Builder resolved(BubbleIcon icon) {
            this.resolved = Objects.requireNonNull(icon, "icon");
            return this;
        }

        /** Rank against other states; Towntopia's states sit at 0, higher shows first. Default 0. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public BubbleState build() {
            return new BubbleState(this);
        }
    }
}
