package townkit.api.brain;

/**
 * When TownKit asks a provider: during Towntopia's daytime choice, in place of an evening leisure step, or (for
 * interrupt providers) on the villager's interrupt check.
 *
 * @since 0.4.0
 */
public enum ChoicePhase {
    DAY,
    EVENING,
    INTERRUPT
}
