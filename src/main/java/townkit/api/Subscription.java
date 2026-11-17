package townkit.api;

/**
 * A listener TownKit is holding for you. {@link #remove()} detaches it; a removed or switched-off listener costs nothing.
 *
 * @since 0.3.0
 */
public interface Subscription {
    void remove();

    /** True when TownKit switched the listener off after ten failures in a row (see the log for why). */
    boolean isDisabled();
}
