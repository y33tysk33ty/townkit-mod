package townkit.api.nav;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitTransportView;
import townkit.api.village.Village;

/**
 * Transport: links in Towntopia's route map that villagers travel by a ride instead of on foot.
 *
 * <p>How it fits together: a mod registers a {@link TransportKind} and enables it with a marker item. A player puts
 * that item in an item frame inside a village; TownKit binds the frame to the village (like Towntopia's tokens: the
 * item is bound, the frame glows) and makes it a {@link TransportStop}. The kind pairs its stops into
 * {@link TransportLink links}; TownKit adds them to the route map (and again after every rebuild), so Towntopia's own
 * route search, reachability and building access use them like steps. A region reachable only by a link is mapped
 * and counts as reachable.
 *
 * <p>Rules: raiders never use links. A stop is refused (one log line) when its frame is outside the village's route
 * map, in another dimension, bound to another village, or not over a routable tile. A removed marker or frame removes
 * its stop within 40 ticks. Links onto a tile with a never path rule are still refused. With no kind enabled nothing
 * changes: no hooks attach and villagers walk as Towntopia makes them.
 *
 * <p>Ready-made kinds ({@link #BUBBLE_ELEVATOR}) are off until a mod enables them with its own marker item; TownKit
 * adds no items.
 *
 * @since 0.6.0
 */
public final class TownKitTransport {
    /**
     * TownKit's ready-made bubble elevator kind; off until a mod enables it.
     *
     * @since 0.6.0
     */
    public static final ResourceLocation BUBBLE_ELEVATOR = ResourceLocation.fromNamespaceAndPath("townkit", "bubble_elevator");

    private TownKitTransport() {
    }

    private static TownKitTransportView view() {
        return TownKitBackend.get().transport();
    }

    /**
     * Registers {@code kind} until the subscription is removed (its stops and links go with it). {@code owner} names
     * the addon in logs. Throws IllegalArgumentException when {@code owner} is blank or another kind already has
     * {@code kind.id()}, NullPointerException on a null argument or id.
     *
     * @since 0.6.0
     */
    public static Subscription registerKind(String owner, TransportKind kind) {
        return view().registerKind(owner, kind);
    }

    /**
     * Enables the kind {@code kindId} (registered now or later) until the subscription is removed, and makes
     * {@code marker} in an item frame a stop of that kind. Several mods may enable one kind, each with its own marker.
     * An item already enabled for another kind stays that kind's marker (logged once). Frames that already hold the
     * marker when the kind is enabled are picked up when they next load (chunk load, world load) or a player touches
     * them; enable at mod setup so saved worlds are covered. Throws
     * IllegalArgumentException when {@code owner} is blank, NullPointerException on a null argument.
     *
     * @since 0.6.0
     */
    public static Subscription enable(String owner, ResourceLocation kindId, Item marker) {
        return view().enable(owner, kindId, marker);
    }

    /**
     * The links in {@code village}'s route map now (available or not); empty for a village with none or one that is
     * gone. Throws NullPointerException on a null village.
     *
     * @since 0.6.0
     */
    public static List<TransportLink> links(Village village) {
        return view().links(village);
    }
}
