package townkit.api.work;

/**
 * Visuals for an addon cook station: TownKit calls {@link #started} when Towntopia's cook starts a batch at one of the
 * station's blocks and {@link #stopped} once when that batch ends (it finished, the cook was interrupted, the block is
 * gone, the cook left its level (unloaded or died), or the server stops). Use it for show, e.g. set a block state
 * while the cook works; the cook never loads or runs the block.
 *
 * <p>Called on the server thread, usually inside the cook's tick: keep it quick. An exception is logged (once per owner) and
 * the cook goes on; ten failures in a row switch the handler off (the station's {@code Subscription#isDisabled()}).
 *
 * @since 0.7.1
 */
public interface StationHandler {
    /** A batch started at {@code ctx.pos()}. */
    default void started(StationContext ctx) {
    }

    /** That batch ended (once per {@link #started}). The block at {@code ctx.pos()} may be gone. */
    default void stopped(StationContext ctx) {
    }
}
