package townkit.api.work;

import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitWorkView;

/**
 * The ores Towntopia's miner knows beside its own seven (coal, copper, iron, lapis, redstone, gold and diamond, ids
 * {@code towntopia:coal} ... {@code towntopia:diamond}): add an {@link OreEntry} with its blocks, the depths the mine
 * choice steers by, and, if the addon wants it, a share of the miner's lucky rolls and of the druid's regrown rock.
 * Towntopia's seven stay Towntopia's: TownKit doesn't change or remove them, and an entry that names one is skipped
 * (logged).
 *
 * <p>What an addon ore does, and where it shows:
 * <ul>
 *   <li>Modded ore drops need nothing: the miner digs any ore block and gets the block's own loot. TownKit makes an
 *   addon ore's blocks count as ore for the miner's ore bonus, vein following and ore sense.</li>
 *   <li>The miner's "wanted ores" setting gets a row for the ore, switched off like Towntopia's (players see it when
 *   their client has TownKit). A villager that switched the row on goes to the mine whose depth is closest to the ore's
 *   {@link OreEntry#depths()}.</li>
 *   <li>The mine room's map marks addon ore blocks like any ore (a marker, as Towntopia's ores get). There is no label
 *   for an addon ore in the mine room or the town statistics.</li>
 *   <li>Lucky rolls ({@link OreEntry#luckyRolls()}) are opt-in: an ore without them is never rolled. The druid
 *   ({@link OreEntry#druid()}) ignores addon ores unless the ore opts in.</li>
 * </ul>
 *
 * <p>Merging follows {@link TownKitCooking}: Java entries in the order they were registered, then the data files
 * ({@code data/<namespace>/townkit/ores/*.json}) in file-id order, re-read on {@code /reload}; a removal wins; adding an
 * id that exists replaces it whole (logged). {@link #changeOre} and {@link #removeOre} do in Java what a {@code change}
 * and a {@code remove} file do. Data files:
 * {@code {"action":"add","id":"mymod:tin","stone":"mymod:tin_ore","deepslate":"mymod:deepslate_tin_ore","depths":[0,64],
 * "lucky_rolls":{"weight":6.0,"low":0,"high":64}}} ({@code deepslate}, {@code label}, {@code depths}, {@code lucky_rolls}
 * and {@code druid} (same shape as {@code lucky_rolls}) optional), {@code {"action":"change","id":"mymod:tin",
 * "depths":[0,32]}} (each field present replaces it; {@code null} clears an optional one) and
 * {@code {"action":"remove","id":"mymod:tin"}}. A file naming a Towntopia ore, an unknown block, or a bad range is
 * skipped (logged). TownKit ships no ores of its own.
 *
 * <p>With nothing registered, Towntopia's miner and druid run on their own objects, untouched. With TownKit's hooks
 * switched off, {@link #ores()} still answers but Towntopia's miner and druid keep their own seven ores. Register at mod
 * setup or later, from any thread.
 *
 * @since 0.7.1
 */
public final class TownKitMining {
    private TownKitMining() {
    }

    private static TownKitWorkView view() {
        return TownKitBackend.get().work();
    }

    /**
     * Adds {@code ore} until the subscription is removed; an ore with the same id is replaced whole (logged). An ore
     * whose id is one of Towntopia's seven, or whose block is air, is skipped (logged). Throws IllegalArgumentException
     * if {@code owner} is blank.
     */
    public static Subscription ore(String owner, OreEntry ore) {
        return view().ore(owner, ore);
    }

    /**
     * Changes the ore {@code id} (another addon's or a data file's) until the subscription is removed, like a
     * {@code "change"} ore file: {@code change} gets the ore as it stands at that point of the merge and answers the
     * changed ore (start from {@link OreEntry#toBuilder()}). If no such ore exists the change waits for one (logged once
     * when the server has started without it). A change answering another id, one of Towntopia's ids or an air block is
     * skipped (logged once), and so is a change of one of Towntopia's seven (they stay Towntopia's). Throws
     * IllegalArgumentException if {@code owner} is blank.
     *
     * @since 0.7.1
     */
    public static Subscription changeOre(String owner, ResourceLocation id, UnaryOperator<OreEntry> change) {
        return view().changeOre(owner, id, change);
    }

    /**
     * Removes the ore {@code id} (another addon's or a data file's) until the subscription is removed, like a
     * {@code "remove"} ore file: a removal wins over every add and change of that id. Removing one of Towntopia's seven
     * is skipped (logged once): they stay Towntopia's. Throws IllegalArgumentException if {@code owner} is blank.
     *
     * @since 0.7.1
     */
    public static Subscription removeOre(String owner, ResourceLocation id) {
        return view().removeOre(owner, id);
    }

    /** The addon ores in merge order (Java entries, then data files); Towntopia's seven are not in it; immutable. */
    public static List<OreEntry> ores() {
        return view().ores();
    }
}
