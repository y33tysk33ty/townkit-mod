package townkit.api.brain;

/**
 * What an addon activity's {@code tick} reports: keep going, finished, or gave up.
 *
 * @since 0.4.0
 */
public enum ActivityStatus {
    RUNNING,
    DONE,
    FAILED
}
