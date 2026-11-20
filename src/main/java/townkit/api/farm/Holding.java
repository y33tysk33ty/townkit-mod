package townkit.api.farm;

/**
 * What the farmer holds when she harvests with {@link HarvestAction.Use}: her own tool of that kind goes in the hand.
 *
 * @since 0.8.0
 */
public enum Holding {
    /** Nothing in the hand. */
    EMPTY_HAND,
    /** Her shears. */
    SHEARS,
    /** Her axe. */
    AXE
}
