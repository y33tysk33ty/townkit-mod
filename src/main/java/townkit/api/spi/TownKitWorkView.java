package townkit.api.spi;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.work.CookStation;
import townkit.api.work.Dish;
import townkit.api.work.DishChange;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.ForgeRecipeChange;
import townkit.api.work.OreEntry;
import townkit.api.work.TierPlace;

/**
 * What TownKit's work tables (the cook, blacksmith and miner changes addons and data files make) expose to the API.
 * Later modules add their methods here. Addons use the module APIs instead.
 *
 * @since 0.7.1
 */
@ApiStatus.Internal
public interface TownKitWorkView {
    /** The kinds of data files with a table registered, e.g. {@code cook_dishes}. */
    Set<String> kinds();

    /** @see townkit.api.work.TownKitCooking#addDish */
    Subscription addDish(String owner, Dish dish);

    /** @see townkit.api.work.TownKitCooking#changeDish */
    Subscription changeDish(String owner, Item result, DishChange change);

    /** @see townkit.api.work.TownKitCooking#removeDish */
    Subscription removeDish(String owner, Item result);

    /** @see townkit.api.work.TownKitCooking#dishes */
    List<Dish> dishes();

    /** @see townkit.api.work.TownKitCooking#station */
    Subscription station(String owner, ResourceLocation id, CookStation station);

    /** @see townkit.api.work.TownKitCooking#removeStation */
    Subscription removeStation(String owner, ResourceLocation id);

    /** @see townkit.api.work.TownKitForge#material */
    Subscription forgeMaterial(String owner, ResourceLocation id, TagKey<Item> items);

    /** @see townkit.api.work.TownKitForge#tier */
    Subscription forgeTier(String owner, ResourceLocation id, TierPlace place);

    /** @see townkit.api.work.TownKitForge#removeMaterial */
    Subscription removeForgeMaterial(String owner, ResourceLocation id);

    /** @see townkit.api.work.TownKitForge#removeTier */
    Subscription removeForgeTier(String owner, ResourceLocation id);

    /** @see townkit.api.work.TownKitForge#addRecipe */
    Subscription addForgeRecipe(String owner, ForgeRecipe recipe);

    /** @see townkit.api.work.TownKitForge#changeRecipe */
    Subscription changeForgeRecipe(String owner, Item result, ForgeRecipeChange change);

    /** @see townkit.api.work.TownKitForge#removeRecipe */
    Subscription removeForgeRecipe(String owner, Item result);

    /** @see townkit.api.work.TownKitForge#smelt */
    Subscription smelt(String owner, Item raw, Item result);

    /** @see townkit.api.work.TownKitForge#removeSmelt */
    Subscription removeSmelt(String owner, Item raw);

    /** @see townkit.api.work.TownKitMining#ore */
    Subscription ore(String owner, OreEntry ore);

    /** @see townkit.api.work.TownKitMining#changeOre */
    Subscription changeOre(String owner, ResourceLocation id, UnaryOperator<OreEntry> change);

    /** @see townkit.api.work.TownKitMining#removeOre */
    Subscription removeOre(String owner, ResourceLocation id);

    /** @see townkit.api.work.TownKitMining#ores */
    List<OreEntry> ores();

    /** @see townkit.api.work.TownKitForge#tierOf */
    Optional<ResourceLocation> forgeTierOf(Item item);
}
