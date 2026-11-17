package townkit.core.work.mine;

import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import townkit.api.work.OreEntry;

/**
 * What Towntopia's rewritten miner instructions call ({@code work-sites.tsv} rows naming this class). While no addon ore
 * is registered, or the miner is out of reach ({@link #active} null: hooks off, or the rows weren't rewritten), every
 * bridge hands back Towntopia's own value after one volatile read.
 *
 * <ul>
 *   <li>{@link #onIsOre}: after {@code MinerActivity#isOre(BlockState)} (so for every caller of it: the ore bonus, vein
 *   following, ore sense, the away shift): an addon ore's blocks count as ore.</li>
 *   <li>{@link #onWantedEmpty}: after the {@code isEmpty()} of the villager's wanted ores in {@code mineOrder}: Towntopia
 *   sorts the mines by depth only when its set is not empty, and a villager that wants only an addon ore has an empty
 *   set; {@link MineHooks} remembers such a villager's wishes just before.</li>
 * </ul>
 */
public final class MineBridges {
    /** The state the bridges answer from while an addon ore is registered and the rows were rewritten; null otherwise. */
    @Nullable
    static volatile MiningOverlay.View active;
    /** The addon ores the last villager asked for ({@code wantedOres}) switched on, or null; server thread. */
    @Nullable
    private static OreEntry[] wishes;
    /** The wanted-ores set that call returned (identity). */
    @Nullable
    private static Object wishesFor;

    private MineBridges() {
    }

    /** Remembers the addon ores {@code set} (a {@code wantedOres} answer) stands for; null for none. */
    static void remember(@Nullable Object set, @Nullable OreEntry[] wished) {
        wishesFor = wished == null ? null : set;
        wishes = wished;
    }

    /** The addon ores wanted along with {@code set}, or null when it isn't the set the last {@code wantedOres} returned. */
    @Nullable
    static OreEntry[] wishes(Object set) {
        return wishesFor == set ? wishes : null;
    }

    /** Rewritten: the return of {@code MinerActivity.isOre(state)}. */
    public static boolean onIsOre(boolean own, Object state) {
        if (own) return true;
        MiningOverlay.View v = active;
        return v != null && state instanceof BlockState s && v.isAddonOre(s.getBlock());
    }

    /** Rewritten: the {@code isEmpty()} of the villager's wanted Towntopia ores in {@code mineOrder}. */
    public static boolean onWantedEmpty(boolean empty) {
        return empty && (active == null || wishes == null);
    }
}
