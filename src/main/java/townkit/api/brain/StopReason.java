package townkit.api.brain;

/**
 * Why an addon activity stopped. {@code stop} is called once per run.
 *
 * @since 0.4.0
 */
public enum StopReason {
    /** Its {@code tick} returned {@link ActivityStatus#DONE}. */
    DONE,
    /** Its {@code tick} returned {@link ActivityStatus#FAILED}, or threw. */
    FAILED,
    /** Towntopia (or another addon) switched the villager to something else. */
    INTERRUPTED,
    /**
     * The villager's entity unloaded (or its village paused). Towntopia doesn't keep activities running across that:
     * when the villager comes back, a fresh activity is made from the registered factory. Keep progress you need in
     * villager data.
     */
    UNLOADED,
    /** Towntopia forgot the villager: it died or is gone for good. */
    VILLAGER_GONE
}
