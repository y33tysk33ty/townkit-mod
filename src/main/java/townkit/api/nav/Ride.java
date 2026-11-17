package townkit.api.nav;

/**
 * One villager's trip over one link, made by {@link TransportKind#ride}. Runs on the server thread, one
 * {@link #tick} per server tick, until it answers DONE or FAILED or runs out of time.
 *
 * @since 0.6.0
 */
public interface Ride {
    /**
     * Moves the ride on by one tick.
     *
     * @since 0.6.0
     */
    RideStatus tick(RideContext ctx);

    /**
     * Called once when the ride ends for any reason: {@code completed} is true only after {@link #tick} answered
     * DONE. Release anything the ride holds here.
     *
     * @since 0.6.0
     */
    default void end(RideContext ctx, boolean completed) {
    }

    /**
     * Ticks the ride may take before TownKit ends it as failed: 400 (20 seconds) unless overridden.
     *
     * @since 0.6.0
     */
    default int timeoutTicks() {
        return 400;
    }
}
