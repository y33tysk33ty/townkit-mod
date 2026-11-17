package townkit.api.brain;

/**
 * How a move asked for with {@link ActivityContext#moveTo} is going. ARRIVED, FAILED and STUCK are final for that
 * goal: asking again for the same goal answers the same until the activity asks for a different one. One exception:
 * while following an entity, ARRIVED turns back into MOVING when the target gets more than 4 blocks away.
 *
 * @since 0.4.0
 */
public enum MoveStatus {
    MOVING,
    /** Within 2 blocks of the goal (or the target entity). */
    ARRIVED,
    /** No route: off the village's route map, refused by Towntopia, the target is gone, or the villager unloaded. */
    FAILED,
    /** No 0.5 blocks of progress for 200 ticks. */
    STUCK
}
