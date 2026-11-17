package townkit.core.building;

import java.util.Set;

/**
 * The behaviour checks a {@code NEEDS:<check>} verdict refers to. The Minecraft-backed implementation lives with the
 * bridge; unit tests use fakes.
 *
 * @param <B> block type
 */
public interface BlockChecks<B> {
    /** Check names this interface defines; a verdict naming any other check never passes. */
    Set<String> KNOWN = Set.of("bed_block", "inventory", "container");

    /**
     * {@code bed_block}: the block is a bed. {@code inventory}: it holds items (container, or item-handler
     * capability). {@code container}: its block entity is a {@code Container}.
     */
    boolean passes(String check, B block);

    /**
     * Asked only after {@link #passes} said no: false when that "no" could still change (e.g. the item-handler
     * capability couldn't be probed because there is no server level, or the caller isn't on the server thread). A
     * rule failing only undecided checks is kept pending and computed again by {@link StandInTable#recheck()}.
     */
    default boolean decided(String check, B block) {
        return true;
    }
}
