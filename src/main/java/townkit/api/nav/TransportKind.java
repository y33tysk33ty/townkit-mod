package townkit.api.nav;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * A way to travel between two stops of a village (a lift, a train, a teleporter...), registered with
 * {@link TownKitTransport#registerKind}. It has no stops, and so no links, until a mod enables it with a marker item
 * ({@link TownKitTransport#enable}). Every method is called on the server thread; a method that throws is logged
 * once and counts as "no links" or "not available".
 *
 * @since 0.6.0
 */
public interface TransportKind {
    /** The kind's id, also the {@code kind} of its stops and links. @since 0.6.0 */
    ResourceLocation id();

    /**
     * Pairs this kind's stops in one village into links. Called when the village's stops of this kind change.
     * {@code stops} are this kind's valid stops in the village, all in {@code level}. Stops left out simply don't link.
     * Each link must join the {@link TransportStop#tile() tiles} of two of these stops and carry this kind's id; two
     * tiles side by side (a step Towntopia already has) can't be a link. TownKit drops (and logs once) any other link.
     *
     * @since 0.6.0
     */
    List<TransportLink> links(ServerLevel level, List<TransportStop> stops);

    /**
     * Whether {@code link} may be used now (for example, a vehicle waits at its departure stop). Called every 40
     * ticks per loaded village with links of this kind; keep it cheap. A link that isn't available stays in the route
     * map but no route uses it.
     *
     * @since 0.6.0
     */
    boolean available(ServerLevel level, TransportLink link);

    /** A ride over {@code ctx.link()} for {@code ctx.villager()}. @since 0.6.0 */
    Ride ride(RideContext ctx);
}
