package townkit.api.brain;

import net.minecraft.resources.ResourceLocation;

/**
 * Offers an addon activity when Towntopia's brain decides what a villager does next.
 * <ul>
 *   <li><b>Daytime</b>: asked with Towntopia's four scores (eat, fetch, work, leisure). The addon activity runs only if
 *   its score is strictly higher than the best of those four; ties go to Towntopia. Never asked at night.</li>
 *   <li><b>Evening</b>: asked only when Towntopia's evening plan picked a leisure step (tavern, visit, library, home),
 *   never for depositing, chores, supper, washing, a bard's set or bedtime. There are no comparable scores, so any
 *   positive score replaces the leisure step: don't take every evening.</li>
 * </ul>
 * The highest positive addon score wins among addons. Keep {@link #score} quick: it runs for every villager on every
 * decision (every 10 to 30 ticks). Exceptions are logged; ten in a row switch the provider off.
 *
 * @since 0.4.0
 */
public interface ChoiceProvider {
    /** How much this villager wants the activity right now; 0 or less for "not now". */
    float score(ChoiceContext ctx);

    /** The registered activity to run when this provider wins. */
    ResourceLocation activity();
}
