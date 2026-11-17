package townkit.api.brain;

import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import townkit.api.village.Village;
import townkit.api.village.Villager;

/**
 * What an addon activity sees during one {@code start}, {@code tick} or {@code stop} call. Only valid inside that call:
 * don't keep it. Server thread.
 *
 * @since 0.4.0
 */
public interface ActivityContext {
    Villager villager();

    /** The villager's village, or null in the rare case Towntopia runs the activity without a village manager. */
    @Nullable
    Village village();

    ServerLevel level();

    long gameTime();

    /**
     * Walk the villager to {@code goal} on the village's route map, through Towntopia's own pathfinding. Call it every
     * tick until it stops answering {@link MoveStatus#MOVING}: the first call asks for the route, later calls report
     * progress. A goal off the route map (Towntopia only routes about 144 blocks around a village's centre) fails at
     * once; {@link #lastMoveProblem()} says why.
     */
    MoveStatus moveTo(BlockPos goal, float speed);

    /**
     * Like {@link #moveTo(BlockPos, float)}, following an entity: the route is renewed when it moves more than 2 blocks,
     * and an ARRIVED follow goes back to MOVING when the target gets more than 4 blocks away.
     */
    MoveStatus moveTo(Entity target, float speed);

    /** Why the last move failed or got stuck, e.g. "off the route map", "no route", "target gone", "stuck". */
    Optional<String> lastMoveProblem();
}
