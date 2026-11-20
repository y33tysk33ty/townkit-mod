package townkit.api.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.SeedSource;

/**
 * What TownKit's farm module (the farmer, away and rancher changes addons and data files make) exposes to the API.
 * Addons use {@code townkit.api.farm.TownKitFarming} instead.
 *
 * @since 0.8.0
 */
@ApiStatus.Internal
public interface TownKitFarmView {
    /** @see townkit.api.farm.TownKitFarming#seedFrom */
    Subscription seedFrom(String owner, Item seed, Item produce, int seedsPerProduce);

    /** @see townkit.api.farm.TownKitFarming#removeSeedFrom */
    Subscription removeSeedFrom(String owner, Item seed);

    /** @see townkit.api.farm.TownKitFarming#seedSources */
    Map<Item, SeedSource> seedSources();

    /** @see townkit.api.farm.TownKitFarming#harvest */
    Subscription harvest(String owner, HarvestRule rule);

    /** @see townkit.api.farm.TownKitFarming#changeHarvest */
    Subscription changeHarvest(String owner, ResourceLocation id, UnaryOperator<HarvestRule> change);

    /** @see townkit.api.farm.TownKitFarming#removeHarvest */
    Subscription removeHarvest(String owner, ResourceLocation id);

    /** @see townkit.api.farm.TownKitFarming#harvests */
    List<HarvestRule> harvests();

    /** @see townkit.api.farm.TownKitFarming#harvestFor */
    Optional<HarvestRule> harvestFor(BlockState state);
}
