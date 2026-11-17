package townkit.core.brain;

/**
 * What an addon interrupt may replace: nothing running, leisure, or another addon activity that allows routine
 * interruptions. Never a Towntopia job, meal, conversation, evening step, sleep or safety activity, and never while
 * Towntopia is pulling the villager out of water.
 */
public final class InterruptGate {
    /** What the villager is doing, as far as interrupts care. */
    public enum Kind {
        NONE,
        LEISURE,
        ADDON,
        OTHER
    }

    private InterruptGate() {
    }

    public static boolean allows(Kind current, boolean currentAllowsRoutine, boolean sameAddonId, boolean escapingWater) {
        if (escapingWater) return false;
        return switch (current) {
            case NONE, LEISURE -> true;
            case ADDON -> currentAllowsRoutine && !sameAddonId;
            case OTHER -> false;
        };
    }
}
