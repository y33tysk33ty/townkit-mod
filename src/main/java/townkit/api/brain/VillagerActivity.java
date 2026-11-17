package townkit.api.brain;

import net.minecraft.resources.ResourceLocation;

/**
 * Something a villager does that an addon defines: Towntopia's brain runs it like one of its own activities. Register
 * a factory with {@link TownKitBrain#registerActivity}; TownKit makes a fresh instance for every run (and again after
 * the villager unloads and comes back), so per-run fields are fine and lasting progress goes in villager data.
 * <p>
 * Exceptions are caught and logged and end the run as {@link StopReason#FAILED}; ten failures in a row switch the
 * activity off until the server restarts.
 *
 * @since 0.4.0
 */
public interface VillagerActivity {
    /** The registered id; it is also the name Towntopia shows and saves for the running activity. */
    ResourceLocation id();

    default void start(ActivityContext ctx) {
    }

    ActivityStatus tick(ActivityContext ctx);

    /** Called once per run, whatever ended it. */
    default void stop(ActivityContext ctx, StopReason reason) {
    }

    /**
     * False to keep routine things from breaking in: the activities Towntopia queues (conversations other villagers
     * start, tavern dances, the Mayor's and druid's chats) and other addons' interrupts. Towntopia's own interrupts
     * still do: fleeing, shelter, critical hunger, defender and cleric call-outs, water rescue, and bedtime.
     */
    default boolean allowsRoutineInterruption() {
        return true;
    }
}
