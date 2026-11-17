package townkit.core.brain;

import java.util.Set;
import javax.annotation.Nullable;

/** Who wins a decision: pure rules, no Towntopia types (unit-tested). */
public final class ChoiceArbiter {
    /** Evening plan decisions an addon may replace (Towntopia's EveningPlanner.Decision names). */
    static final Set<String> EVENING_LEISURE = Set.of("TAVERN", "VISIT", "LIBRARY", "HOME");

    private ChoiceArbiter() {
    }

    /** The addon that beats Towntopia's best daytime score (strictly), or -1. Never at night. */
    public static int pickDay(float bestTowntopia, float[] addonScores, boolean night) {
        if (night) return -1;
        int best = highestPositive(addonScores);
        return best >= 0 && addonScores[best] > bestTowntopia ? best : -1;
    }

    /** The addon that replaces an evening leisure step, or -1: only for leisure decisions, never in the NIGHT phase. */
    public static int pickEvening(@Nullable String decision, @Nullable String phase, float[] addonScores) {
        if (decision == null || !EVENING_LEISURE.contains(decision) || "NIGHT".equals(phase)) return -1;
        return highestPositive(addonScores);
    }

    /** Index of the highest score above 0 (the first one on a tie), or -1; NaN never wins. */
    private static int highestPositive(float[] scores) {
        int best = -1;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > 0F && (best < 0 || scores[i] > scores[best])) best = i;
        }
        return best;
    }
}
