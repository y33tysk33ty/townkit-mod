package townkit.core.brain;

import java.util.UUID;

/** Spreads per-villager work over ticks: each villager is due once per interval, on its own slot. */
public final class Stagger {
    private Stagger() {
    }

    public static boolean due(UUID npcId, long gameTime, int interval) {
        return Math.floorMod(npcId.hashCode() + gameTime, interval) == 0;
    }
}
