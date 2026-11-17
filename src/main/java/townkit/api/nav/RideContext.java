package townkit.api.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import townkit.api.village.Villager;

/**
 * What a {@link Ride} sees and may do, on the server thread, during its ticks.
 *
 * @since 0.6.0
 */
public interface RideContext {
    /**
     * The rider. Any Towntopia NPC whose walk reaches a link rides, a traveler that no village holds too; for one of
     * those the handle answers from its entity, as a {@link Villager} of no village does.
     *
     * @since 0.6.0
     */
    Villager villager();

    /** The villager's loaded entity. @since 0.6.0 */
    PathfinderMob body();

    /** @since 0.6.0 */
    ServerLevel level();

    /**
     * The link being ridden, oriented the way the villager travels: {@code from()} is the stop tile it boarded at,
     * {@code to()} the one it gets off at (a both-ways link ridden back comes with its ends swapped).
     *
     * @since 0.6.0
     */
    TransportLink link();

    /** Ticks since the ride started: 0 on the first {@link Ride#tick}. @since 0.6.0 */
    int ticks();

    /** Asks the villager's body to move towards {@code target} at {@code speed} (Minecraft's move control). @since 0.6.0 */
    void moveTowards(Vec3 target, double speed);

    /** Puts the villager standing on the tile whose feet are at {@code feet}, at once, motion stopped. @since 0.6.0 */
    void placeAt(BlockPos feet);
}
