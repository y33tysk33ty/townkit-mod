package townkit.api.brain;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import townkit.api.village.Village;
import townkit.api.village.Villager;

/**
 * What a choice or interrupt provider sees when TownKit asks it. Only valid during that call: don't keep it.
 * Server thread.
 *
 * @since 0.4.0
 */
public interface ChoiceContext {
    Villager villager();

    Village village();

    ServerLevel level();

    ChoicePhase phase();

    /** Towntopia's own scores for this daytime decision; 0 outside {@link ChoicePhase#DAY}. */
    float eatScore();

    float fetchScore();

    float workScore();

    float leisureScore();

    /** The leisure step an evening choice would replace; present only in {@link ChoicePhase#EVENING}. */
    Optional<EveningStep> eveningStep();

    /** The name of what the villager is doing now; "none" when idle. */
    String currentActivity();
}
