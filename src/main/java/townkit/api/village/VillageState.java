package townkit.api.village;

/**
 * What a village is doing right now, from {@link Village#state()}.
 *
 * @since 0.6.2
 */
public enum VillageState {
    /** Running normally. */
    ACTIVE,
    /** Paused because no player is near: Towntopia skips its time and catches up later. */
    DORMANT,
    /** Waking up from dormant: Towntopia is catching the village up. */
    RECONCILING,
    /** Switched off (for example its town hall token is missing), or the village is gone. */
    INACTIVE
}
