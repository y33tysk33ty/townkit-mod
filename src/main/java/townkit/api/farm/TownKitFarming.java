package townkit.api.farm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitFarmView;

/**
 * What Towntopia's farmer grows and harvests. The farmer's skill bonus already reaches addon crops through the item
 * tag {@code townkit:farmer_bonus_produce}: an item listed there that drops from a harvest may roll the bonus.
 *
 * @since 0.8.0
 */
public final class TownKitFarming {
    private TownKitFarming() {
    }

    private static TownKitFarmView view() {
        return TownKitBackend.get().farm();
    }

    /**
     * Lets Towntopia's farmer make {@code seed} from {@code produce}, {@code seedsPerProduce} seeds for each, the way she
     * makes melon and pumpkin seeds: when a field wants the seed and neither she nor any storage has any, she fetches or
     * uses the produce and makes up to 16 seeds. For crops whose harvest gives no seeds (an addon corn whose kernels are
     * only the planting item). The seed should be a planting item of a {@code farmer_crops} definition; one that is not
     * is kept but never planted (logged once after the server starts). An entry whose seed is melon or pumpkin seeds
     * (Towntopia's own two, which stay Towntopia's) or whose count is under 1 is skipped (logged); adding a seed that
     * already has an entry replaces it whole (logged). Data files:
     * {@code data/<namespace>/townkit/farm_seeds/*.json}, {@code {"action":"add","seed":"mymod:corn_kernels",
     * "produce":"mymod:corn","seeds_per_produce":2}}, a {@code change} (each of {@code produce} and
     * {@code seeds_per_produce} present replaces it) and {@code {"action":"remove","seed":"mymod:corn_kernels"}}; Java
     * entries first, then files in file-id order, a removal wins, re-read on {@code /reload}. With nothing registered,
     * Towntopia's seed crafting runs untouched. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription seedFrom(String owner, Item seed, Item produce, int seedsPerProduce) {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(produce, "produce");
        return view().seedFrom(owner, seed, produce, seedsPerProduce);
    }

    /**
     * Removes the seed's entry (another addon's or a data file's), like a {@code "remove"} file: a removal wins over
     * every add and change of that seed. Melon and pumpkin seeds stay Towntopia's: removing them is skipped (logged).
     * Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription removeSeedFrom(String owner, Item seed) {
        Objects.requireNonNull(seed, "seed");
        return view().removeSeedFrom(owner, seed);
    }

    /** The seeds TownKit lets the farmer make, with what from (Towntopia's own two are not in it); immutable. */
    public static Map<Item, SeedSource> seedSources() {
        return view().seedSources();
    }

    // --- harvest rules ---------------------------------------------------------------------------------------------

    /**
     * Adds a harvest rule: when a crop block Towntopia already knows (from any {@code farmer_crops} file) counts as ripe,
     * and what the farmer does to harvest it. Towntopia still finds, plans, plants and shows the crop; the rule changes
     * only the ripe answer and the harvest. Works for crops whose {@code farmer_crops} file says
     * {@code break_and_replant} (field crops) and for the bush, vine and pod definitions Towntopia uses; a rule on a
     * {@code pick_keep_plant} field crop does nothing (Towntopia never harvests those), and a rule no {@code farmer_crops}
     * definition matches does nothing: both are logged once after the server starts. A {@link Ripe.AtLeast} or
     * {@link Ripe.Is} property the block doesn't have, or a value outside its range, leaves the block out (a rule left
     * with no block is dropped); logged once. When two rules hold the same block, the first in merge order keeps it.
     * Adding an id that already exists replaces it whole (logged). Data files:
     * {@code data/<namespace>/townkit/farm_harvest/*.json}, for example {@code {"action":"add","id":"mymod:blueberry",
     * "blocks":["mymod:blueberry_bush"],"ripe":{"property":"age","at_least":3},"harvest":{"type":"use"}}}; Java entries
     * first, then files in file-id order, a removal wins, re-read on {@code /reload}. With no rule registered
     * Towntopia's farmer runs untouched. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription harvest(String owner, HarvestRule rule) {
        Objects.requireNonNull(rule, "rule");
        return view().harvest(owner, rule);
    }

    /**
     * Changes the rule with {@code id} (another addon's or a data file's too) by {@code change}; a change of an unknown id
     * is logged. The returned rule must keep the id. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription changeHarvest(String owner, ResourceLocation id, UnaryOperator<HarvestRule> change) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(change, "change");
        return view().changeHarvest(owner, id, change);
    }

    /**
     * Removes the rule with {@code id}, like a {@code "remove"} file: a removal wins over every add and change of that
     * id. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription removeHarvest(String owner, ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        return view().removeHarvest(owner, id);
    }

    /** The harvest rules in merge order; immutable. */
    public static List<HarvestRule> harvests() {
        return view().harvests();
    }

    /** The rule that holds this block state's block, if any. */
    public static Optional<HarvestRule> harvestFor(BlockState state) {
        Objects.requireNonNull(state, "state");
        return view().harvestFor(state);
    }
}
