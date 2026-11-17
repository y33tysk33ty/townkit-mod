package townkit.api.brain;

import net.minecraft.resources.ResourceLocation;

/**
 * Breaks in with an addon activity when something calls for it, on each villager's own slot every
 * {@code intervalTicks} (see {@link TownKitBrain#addInterrupt}). Asked only when Towntopia has no interrupt of its own
 * for the villager, and only while the villager is idle, at leisure, or running another addon activity that allows
 * routine interruptions: never during a Towntopia job, a meal, a conversation, an evening step, sleep, fleeing,
 * shelter or water rescue. The context's phase is {@link ChoicePhase#INTERRUPT} and its scores are 0.
 * Exceptions are logged; ten in a row switch the provider off.
 *
 * @since 0.4.0
 */
public interface InterruptProvider {
    /** True to break in now. Keep it quick: it runs for every villager on its slot. */
    boolean wants(ChoiceContext ctx);

    /** The registered activity to run when this provider breaks in. */
    ResourceLocation activity();
}
