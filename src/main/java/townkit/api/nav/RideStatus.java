package townkit.api.nav;

/**
 * What a {@link Ride} answers each tick.
 *
 * @since 0.6.0
 */
public enum RideStatus {
    /** Still riding: call again next tick. */
    RUNNING,
    /** Arrived: the villager is at the link's far tile and walks on from there. */
    DONE,
    /** The ride can't go on: TownKit ends it safely (the villager is put on a safe stop) and avoids the link a while. */
    FAILED
}
