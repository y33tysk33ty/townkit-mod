package townkit.api.nav;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * A link in a village's route map: villagers may travel from the route tile {@code from} to the route tile
 * {@code to} by a ride of transport kind {@code kind} (and back, when {@code bothWays}). {@code from} and {@code to}
 * are the feet positions of two of that kind's stop tiles ({@link TransportStop#tile()}). {@code cost} is the price
 * of the ride in the route search, in plain steps; the search never counts it as less than the x/z distance covered.
 *
 * <p>Throws NullPointerException on a null argument, IllegalArgumentException when {@code cost < 1} or
 * {@code from.equals(to)}.
 *
 * @since 0.6.0
 */
public record TransportLink(ResourceLocation kind, BlockPos from, BlockPos to, int cost, boolean bothWays) {
    public TransportLink {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (cost < 1) {
            throw new IllegalArgumentException("cost must be at least 1: " + cost);
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("a link needs two different tiles: " + from);
        }
        from = from.immutable();
        to = to.immutable();
    }
}
