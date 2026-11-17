package townkit.api.village;

/**
 * Where a village's raid is, from {@link Village#raidStage()}.
 *
 * @since 0.6.2
 */
public enum RaidStage {
    /** No raid under way. */
    IDLE,
    /** Raiders are coming (scouting, omen). */
    WARNING,
    /** Raiders have set up a camp. */
    CAMP,
    /** Raiders are attacking. Also the whole fight of a vanilla raid that Towntopia adopted into the village. */
    ASSAULT,
    /** Raiders are leaving. */
    RETREAT
}
